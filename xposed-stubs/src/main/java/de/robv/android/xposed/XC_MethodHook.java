package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
    }

    public class Unhook {
        public void unhook() {}
    }
}
