package de.robv.android.xposed;

import java.util.Set;

public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean makeWorldReadable() { return false; }
    public void reload() {}
    public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
    public long getLong(String key, long defaultValue) { return defaultValue; }
    public String getString(String key, String defaultValue) { return defaultValue; }
    public Set<String> getStringSet(String key, Set<String> defaultValue) { return defaultValue; }
}
