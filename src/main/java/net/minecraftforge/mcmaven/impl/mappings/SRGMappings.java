/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;

// The raw mappings, typically named SRG for old versions, but unobfed versions its equivelent to official
public class SRGMappings extends Mappings {
    private final Map<Object, ResolvedMappings> resolved = new IdentityHashMap<>();

    public SRGMappings(String channel, @Nullable String version) {
        super(channel, version);
    }

    @Override
    public Mappings withMCVersion(String version) {
        if (this.version() != null && this.version().equals(version))
            return this;
        return new SRGMappings(channel(), version);
    }

    @Override
    public ResolvedMappings withContext(MCPSide side) {
        return this.resolved.computeIfAbsent(side, _ -> withContextImpl(side));
    }

    private ResolvedMappings withContextImpl(MCPSide side) {
        var root = new File(side.getMCP().getBuildFolder(), "data/mapings");
        var csv = csvTask(root);
        var artifact = this.getArtifact(side);
        var mappings = side.getTasks().getMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, null);
    }

    @Override
    public ResolvedMappings withContext(FG2Userdev fg2) {
        return this.resolved.computeIfAbsent(fg2, _ -> withContextImpl(fg2));
    }

    public ResolvedMappings withContextImpl(FG2Userdev fg2) {
        var root = new File(fg2.getBuildFolder(), "data/mapings");
        var csv = csvTask(root);
        var artifact = this.getArtifact(fg2);
        var mappings = fg2.getMCP().getMappings();
        var extra = fg2.getExtraMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, extra);
    }

    @Override
    public ResolvedMappings withContext(MCPLegacy legacy) {
        return this.resolved.computeIfAbsent(legacy, _ -> withContextImpl(legacy));
    }

    public ResolvedMappings withContextImpl(MCPLegacy legacy) {
        var root = new File(legacy.getBuildFolder(), "data/mapings");
        var csv = csvTask(root);
        var artifact = this.getArtifact(legacy);
        var mappings = legacy.getMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, null);
    }

    private Task csvTask(File root) {
        return Task.named("srg2names[" + this + "][Empty]", () -> makeEmptyCsv(root));
    }

    private File makeEmptyCsv(File root) {
        var output = new File(getFolder(root), "srg.zip");

        var cache = Util.cache(output);

        if (Mavenizer.checkCache(output, cache))
            return output;

        if (!output.getParentFile().exists())
            output.getParentFile().mkdirs();

        byte[] header = String.join(",", "searge", "name", "side", "desc").getBytes(StandardCharsets.UTF_8);

        try (var fos = new FileOutputStream(output);
             var out = new ZipOutputStream(fos)) {
            out.putNextEntry(new ZipEntry("fields.csv"));
            out.write(header);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("methods.csv"));
            out.write(header);
            out.closeEntry();
        } catch (IOException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }
}
