/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mcpconfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.util.Artifact;

/*
 * Provides the following artifacts:
 *
 * net.minecraft:
 *   client:
 *     MCPVersion:
 *       srg - Srg named SLIM jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   server:
 *     MCPVersion:
 *       srg - Srg named SLIM jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   joined:
 *     MCPVersion:
 *       .pom - Pom meta linking against net.minecraft:client:extra and net.minecraft:client:data
 *       '' - Notch named merged jar file
 *       srg - Srg named jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   mappings_{channel}:
 *     MCPVersion|MCVersion:
 *       .zip - A zip file containing SRG -> Human readable field and method mappings.
 *         Current supported channels:
 *         'stable', 'snapshot': MCP's crowdsourced mappings.
 *         'official': Official mappings released by Mojang.
 *
 *   Note: It does NOT provide the Obfed named jars for server and client, as that is provided by MinecraftRepo.
 */
// client extra
// TODO [MCMaven][Documentation] Document
public class MCPConfigRepo {
    private final Map<Artifact, MCP> versions = new HashMap<>();
    private final Map<String, MinecraftTasks> mcTasks = new HashMap<>();
    final Cache cache;
    final File output;

    public MCPConfigRepo(Cache cache, File output) {
        this.cache = cache;
        this.output = output;
    }

    public MCP get(Artifact artifact) {
        return this.versions.computeIfAbsent(artifact, this::download);
    }

    private MCP download(Artifact artifact) {
        return new MCP(this, artifact);
    }

    public Cache getCache() {
        return this.cache;
    }

    public MinecraftTasks getMCTasks(String version) {
        return this.mcTasks.computeIfAbsent(version, k -> new MinecraftTasks(this.cache.root, version));
    }
}
