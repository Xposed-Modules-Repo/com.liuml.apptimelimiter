package com.liuml.apptimelimiter.nonroot;

interface IShizukuExecutionService {
    int forceStopPackage(String packageName, int userId);
    void destroy();
}
