package com.wsfisch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    public enum Dialect {
        V1, V2, AUTO
    }

    private final ScriptHost host;

    public ScriptEngine(ScriptHost host) {
        this.host = host;
    }

    public Dialect detectDialect(String source) {
        if (source.contains("#Requires AutoHotkey v2"))
            return Dialect.V2;

        if (source.contains("#Requires AutoHotkey v1"))
            return Dialect.V1;

        if (source.matches("(?s).*\\bClick\\s*\\(.*"))
            return Dialect.V2;

        if (source.matches("(?s).*\\bClick\\s*,.*"))
            return Dialect.V1;

        return Dialect.AUTO;
    }

    public void run(String source) throws InterruptedException {
        List<String> lines = new ArrayList<>();

        for (String raw : source.split("\\R")) {
            String line = raw.replaceFirst(";.*$", "").trim();

            if (!line.isEmpty() && !line.startsWith("#")) {
                lines.add(line);
            }
        }

        executeBlock(lines, 0, lines.size());
    }

    private int executeBlock(
        List<String> lines,
        int start,
        int end
    ) throws InterruptedException {

        int i = start;

        while (i < end && !host.isStopRequested()) {
            String line = lines.get(i).trim();

            if (line.equals("{")) {
                i++;
                continue;
            }

            if (line.equals("}")) {
                return i + 1;
            }

            Matcher loop = Pattern.compile(
                "(?i)^Loop(?:\\s*,\\s*|\\s+)(\\d+)\\s*\\{?$"
            ).matcher(line);

            if (loop.find()) {
                int open = i;

                if (!line.endsWith("{")) {
                    if (i + 1 >= end ||
                        !lines.get(i + 1).trim().equals("{")) {
                        host.log("Loop: expected {");
                        i++;
                        continue;
                    }

                    open = i + 1;
                }

                int close = findClose(lines, open + 1, end);

                if (close < 0) {
                    host.log("Loop: missing }");
                    return end;
                }

                int count = Integer.parseInt(loop.group(1));

                for (int n = 0;
                     n < count && !host.isStopRequested();
                     n++) {
                    executeBlock(lines, open + 1, close);
                }

                i = close + 1;
                continue;
            }

            if (executeCommand(line)) {
                i++;
                continue;
            }

            host.log("Unsupported command: " + line);
            i++;
        }

        return i;
    }

    private int findClose(
        List<String> lines,
        int start,
        int end
    ) {
        int depth = 0;

        for (int i = start; i < end; i++) {
            String s = lines.get(i).trim();

            if (s.equals("{")) depth++;

            else if (s.equals("}")) {
                if (depth == 0) return i;
                depth--;
            }
        }

        return -1;
    }

    private boolean executeCommand(String line)
        throws InterruptedException {

        Matcher clickV2 = Pattern.compile(
            "(?i)^Click\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)\\s*$"
        ).matcher(line);

        Matcher clickV1 = Pattern.compile(
            "(?i)^Click\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$"
        ).matcher(line);

        Matcher sleepV2 = Pattern.compile(
            "(?i)^Sleep\\s*\\(\\s*(\\d+)\\s*\\)\\s*$"
        ).matcher(line);

        Matcher sleepV1 = Pattern.compile(
            "(?i)^Sleep\\s*,\\s*(\\d+)\\s*$"
        ).matcher(line);

        if (clickV2.matches()) {
            host.click(
                Float.parseFloat(clickV2.group(1)),
                Float.parseFloat(clickV2.group(2))
            );
            return true;
        }

        if (clickV1.matches()) {
            host.click(
                Float.parseFloat(clickV1.group(1)),
                Float.parseFloat(clickV1.group(2))
            );
            return true;
        }

        if (sleepV2.matches()) {
            host.sleep(Long.parseLong(sleepV2.group(1)));
            return true;
        }

        if (sleepV1.matches()) {
            host.sleep(Long.parseLong(sleepV1.group(1)));
            return true;
        }

        return false;
    }
}
