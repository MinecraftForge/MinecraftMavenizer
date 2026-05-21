/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;

class OfficialMappings extends Mappings {
    private final Map<Object, ResolvedMappings> resolved = new IdentityHashMap<>();

    public OfficialMappings(String channel, @Nullable String version) {
        super(channel, version);
    }

    @Override
    public boolean isPrimary() {
        return true;
    }

    @Override
    public Mappings withMCVersion(String version) {
        if (Objects.equals(version(), version))
            return this;
        return new OfficialMappings(channel(), version);
    }

    @Override
    public ResolvedMappings withContext(MCPSide side) {
        return this.resolved.computeIfAbsent(side, _ -> withContextImpl(side));
    }

    private ResolvedMappings withContextImpl(MCPSide side) {
        var csv = makeCsv(side);
        var root = new File(side.getMCP().getBuildFolder(), "data/mapings");
        var artifact = this.getArtifact(side);
        var mappings = side.getTasks().getMappings();
        return new ResolvedMappings(channel(), version(), artifact, root, mappings, csv, null);
    }

    @Override
    public ResolvedMappings withContext(FG2Userdev fg2) {
        throw new IllegalStateException("Official mappings does not support Legacy: " + fg2.getName());
    }

    @Override
    public ResolvedMappings withContext(MCPLegacy legacy) {
        throw new IllegalStateException("Official mappings does not support Legacy: " + legacy.getName());
    }

    private Task makeCsv(MCPSide side) {
        var mc = side.getMCP().getMinecraftTasks();
        var srg = side.getTasks().getMappings();

        // Create an empty srg->mapped zip file when requested. This is needed by old MCPConfig setups until we bump it to a version that doesn't require the concept of mappings at all
        if (!MCPConfigRepo.isObfuscated(mc.getVersion()))
            return Task.named("srg2names[" + this + "][Empty]", () -> makeEmptyCsv(side));

        var client = mc.versionFile(MinecraftTasks.MCFile.CLIENT_MAPPINGS);
        var server = mc.versionFile(MinecraftTasks.MCFile.SERVER_MAPPINGS);
        return Task.named("srg2names[" + this + ']',
            Task.deps(srg, client, server),
            () -> getMappings(side, srg, client, server)
        );
    }

    private File makeEmptyCsv(MCPSide side) {
        var root = getFolder(new File(side.getMCP().getBuildFolder(), "data/mapings"));
        var output = new File(root, "official.zip");

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

    private File getMappings(MCPSide side, Task srgMappings, Task clientTask, Task serverTask) {
        var tool = side.getMCP().getCache().maven().download(Constants.INSTALLER_TOOLS);

        var root = getFolder(new File(side.getMCP().getBuildFolder(), "data/mapings"));
        var output = new File(root, "official.zip");
        var log = new File(root, "official.log");

        var mappings = srgMappings.execute();
        var client = clientTask.execute();
        var server = serverTask.execute();

        var cache = Util.cache(output);
        cache.add("tool", tool);
        cache.add("mappings", mappings);
        cache.add("client", client);
        cache.add("server", server);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var args = List.of(
            "--task",
            "MAPPINGS_CSV",
            "--srg",
            mappings.getAbsolutePath(),
            "--client",
            client.getAbsolutePath(),
            "--server",
            server.getAbsolutePath(),
            "--output",
            output.getAbsolutePath()
        );

        File jdk;
        try {
            jdk = side.getMCP().getCache().jdks().get(Constants.INSTALLER_TOOLS_JAVA_VERSION);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + Constants.INSTALLER_TOOLS_JAVA_VERSION, e);
        }

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }
}
