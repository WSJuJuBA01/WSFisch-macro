package com.wsfisch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptEngine {
    private static final int MAX_LINES = 20000;
    private static final int MAX_LOOP = 10000;
    private static final int MAX_DEPTH = 32;

    private final ScriptHost host;

    public ScriptEngine(ScriptHost host) {
        this.host = host;
    }

    public void run(String source) throws InterruptedException {
        List<String> lines = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            String line = raw.replaceFirst(";.*$", "").trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                lines.add(line);
            }
        }
        executeBlock(lines, 0, lines.size(), 0);
    }

    private void executeBlock(List<String> lines, int start, int end, int depth) throws InterruptedException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Maximum loop nesting exceeded");
        int i = start;
        while (i < end && !host.isStopRequested()) {
            String line = lines.get(i).trim();
            if (line.equals("{") || line.equals("}")) { i++; continue; }
            Matcher loop = Pattern.compile("(?i)^Loop(?:\\s*,\\s*|\\s+)(\\d+)\\s*\\{?$").matcher(line);
            if (loop.matches()) {
                int count = Integer.parseInt(loop.group(1));
                int open = i;
                if (!line.endsWith("{")) {
                    if (i + 1 >= end || !lines.get(i + 1).trim().equals("{")) throw new IllegalArgumentException("Loop requires a block");
                    open = i + 1;
                }
                int close = findClose(lines, open + 1, end);
                if (close < 0) throw new IllegalArgumentException("Loop missing closing brace");
                for (int n = 0; n < count && !host.isStopRequested(); n++) {
                    executeBlock(lines, open + 1, close, depth + 1);
                }
                i = close + 1;
                continue;
            }
            if (!executeCommand(line)) {
                throw new IllegalArgumentException("Unsupported command: " + line);
            }
            i++;
        }
    }

    private int findClose(List<String> lines, int start, int end) {
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

    private boolean executeCommand(String line) throws InterruptedException {
        Matcher click = Pattern.compile("(?i)^Click\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matcher(line);
        if (click.matches()) {
            host.click(Float.parseFloat(click.group(1)), Float.parseFloat(click.group(2)));
            return true;
        }
        Matcher sleep = Pattern.compile("(?i)^Sleep\\s*,\\s*(\\d+)\\s*$").matcher(line);
        if (sleep.matches()) {
            host.sleep(Long.parseLong(sleep.group(1)));
            return true;
        }
        Matcher swipe = Pattern.compile("(?i)^Swipe\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+)\\s*$").matcher(line);
        if (swipe.matches()) {
            host.swipe(
                    Float.parseFloat(swipe.group(1)),
                    Float.parseFloat(swipe.group(2)),
                    Float.parseFloat(swipe.group(3)),
                    Float.parseFloat(swipe.group(4)),
                    Long.parseLong(swipe.group(5))
            );
            return true;
        }
        return false;
    }
                }
