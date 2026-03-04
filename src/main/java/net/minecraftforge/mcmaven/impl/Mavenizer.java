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

    // Default java argumetns are built build by the Image, and env variables
    // https://github.com/openjdk/jdk/blob/08c8520b39083ec6354dc5df2f18c1f4c3588053/src/hotspot/share/runtime/arguments.cpp#L3628
    private static final String[] DEFAULT_ARG_ENV = { "JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_TOOL_OPIONS"};
    private static final String[] MEMORY_FLAGS = {"-Xmx", "-XX:MaxHeapSize", "-Xms"};
    private static void warnAboutMemory() {
        var found = false;
        for (var env : DEFAULT_ARG_ENV) {
            var value = System.getenv(env);
            if (value == null)
                continue;
            for (var flag : MEMORY_FLAGS) {
                if (value.contains(flag)) {
                    LOGGER.warn("Detected Heap Size Argument(" + flag + ") in " + env + " environment variable.");
                    found = true;
                }
            }
        }
        if (found)
            LOGGER.warn("Please remove it if you run into memory related issues");
    }

    public static List<String> fillDecompileJvmArgs(List<String> args, boolean firstRun) {
        if (!firstRun)
            return args; // Use the unmodifed args from MCPConfig

        var ret = stripMemoryJvmArgs(args);
        // If we have an explicit memory size use it
        if (decompileMemory != null) {
            ret.add("-Xmx" + decompileMemory);
        } else {
            // Don't use any memory arguments and hope java picks the correct values
            // By default it is 1/4th physical memory on modern JVMs
            //     https://docs.oracle.com/en/java/javase/21/gctuning/ergonomics.html
            // There are old JVMs that limit it to 1GB but there is no good way to detect if we're using one so just hope.
            //     https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/ergonomics.html
            // Best we can do is warn about memory argumetns if we see them.
            warnAboutMemory();
        }
        return ret;
    }

    private static List<String> stripMemoryJvmArgs(List<String> args) {
        var ret = new ArrayList<String>(args.size());

        for (var arg : args) {
            var skip = false;
            for (var flag : MEMORY_FLAGS) {
                if (arg.startsWith(flag)) {
                    skip = true;
                    break;
                }
            }
            if (!skip)
                ret.add(arg);
        }
        return ret;
    }
}
