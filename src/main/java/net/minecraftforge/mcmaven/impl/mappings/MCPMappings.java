/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;

public class MCPMappings extends Mappings {
    private final Map<Object, ResolvedMappings> resolved = new IdentityHashMap<>();

    public MCPMappings(String channel, @Nullable String version) {
        super(channel, version);
        if (version == null)
            throw new IllegalArgumentException("MCP Mappings can not have a null version");
    }

    @Override
    public Mappings withMCVersion(String version) {
        return this;
    }

    @Override
    public boolean isPrimary() {
        return false;
    }

    @Override
    public ResolvedMappings withContext(MCPSide side) {
        return this.resolved.computeIfAbsent(side, _ -> withContextImpl(side));
    }

    private ResolvedMappings withContextImpl(MCPSide side) {
        var csv = downloadCsv(side.getMCP().getCache().maven());
        var root = new File(side.getMCP().getBuildFolder(), "data/mapings");
        var artifact = this.getArtifact(side);
        var mappings = side.getTasks().getMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, null);
    }

    @Override
    public ResolvedMappings withContext(FG2Userdev fg2) {
        return this.resolved.computeIfAbsent(fg2, _ -> withContextImpl(fg2));
    }

    private ResolvedMappings withContextImpl(FG2Userdev fg2) {
        var csv = downloadCsv(fg2.getCache().maven());
        var root = new File(fg2.getBuildFolder(), "data/mapings");
        var artifact = this.getArtifact(fg2);
        var mappings = fg2.getMCP().getMappings();
        var extra = fg2.getExtraMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, extra);
    }

    @Override
    public ResolvedMappings withContext(MCPLegacy legacy) {
        return this.resolved.computeIfAbsent(legacy, _ -> withContextImpl(legacy));
    }

    private ResolvedMappings withContextImpl(MCPLegacy legacy) {
        var csv = downloadCsv(legacy.getCache().maven());
        var root = new File(legacy.getBuildFolder(), "data/mapings");
        var artifact = this.getArtifact(legacy);
        var mappings = legacy.getMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, null);
    }

    private Task downloadCsv(MavenCache maven) {
        // This is simple because it's the old MCP Bot based zip files
        // So it's just a matter of downloading from Forge's maven.
        var artifact = Artifact.from("de.oceanlabs.mcp", "mcp_" + this.channel(), this.version(), null, "zip");
        return Task.named("srg2names[" + this + ']', () -> downloadCsv(maven, artifact));
    }

    private File downloadCsv(MavenCache maven, Artifact artifact) {
        try {
            return maven.download(artifact);
        } catch (Exception e) {
            // They didnt have a suffix and we couldn't find the mapping
            if (this.version().indexOf('-') == -1 && e instanceof FileNotFoundException) {
                List<String> versions = null;
                try {
                    versions = maven.getVersions(artifact);
                } catch (Exception _) {
                    // Whelp we couldn't find the version list to provide a useful hint, so just fall back to the normal error
                    return Util.sneak(e);
                }

                var candidates = new ArrayList<String>();
                var prefix = this.version() + '-';
                for (var version : versions) {
                    if (version.startsWith(prefix))
                        candidates.add(version);
                }
                var buf = new StringBuilder();
                buf.append("Could not find mapping file for ").append(this.toString());
                if (candidates.size() == 1)
                    buf.append(" Did you mean ").append(candidates.getFirst()).append('?');
                else if (!candidates.isEmpty()) {
                    buf.append(" Did you mean one of these:");
                    for (var candidate : candidates)
                        buf.append("\n\t").append(candidate);
                }
                buf.append("\n\tYou can try looking at ")
                    .append(Constants.FORGE_MAVEN).append(artifact.withVersion(null).getFolder()).append("/maven-metadata.xml")
                    .append(" for full list of possible versions");

                throw new IllegalArgumentException(buf.toString(), e);
            }
            return Util.sneak(e);
        }
    }
}
