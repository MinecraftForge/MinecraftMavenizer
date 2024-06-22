/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public enum OS {
    AIX    ("aix",          "aix"),
    ALPINE ("apline_linux", "alpine"),
    LINUX  ("linux",        "linux", "unix"),
    MUSL   ("linux_musl",   "musl"),
    OSX    ("macos",        "mac", "osx", "darwin"),
    QNX    ("qnx",          "qnx"),
    SOLARIS("solaris",      "sunos"),
    WINDOWS("windows",      "win"),
    UNKNOWN("unknown");

    private static final OS[] $values = values();
    public static final OS CURRENT = getCurrent();

    private final String key;
    private final String[] names;

    private OS(String key, String... names) {
        this.key = key;
        this.names = names;
    }

    public String key() {
        return this.key;
    }

    public static OS byKey(String key) {
        for (OS value : $values) {
            if (value.key.equals(key))
                return value;
        }
        return null;
    }

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
                            Log.log("Failed to read /etc/os-release: " + e.getMessage());
                        }
                    }
                    return os;
                }
            }
        }
        return UNKNOWN;
    }
}