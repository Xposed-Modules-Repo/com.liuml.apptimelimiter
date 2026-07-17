package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback
    ) {
        throw new UnsupportedOperationException("Compile-time stub only");
    }
}
