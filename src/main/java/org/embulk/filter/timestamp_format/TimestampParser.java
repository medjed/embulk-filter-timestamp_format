package org.embulk.filter.timestamp_format;

import com.google.common.base.Optional;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;

import org.embulk.filter.timestamp_format.TimestampFormatFilterPlugin.PluginTask;

import org.embulk.spi.time.JRubyTimeParserHelper;
import org.embulk.spi.time.JRubyTimeParserHelperFactory;
import org.embulk.spi.time.Timestamp;

import static org.embulk.spi.time.TimestampFormat.parseDateTimeZone;

import org.embulk.spi.time.TimestampParseException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.jruby.embed.ScriptingContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joda.time.format.DateTimeFormat;

public class TimestampParser {
    public interface Task {
        @Config("default_from_timezone")
        @ConfigDefault("\"UTC\"")
        DateTimeZone getDefaultFromTimeZone();

        @Config("default_from_timestamp_format")
        @ConfigDefault("[\"%Y-%m-%d %H:%M:%S.%N %z\"]")
        List<String> getDefaultFromTimestampFormat();
    }

    public interface TimestampColumnOption {
        @Config("from_timezone")
        @ConfigDefault("null")
        Optional<DateTimeZone> getFromTimeZone();

        @Config("from_format")
        @ConfigDefault("null")
        Optional<List<String>> getFromFormat();
    }

    private final List<JRubyTimeParserHelper> jrubyParserList = new ArrayList<>();
    private final List<DateTimeFormatter> javaParserList = new ArrayList<>();
    private final DateTimeZone defaultFromTimeZone;

    TimestampParser(PluginTask task) {
        this(task.getJRuby(), task.getDefaultFromTimestampFormat(), task.getDefaultFromTimeZone());
    }

    public TimestampParser(PluginTask task, TimestampColumnOption columnOption) {
        this(task.getJRuby(),
                columnOption.getFromFormat().or(task.getDefaultFromTimestampFormat()),
                columnOption.getFromTimeZone().or(task.getDefaultFromTimeZone()));
    }

    public TimestampParser(ScriptingContainer jruby, List<String> formatList, DateTimeZone defaultFromTimeZone) {
        JRubyTimeParserHelperFactory helperFactory = (JRubyTimeParserHelperFactory) jruby.runScriptlet("Embulk::Java::TimeParserHelper::Factory.new");

        // TODO get default current time from ExecTask.getExecTimestamp
        for (String format : formatList) {
            if (format.contains("%")) {
                JRubyTimeParserHelper helper = (JRubyTimeParserHelper) helperFactory.newInstance(format, 1970, 1, 1, 0, 0, 0, 0);  // TODO default time zone
                this.jrubyParserList.add(helper);
            } else {
                DateTimeFormatter parser = DateTimeFormat.forPattern(format).withLocale(Locale.ENGLISH).withZone(defaultFromTimeZone);
                this.javaParserList.add(parser);
            }
        }
        this.defaultFromTimeZone = defaultFromTimeZone;
    }

    public DateTimeZone getDefaultFromTimeZone() {
        return defaultFromTimeZone;
    }

    public Timestamp parse(String text) throws TimestampParseException, IllegalArgumentException {
        if (!jrubyParserList.isEmpty()) {
            return jrubyParse(text);
        } else if (!javaParserList.isEmpty()) {
            return javaParse(text);
        } else {
            assert false;
            throw new RuntimeException();
        }
    }

    private Timestamp jrubyParse(String text) throws TimestampParseException {
        long localUsec = -1;
        TimestampParseException exception = null;

        JRubyTimeParserHelper helper = null;
        for (JRubyTimeParserHelper h : jrubyParserList) {
            helper = h;
            try {
                localUsec = helper.strptimeUsec(text); // NOTE: micro second resolution
                break;
            } catch (TimestampParseException ex) {
                exception = ex;
            }
        }
        if (localUsec == -1) {
            throw exception;
        }
        DateTimeZone timeZone = defaultFromTimeZone;
        String zone = helper.getZone();

        if (zone != null) {
            // TODO cache parsed zone?
            timeZone = parseDateTimeZone(zone);
            if (timeZone == null) {
                throw new TimestampParseException("Invalid time zone name '" + text + "'");
            }
        }

        long localSec = localUsec / 1000000;
        long usec = localUsec % 1000000;
        long sec = timeZone.convertLocalToUTC(localSec * 1000, false) / 1000;

        return Timestamp.ofEpochSecond(sec, usec * 1000);
    }

    private Timestamp javaParse(String text) throws IllegalArgumentException {
        DateTime dateTime = null;
        IllegalArgumentException exception = null;

        for (DateTimeFormatter parser : javaParserList) {
            try {
                dateTime = parser.parseDateTime(text);
                break;
            } catch (IllegalArgumentException ex) {
                exception = ex;
            }
        }
        if (dateTime == null) {
            throw exception;
        }
        long msec = dateTime.getMillis(); // NOTE: milli second resolution

        long nanoAdjustment = msec * 1000000;
        return Timestamp.ofEpochSecond(0, nanoAdjustment);
    }
}
