package com.wsfisch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ScriptEngine {
    private final ScriptHost host;

    public ScriptEngine(ScriptHost host) {
        this.host = host;
    }

    public void run(String script) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String line : script.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith(";")) {
                lines.add(trimmed);
            }
        }

        for (String line : lines) {
            if (line.startsWith("#")) {
                continue;
            }

            if (line.toLowerCase(Locale.US).startsWith("sleep")) {
                long millis = parseMillis(line);
                if (millis > 0) {
                    host.sleep(millis);
                }
                continue;
            }

            if (line.toLowerCase(Locale.US).startsWith("click")) {
                ParsedClick click = parseClick(line);
                host.click(click.x, click.y);
                continue;
            }

            host.log("Command: " + line);
            if (line.contains("Loop")) {
                host.log("Loop command detected");
            }
        }
    }

    private static long parseMillis(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 2) {
            String value = parts[1].trim().replaceAll("[^0-9-]", "");
            return Long.parseLong(value);
        }
        return 0L;
    }

    private static ParsedClick parseClick(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 2) {
            String x = parts[0].replaceAll("[^0-9.-]", "").trim();
            String y = parts[1].replaceAll("[^0-9.-]", "").trim();
            return new ParsedClick(Float.parseFloat(x), Float.parseFloat(y));
        }
        return new ParsedClick(0f, 0f);
    }

    private static class ParsedClick {
        float x;
        float y;
        ParsedClick(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
