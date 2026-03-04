/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Logger;

public final class Mavenizer {
    private Mavenizer() { }

    public static final Logger LOGGER = Logger.create();

    private static boolean offline = false;

    private static boolean cacheOnly = false;
    private static boolean cacheMiss = false;
    private static boolean ignoreCache = false;

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

    public static void setIgnoreCache() {
        ignoreCache = true;
    }

    public static boolean ignoreCache() {
        return ignoreCache;
    }

    public static void assertOnline() {
        if (offline)
            throw new IllegalArgumentException("Offline mode is enabled! Please run without --offline");
    }

    public static void assertNotCacheOnly() {
        if (cacheOnly) {
            throw new IllegalArgumentException("Cache is out of date! Please run without --cache-only");
        } else if (!cacheMiss) {
            LOGGER.debug("Cache miss!", new Exception("Cache miss! Stacktrace for Information Only"));
            cacheMiss = true;
            LOGGER.release();
        }
    }

    public static boolean checkCache(File output, HashStore cache) {
        if (!ignoreCache && output.exists() && cache.isSame())
            return true;
        Mavenizer.assertNotCacheOnly();
        return false;
    }

    private static @Nullable String decompileMemory = null;
    public static void setDecompileMemory(String value) {
        decompileMemory = value;
    }
    public static List<String> fillDecompileJvmArgs(List<String> args) {
        return fillJvmArgs(decompileMemory, args);
    }
    private static List<String> fillJvmArgs(@Nullable String mx, List<String> args) {
        if (mx == null)
            return args;
        var ret = new ArrayList<String>();
        ret.add("-Xmx" + mx);
        for (var arg : args) {
            if (arg.startsWith("-Xmx"))
                continue;
            ret.add(arg);
        }
        return ret;
    }
}
