package com.wsfisch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Небольшой Android-интерпретатор команд; это не полный AutoHotkey runtime. */
public final class ScriptEngine {
    private static final int MAX_LINES = 20_000;
    private static final int MAX_LOOP = 10_000;
    private static final int MAX_DEPTH = 32;

    private final ScriptHost host;

    public ScriptEngine(ScriptHost host) {
        this.host = host;
    }

    public void run(String source) throws InterruptedException {
        if (source == null || source.length() > 1_000_000) {
            throw new IllegalArgumentException("Script is empty or too large");
        }

        List<String> lines = new ArrayList<>();
        for (String raw : source.split("\\R", -1)) {
            String line = raw.replaceFirst(";.*$", "").trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                lines.add(line);
                if (lines.size() > MAX_LINES) {
                    throw new IllegalArgumentException("Too many script lines");
                }
            }
        }

        executeBlock(lines, 0, lines.size(), 0);
    }

    private void executeBlock(
        List<String> lines,
        int start,
        int end,
        int depth
    ) throws InterruptedException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Maximum loop nesting exceeded");
        }

        int i = start;
        while (i < end && !host.isStopRequested()) {
            String line = lines.get(i).trim();

            if (line.equals("{") || line.equals("}")) {
                i++;
                continue;
            }

            Matcher loop = Pattern.compile(
                "(?i)^Loop(?:\\s*,\\s*|\\s+)(\\d+)\\s*\\{?$|" +
                "^Loop\\s*\\(\\s*(\\d+)\\s*\\)\\s*\\{?$"
            ).matcher(line);

            if (loop.matches()) {
                String countText = loop.group(1) != null ? loop.group(1) : loop.group(2);
                int count = Integer.parseInt(countText);

                if (count > MAX_LOOP) {
                    throw new IllegalArgumentException("Loop limit is 10000 iterations");
                }

                int open = i;
                if (!line.endsWith("{")) {
                    if (i + 1 >= end || !lines.get(i + 1).trim().equals("{")) {
                        throw new IllegalArgumentException(
                            "Loop requires a { ... } block at line " + (i + 1)
                        );
                    }
                    open = i + 1;
                }

                int close = findClose(lines, open + 1, end);
                if (close < 0) {
                    throw new IllegalArgumentException("Loop is missing closing }");
                }

                for (int n = 0; n < count && !host.isStopRequested(); n++) {
                    executeBlock(lines, open + 1, close, depth + 1);
                }

                i = close + 1;
                continue;
            }

            if (!executeCommand(line)) {
                throw new IllegalArgumentException(
                    "Unsupported command at line " + (i + 1) + ": " + line
                );
            }
            i++;
        }
    }

    private int findClose(List<String> lines, int start, int end) {
        int nesting = 0;
        for (int i = start; i < end; i++) {
            String line = lines.get(i).trim();
            if (line.equals("{")) {
                nesting++;
            } else if (line.equals("}")) {
                if (nesting == 0) return i;
                nesting--;
            }
        }
        return -1;
    }

    private boolean executeCommand(String line) throws InterruptedException {
        Matcher m = Pattern.compile(
            "(?i)^Click\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            host.click(Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)));
            return true;
        }

        m = Pattern.compile(
            "(?i)^Click\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            host.click(Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)));
            return true;
        }

        m = Pattern.compile(
            "(?i)^Sleep\\s*,\\s*(\\d+)\\s*$|^Sleep\\s*\\(\\s*(\\d+)\\s*\\)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            long millis = Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
            if (millis > 86_400_000L) {
                throw new IllegalArgumentException("Sleep limit is 24 hours");
            }
            host.sleep(millis);
            return true;
        }

        m = Pattern.compile(
            "(?i)^Swipe\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*" +
            "(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*" +
            "(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            host.swipe(
                Float.parseFloat(m.group(1)),
                Float.parseFloat(m.group(2)),
                Float.parseFloat(m.group(3)),
                Float.parseFloat(m.group(4)),
                Long.parseLong(m.group(5))
            );
            return true;
        }

        m = Pattern.compile(
            "(?i)^PixelGetColor\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            int color = host.pixelColor(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))
            );
            host.log(String.format(Locale.ROOT, "Color: 0x%06X", color & 0xFFFFFF));
            return true;
        }

        m = Pattern.compile(
            "(?i)^PixelSearch\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*" +
            "(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(0x[0-9a-f]{6}|#[0-9a-f]{6})" +
            "\\s*(?:,\\s*(\\d+))?\\s*$"
        ).matcher(line);
        if (m.matches()) {
            int color = parseColor(m.group(5));
            int tolerance = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            int[] point = host.findPixel(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)),
                color,
                tolerance
            );
            host.log(
                point == null
                    ? "PixelSearch: not found"
                    : "PixelSearch: found at " + point[0] + "," + point[1]
            );
            return true;
        }

        m = Pattern.compile(
            "(?i)^WaitPixel\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*" +
            "(0x[0-9a-f]{6}|#[0-9a-f]{6})\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*$"
        ).matcher(line);
        if (m.matches()) {
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int color = parseColor(m.group(3));
            int tolerance = Integer.parseInt(m.group(4));
            long timeoutMs = Long.parseLong(m.group(5));

            if (timeoutMs > 120_000L) {
                throw new IllegalArgumentException("WaitPixel timeout limit is 120000 ms");
            }
            if (tolerance > 255) {
                throw new IllegalArgumentException("Color tolerance must be 0..255");
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean found = false;
            while (!host.isStopRequested() && System.currentTimeMillis() <= deadline) {
                if (closeColor(host.pixelColor(x, y), color, tolerance)) {
                    found = true;
                    break;
                }
                host.sleep(50);
            }
            host.log(found ? "WaitPixel: matched" : "WaitPixel: timed out");
            return true;
        }

        return false;
    }

    private static int parseColor(String text) {
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.regionMatches(true, 0, "0x", 0, 2)) {
            value = value.substring(2);
        }
        return (int) Long.parseLong(value, 16) & 0xFFFFFF;
    }

    private static boolean closeColor(int actual, int target, int tolerance) {
        return Math.abs(((actual >> 16) & 0xFF) - ((target >> 16) & 0xFF)) <= tolerance
            && Math.abs(((actual >> 8) & 0xFF) - ((target >> 8) & 0xFF)) <= tolerance
            && Math.abs((actual & 0xFF) - (target & 0xFF)) <= tolerance;
    }
    }
