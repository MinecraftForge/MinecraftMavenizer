/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.util.os.OS;
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

        public static @Nullable OperatingSystemFamily from(@Nullable OS os) {
            return switch (os) {
                case WINDOWS -> WINDOWS;
                case MACOS -> MACOS;
                case null -> null;
                case UNKNOWN -> null;
                default -> LINUX;
            };
        }
    }
}
