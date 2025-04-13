package net.minecraftforge.mcmaven.impl.util;

public final class GlobalOptions {
    public static boolean cacheOnly = false;

    public static void assertNotCacheOnly() {
        if (cacheOnly)
            throw new IllegalArgumentException("Cache is out of date! Please run without --cache-only");
    }

    private GlobalOptions() { }
}
