package com.ghostlock.pad.shizuku;

interface IEngineCallback {
    void onLog(String line);
    void onPhase(String phase);
    void onComplete(int exitCode, String summary);
}
