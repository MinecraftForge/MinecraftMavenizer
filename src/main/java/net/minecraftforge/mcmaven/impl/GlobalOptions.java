package net.minecraftforge.mcmaven.impl;

import net.minecraftforge.util.logging.Log;

public final class GlobalOptions {
    private static boolean offline = false;

    public static boolean cacheOnly = false;
    private static boolean cacheMiss = false;

    public static boolean isOffline() {
        return offline || cacheOnly;
    }

    public static void setOffline(boolean isOffline) {
        offline = isOffline;
    }

    public static void assertOnline() {
        if (offline)
            throw new IllegalArgumentException("Offline mode is enabled! Please run without --offline");
    }

    public static void assertNotCacheOnly() {
        if (cacheOnly) {
            throw new IllegalArgumentException("Cache is out of date! Please run without --cache-only");
        } else if (!cacheMiss) {
            cacheMiss = true;
            Log.release();
        }
    }

    private GlobalOptions() { }
}
