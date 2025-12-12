/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.util.Locale;

import joptsimple.OptionParser;

enum Tasks {
    MAVEN(MavenTask::run, "Generates a maven repository for Minecraft Artifacts"),
    MCP(MCPTask::run, "Generates a 'clean' sources jar from a MCPConfig pipeline"),
    MCP_DATA(MCPDataTask::run, "Extracts a data file from a MCPConfig archive")
    ;

    interface Callback {
        OptionParser run(String[] args, boolean getParser) throws Exception;
    }
    final String key;
    final Callback callback;
    final String description;

    private Tasks(Callback callback, String description) {
        this.key = name().toLowerCase(Locale.ENGLISH).replace('_', '-');
        this.callback = callback;
        this.description = description;
    }
}
