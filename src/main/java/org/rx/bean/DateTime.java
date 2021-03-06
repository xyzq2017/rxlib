package org.rx.bean;

import org.apache.commons.lang3.time.FastDateFormat;
import org.rx.*;

import java.text.ParseException;
import java.time.DayOfWeek;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.rx.$.$;
import static org.rx.Contract.require;
import static org.rx.Contract.values;

/**
 * http://www.mkyong.com/java/how-to-calculate-date-time-difference-in-java/
 */
public final class DateTime extends Date {
    public static final TimeZone       UtcZone  = TimeZone.getTimeZone("UTC");
    public static final DateTime       BaseDate = new DateTime(2000, 1, 1);
    public static final NQuery<String> Formats  = NQuery.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss,SSS",
            "yyyyMMddHHmmssSSS", "yyyy-MM-dd HH:mm:ss,SSSZ");

    public static DateTime now() {
        return new DateTime(System.currentTimeMillis());
    }

    public static DateTime utcNow() {
        return now().asUniversalTime();
    }

    @ErrorCode(cause = ParseException.class, messageKeys = { "$formats", "$date" })
    public static DateTime valueOf(String dateString) {
        SystemException lastEx = null;
        for (String format : Formats) {
            try {
                return valueOf(dateString, format);
            } catch (SystemException ex) {
                lastEx = ex;
            }
        }
        $<ParseException> out = $();
        Exception nested = lastEx.tryGet(out, ParseException.class) ? out.$ : lastEx;
        throw new SystemException(values(String.join(",", Formats), dateString), nested);
    }

    public static DateTime valueOf(String dateString, String format) {
        try {
            //SimpleDateFormat not thread safe
            return new DateTime(FastDateFormat.getInstance(format).parse(dateString));
        } catch (ParseException ex) {
            throw SystemException.wrap(ex);
        }
    }

    private Calendar calendar;

    private Calendar getCalendar() {
        if (calendar == null) {
            calendar = Calendar.getInstance();
            calendar.setTimeInMillis(super.getTime());
        }
        return calendar;
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getYear() {
        return getCalendar().get(Calendar.YEAR);
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getMonth() {
        return getCalendar().get(Calendar.MONTH);
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getDay() {
        return getCalendar().get(Calendar.DAY_OF_MONTH);
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getHours() {
        return getCalendar().get(Calendar.HOUR_OF_DAY);
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getMinutes() {
        return getCalendar().get(Calendar.MINUTE);
    }

    @SuppressWarnings(Const.AllWarnings)
    @Override
    public int getSeconds() {
        return getCalendar().get(Calendar.SECOND);
    }

    public int getMillisecond() {
        return getCalendar().get(Calendar.MILLISECOND);
    }

    public int getDayOfYear() {
        return getCalendar().get(Calendar.DAY_OF_YEAR);
    }

    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of(getCalendar().get(Calendar.DAY_OF_WEEK));
    }

    public long getTotalDays() {
        return super.getTime() / (24 * 60 * 60 * 1000);
    }

    public long getTotalHours() {
        return super.getTime() / (60 * 60 * 1000);
    }

    public long getTotalMinutes() {
        return super.getTime() / (60 * 1000);
    }

    public long getTotalSeconds() {
        return super.getTime() / (1000);
    }

    public long getTotalMilliseconds() {
        return super.getTime();
    }

    public DateTime(int year, int month, int day) {
        this(year, month, day, 0, 0, 0);
    }

    public DateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = getCalendar();
        c.set(year, month, day, hour, minute, second);
        super.setTime(c.getTimeInMillis());
    }

    public DateTime(long ticks) {
        super(ticks);
    }

    public DateTime(Date date) {
        super(date.getTime());
    }

    @Override
    public synchronized void setTime(long time) {
        super.setTime(time);
        if (calendar != null) {
            calendar.setTimeInMillis(time);
        }
    }

    public DateTime addYears(int value) {
        return add(Calendar.YEAR, value);
    }

    public DateTime addMonths(int value) {
        return add(Calendar.MONTH, value);
    }

    public DateTime addDays(int value) {
        return add(Calendar.DAY_OF_MONTH, value);
    }

    public DateTime addHours(int value) {
        return add(Calendar.HOUR_OF_DAY, value);
    }

    public DateTime addMinutes(int value) {
        return add(Calendar.MINUTE, value);
    }

    public DateTime addSeconds(int value) {
        return add(Calendar.SECOND, value);
    }

    public DateTime addMilliseconds(int value) {
        return add(Calendar.MILLISECOND, value);
    }

    private DateTime add(int field, int value) {
        Calendar c = getCalendar();
        long mark = c.getTimeInMillis();
        c.set(field, c.get(field) + value);
        try {
            return new DateTime(c.getTimeInMillis());
        } finally {
            c.setTimeInMillis(mark);
        }
    }

    public DateTime addTicks(long ticks) {
        return new DateTime(super.getTime() + ticks);
    }

    public DateTime add(Date value) {
        require(value);

        return addTicks(value.getTime());
    }

    public DateTime subtract(Date value) {
        require(value);

        return new DateTime(super.getTime() - value.getTime());
    }

    public DateTime asLocalTime() {
        getCalendar().setTimeZone(TimeZone.getDefault());
        return this;
    }

    public DateTime asUniversalTime() {
        getCalendar().setTimeZone(UtcZone);
        return this;
    }

    public String toDateTimeString() {
        return toString(Formats.first());
    }

    @Override
    public String toString() {
        return toString(Formats.last());
    }

    public String toString(String format) {
        require(format);

        return FastDateFormat.getInstance(format, getCalendar().getTimeZone()).format(this);
    }
}
