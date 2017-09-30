package org.rx.util;

import org.rx.App;

public final class StringBuilder {
    private java.lang.StringBuilder buffer;
    private String                  prefix;

    public java.lang.StringBuilder getBuffer() {
        return buffer;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public StringBuilder() {
        buffer = new java.lang.StringBuilder();
        prefix = App.EmptyString;
    }

    public StringBuilder replace(String target, String replacement) {
        int index = 0;
        while ((index = buffer.indexOf(target, index)) != -1) {
            buffer.replace(index, index + target.length(), replacement);
            index += replacement.length(); // Move to the end of the replacement
        }
        return this;
    }

    public StringBuilder append(Object obj) {
        buffer.append(prefix).append(obj);
        return this;
    }

    public StringBuilder append(String format, Object... args) {
        buffer.append(prefix).append(String.format(format, args));
        return this;
    }

    public StringBuilder appendLine() {
        append(System.lineSeparator());
        return this;
    }

    public StringBuilder appendLine(Object obj) {
        buffer.append(prefix).append(obj).append(System.lineSeparator());
        return this;
    }

    public StringBuilder appendLine(String format, Object... args) {
        buffer.append(prefix).append(String.format(format, args)).append(System.lineSeparator());
        return this;
    }

    @Override
    public String toString() {
        return buffer.toString();
    }
}
