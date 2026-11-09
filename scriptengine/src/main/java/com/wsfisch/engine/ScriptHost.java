package com.wsfisch.engine;

public interface ScriptHost {
    void click(float x, float y);
    void sleep(long millis) throws InterruptedException;
    void log(String message);
    boolean isStopRequested();
}
