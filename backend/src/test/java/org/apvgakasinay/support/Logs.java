package org.apvgakasinay.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reading captured log output by its records, not by words that also occur in class names. */
public final class Logs {

    /** The start of a log record of the application's format: timestamp, level, process id, "---". */
    private static final Pattern RECORD = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s.*");

    private Logs() {
    }

    /** The records of one level, each cut to a readable length. */
    public static List<String> records(String output, String level) {
        List<String> found = new ArrayList<>();
        for (String line : output.split("\\R")) {
            var matcher = RECORD.matcher(line);
            if (matcher.matches() && matcher.group(1).equals(level)) {
                found.add(line.substring(0, Math.min(line.length(), 500)));
            }
        }
        return found;
    }

    /** Records at ERROR: what the application logs when a request fails unexpectedly. */
    public static List<String> errorRecords(String output) {
        return records(output, "ERROR");
    }

    /** Lines of a printed stack trace that pass through the application's own code. */
    public static List<String> applicationStackFrames(String output) {
        List<String> found = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("\tat org.apvgakasinay.")) {
                found.add(line);
            }
        }
        return found;
    }
}
