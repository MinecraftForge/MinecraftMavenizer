/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.file.FileUtils;

public final class PythonMappings extends Mappings {
    private static final String[] CSV_FILES = {"fields.csv", "methods.csv", "params.csv"};
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^\\d{8}-\\d+\\.\\d+\\.\\d+$");
    private final Map<Object, ResolvedMappings> resolved = new IdentityHashMap<>();

    public PythonMappings(String channel, String version) {
        super(channel, version);
        if (!"snapshot".equals(channel) || version == null || !VERSION_PATTERN.matcher(version).matches())
            throw new IllegalArgumentException("Python-era Forge mappings require a snapshot CSV date in yyyyMMdd-mcversion format");
    }

    @Override
    public Mappings withMCVersion(String version) {
        if (new ComparableVersion(version).compareTo(new ComparableVersion("1.6")) >= 0)
            throw new IllegalArgumentException("Bundled Python-era mappings are only valid before Minecraft 1.6");
        return this;
    }

    @Override
    public ResolvedMappings withContext(MCPSide side) {
        throw new UnsupportedOperationException("Python-era mappings require a Python MCP archive");
    }

    @Override
    public ResolvedMappings withContext(FG2Userdev fg2) {
        throw new UnsupportedOperationException("Python-era mappings require a Python MCP archive");
    }

    @Override
    public ResolvedMappings withContext(MCPLegacy legacy) {
        if (new ComparableVersion(legacy.getMinecraftTasks().getVersion()).compareTo(new ComparableVersion("1.6")) >= 0)
            throw new IllegalArgumentException("Bundled Python-era mappings are only valid before Minecraft 1.6");
        return this.resolved.computeIfAbsent(legacy, _ -> withContextImpl(legacy));
    }

    private ResolvedMappings withContextImpl(MCPLegacy legacy) {
        var root = new File(legacy.getBuildFolder(), "data/mappings");
        var csv = Task.named("python-mappings[" + this + ']', () -> createCsvZip(legacy.getData(), root, version()));
        var artifact = getArtifact(legacy);
        return new ResolvedMappings(channel(), version(), artifact, root, legacy.getMappings(), csv, null);
    }

    private static File createCsvZip(File mcpData, File root, String version) {
        var output = new File(root, "snapshot-" + version + ".zip");
        var cache = Util.cache(output)
            .add("mcp", mcpData)
            .addKnown("format", "python-mcp-csv-v1");
        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var source = new ZipFile(mcpData);
             var out = new ZipOutputStream(new FileOutputStream(output))) {
            for (var csv : CSV_FILES) {
                var name = "conf/" + csv;
                var entry = source.getEntry(name);
                if (entry == null || entry.isDirectory())
                    throw new IllegalStateException("Python MCP archive is missing " + name);
                out.putNextEntry(FileUtils.getStableEntry(csv));
                try (var input = source.getInputStream(entry)) {
                    input.transferTo(out);
                }
                out.closeEntry();
            }
            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to package bundled Python MCP mappings from " + mcpData.getAbsolutePath(), e);
        }
    }
}
