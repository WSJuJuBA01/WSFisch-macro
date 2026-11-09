
package com.wsfisch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    private static final Pattern NUMBER =
            Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final ScriptHost host;

    public ScriptEngine(ScriptHost host) {
        this.host = host;
    }

    public void run(String script) throws Exception {
        if (script == null || script.trim().isEmpty()) {
            throw new IllegalArgumentException("Script is empty");
        }

        List<String> lines = new ArrayList<>();
        for (String raw : script.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty()
                    && !line.startsWith(";")
                    && !line.startsWith("//")) {
                lines.add(line);
            }
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Script has no commands");
        }

        executeBlock(lines, 0, lines.size());
    }

    private void executeBlock(
            List<String> lines,
            int start,
            int end
    ) throws Exception {
        int i = start;

        while (i < end && !host.isStopRequested()) {
            String line = lines.get(i).trim();
            String lower = line.toLowerCase(Locale.US);

            if (line.equals("{")) {
                i++;
                continue;
            }

            if (line.equals("}")) {
                return;
            }

            if (lower.startsWith("loop")) {
                int count = parseFirstInteger(
                        line.substring(4), "Loop"
                );

                int bodyStart = i + 1;

                if (bodyStart >= end
                        || !lines.get(bodyStart).trim().equals("{")) {
                    throw new IllegalArgumentException(
                            "Loop must be followed by '{'"
                    );
                }

                int depth = 1;
                int bodyEnd = bodyStart + 1;

                while (bodyEnd < end && depth > 0) {
                    String candidate = lines.get(bodyEnd).trim();

                    if (candidate.equals("{")) depth++;
                    if (candidate.equals("}")) depth--;

                    if (depth > 0) bodyEnd++;
                }

                if (depth != 0) {
                    throw new IllegalArgumentException(
                            "Loop is missing '}'"
                    );
                }

                for (int n = 0;
                     n < count && !host.isStopRequested();
                     n++) {
                    executeBlock(
                            lines,
                            bodyStart + 1,
                            bodyEnd
                    );
                }

                i = bodyEnd + 1;
                continue;
            }

            if (lower.startsWith("click")) {
                float[] point = parseClick(line);

                if (!host.isStopRequested()) {
                    host.click(point[0], point[1]);
                }

                i++;
                continue;
            }

            if (lower.startsWith("sleep")) {
                long millis = parseFirstLong(
                        line.substring(5), "Sleep"
                );

                if (millis < 0) {
                    throw new IllegalArgumentException(
                            "Sleep duration cannot be negative"
                    );
                }

                host.sleep(millis);
                i++;
                continue;
            }

            if (line.startsWith("#")) {
                i++;
                continue;
            }

            host.log("Unsupported command: " + line);
            i++;
        }
    }

    private static float[] parseClick(String line) {
        // Убираем название команды до разбора координат.
        String arguments = line.substring(5);
        Matcher matcher = NUMBER.matcher(arguments);

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Click requires X and Y coordinates: " + line
            );
        }

        float x = Float.parseFloat(matcher.group());

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Click requires both X and Y coordinates: " + line
            );
        }

        float y = Float.parseFloat(matcher.group());

        return new float[]{x, y};
    }

    private static int parseFirstInteger(
            String text,
            String command
    ) {
        long value = parseFirstLong(text, command);

        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    command + " value is too large"
            );
        }

        return (int) value;
    }

    private static long parseFirstLong(
            String text,
            String command
    ) {
        Matcher matcher = Pattern.compile("-?\\d+").matcher(text);

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    command + " requires a number"
            );
        }

        return Long.parseLong(matcher.group());
    }
}
