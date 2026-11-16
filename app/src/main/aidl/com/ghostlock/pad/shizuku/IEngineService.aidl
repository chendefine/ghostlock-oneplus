package com.ghostlock.pad.shizuku;

import com.ghostlock.pad.shizuku.IEngineCallback;

interface IEngineService {
    void destroy() = 16777114;
    String probe() = 2;
    void run(String expectedKernel, @nullable String managerPackage, IEngineCallback callback) = 3;
}
