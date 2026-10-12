package com.liuml.apptimelimiter.nonroot;

interface IShizukuExecutionService {
    int forceStopPackage(String packageName, int userId);
    void destroy();
    String inspectTask(String packageName, int userId);
    boolean removeTask(String packageName, int userId, int taskId, long deadlineElapsed);
}
