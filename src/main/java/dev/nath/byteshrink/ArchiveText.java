package dev.nath.byteshrink;

/** Literal, single-line display of file paths and ZIP entry names. */
final class ArchiveText {
    private ArchiveText() {}

    static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\') result.append("\\\\");
            else if (Character.isISOControl(c)) result.append(String.format("\\u%04X", (int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
