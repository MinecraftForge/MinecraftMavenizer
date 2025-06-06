/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl;

import net.minecraftforge.util.logging.Log;

public final class GlobalOptions {
    private static boolean offline = false;

    private static boolean cacheOnly = false;
    private static boolean cacheMiss = false;

    public static boolean isOffline() {
        return offline || cacheOnly;
    }

    public static void setOffline() {
        offline = true;
    }

    public static boolean isCacheOnly() {
        return cacheOnly;
    }

    public static void setCacheOnly() {
        cacheOnly = true;
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
