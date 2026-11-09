package com.wsfisch.engine;

public interface ScriptHost {
    void click(float x, float y);
    void swipe(float x1, float y1, float x2, float y2, long durationMs);
    void sleep(long millis) throws InterruptedException;
    void log(String message);
    boolean isStopRequested();
    int pixelColor(int x, int y);
    int[] findPixel(int x1, int y1, int x2, int y2, int color, int tolerance);
}
