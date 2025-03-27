/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

import static net.minecraftforge.mcmaven.impl.util.Constants.LOGGER;

// TODO Move to Java Version?
// TODO Always warn on unknown OS
/**
 * Enum representing the operating system.
 */
public enum OS {
    AIX    ("aix",          "aix"),
    /** @apiNote When working with Minecraft natives, this will be treated as {@link #LINUX}. */
    ALPINE ("apline_linux", "alpine"),
    LINUX  ("linux",        "linux", "unix"),
    /** @apiNote When working with Minecraft natives, this will be treated as {@link #LINUX}. */
    MUSL   ("linux_musl",   "musl"),
    /** @apiNote When working with Minecraft natives, this is primarily referred to as {@code osx}. */
    MACOS  ("macos",        "mac", "osx", "darwin"),
    QNX    ("qnx",          "qnx"),
    SOLARIS("solaris",      "sunos"),
    WINDOWS("windows",      "win"),
    UNKNOWN("unknown");

    private static final OS[] $values = values();
    /** The operating system that this tool is being run on. */
    public static final OS CURRENT = getCurrent();

    private final String key;
    private final String[] names;

    private OS(String key, String... names) {
        this.key = key;
        this.names = names;
    }

    /**
     * The primary name, and enum key, of the operating system.
     *
     * @return The primary name
     */
    public String key() {
        return this.key;
    }

    /**
     * Returns the OS enum for the given key.
     *
     * @param key The key to search for
     * @return The OS enum, or null if not found
     */
    public static @Nullable OS byKey(String key) {
        for (OS value : $values) {
            if (value.key.equals(key))
                return value;
        }
        return null;
    }

    /**
     * Returns the mandatory file extension for executables on this OS.
     *
     * @return The file extension
     */
    public String exe() {
        return this == OS.WINDOWS ? ".exe" : "";
    }

    private static OS getCurrent() {
        String prop = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        for (OS os : $values) {
            for (String key : os.names) {
                if (prop.contains(key)) {
                    if (os == LINUX) {
                        try {
                            for (String line : Files.readAllLines(Paths.get("/etc/os-release"), StandardCharsets.UTF_8)) {
                                line = line.toLowerCase(Locale.ENGLISH);
                                if (line.startsWith("name=") && line.contains("alpine")) {
                                    return ALPINE;
                                }
                            }
                        } catch (IOException e) {
                            LOGGER.error("Failed to read /etc/os-release: " + e.getMessage());
                        }
                    }
                    return os;
                }
            }
        }
        return UNKNOWN;
    }
}