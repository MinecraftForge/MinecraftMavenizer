/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.util.Locale;

enum Tasks {
    MAVEN(MavenTask::run, "Generates a maven repository for Minecraft Artifacts"),
    MCP(MCPTask::run, "Generates a 'clean' sources jar from a MCPConfig pipeline")
    ;

    interface Callback {
        void run(String[] args) throws Exception;
    }
    final String key;
    final Callback callback;
    final String description;

    private Tasks(Callback callback, String description) {
        this.key = name().toLowerCase(Locale.ENGLISH);
        this.callback = callback;
        this.description = description;
    }

}
