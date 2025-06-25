/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.util.data.OS;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public interface GradleAttributes {
    enum OperatingSystemFamily implements GradleModule.Attribute {
        WINDOWS, MACOS, LINUX;

        @Override
        public String getName() {
            return "net.minecraftforge.native.operatingSystem";
        }

        @Override
        public String getValue() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable OperatingSystemFamily from(OS os) {
            return switch (os) {
                case WINDOWS -> WINDOWS;
                case MACOS -> MACOS;
                case LINUX, ALPINE, MUSL -> LINUX;
                case null, default -> null;
            };
        }

        public OS toOS() {
            return switch (this) {
                case WINDOWS -> OS.WINDOWS;
                case MACOS -> OS.MACOS;
                case LINUX -> OS.LINUX;
            };
        }

        public static boolean allowsAll(Artifact artifact) {
            for (var os : OperatingSystemFamily.values()) {
                if (!artifact.allowsOS(os.toOS())) return false;
            }

            return true;
        }
    }
}
