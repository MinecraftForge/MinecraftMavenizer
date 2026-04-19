/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.ArtifactFile;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;
import net.minecraftforge.mcmaven.impl.util.Task;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class MCPSide {
    public static final String CLIENT = "client";
    public static final String SERVER = "server";
    public static final String JOINED = "joined";

    private final MCP mcp;
    private final String side;
    private final File build;
    private final MCPTaskFactory factory;

    MCPSide(MCP owner, String side) {
        this.mcp = owner;
        this.side = side;
        this.build = new File(this.mcp.getBuildFolder(), this.side);
        this.factory = new MCPTaskFactory(this, this.build);
    }

    public boolean containsClient() {
        return this.side.equals(JOINED) || this.side.equals(CLIENT);
    }

    public boolean containsServer() {
        return this.side.equals(JOINED) || this.side.equals(SERVER);
    }

    public MCPTaskFactory getTasks() {
        return this.factory;
    }

    public String getName() {
        return this.side;
    }

    public MCP getMCP() {
        return this.mcp;
    }

    public File getBuildFolder() {
        return this.build;
    }

    public void forAllLibraries(Consumer<Artifact> consumer) {
        this.forAllLibraries(consumer, null);
    }

    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        this.forAllLibrariesInternal(consumer, filter, this.getMCLibraries());
        this.forAllLibrariesInternal(consumer, filter, this.getMCPConfigLibraries());
    }

    // to avoid duplicate code
    private void forAllLibrariesInternal(Consumer<? super Artifact> consumer, @Nullable Predicate<? super Artifact> filter, Iterable<? extends Artifact> libraries) {
        for (var library : libraries) {
            if (filter == null || filter.test(library))
                consumer.accept(library);
        }
    }

    private List<ArtifactFile> getVanillaLibraries() {
        var tasks = this.getMCP().getMinecraftTasks();
        if (SERVER.equals(this.side))
            return tasks.getServerLibraries();
        return tasks.getClientLibraries();
    }

    public List<Artifact> getMCLibraries() {
        var list = getVanillaLibraries();
        var ret = new ArrayList<Artifact>(list.size());
        for (var lib : list)
            ret.add(lib.artifact());
        return ret;
    }

    public List<Artifact> getMCPConfigLibraries() {
        var artifacts = new ArrayList<Artifact>();

        for (var lib : this.mcp.getConfig().getLibraries(this.side)) {
            var artifact = StupidHacks.fixLegacyTools(Artifact.from(lib));
            artifacts.add(artifact);
        }

        return artifacts;
    }

    public Task getSources() {
        return this.getTasks().getLastTask();
    }

    public List<File> getClasspath() {
        var classpath = new ArrayList<File>();

        // minecraft version.json(or bundle) libs + mcpconfig libs
        for (var lib : this.getVanillaLibraries()) {
            classpath.add(lib.file());
        }

        for (var lib : this.mcp.getConfig().getLibraries(this.side)) {
            classpath.add(this.mcp.getCache().maven().download(Artifact.from(lib)));
        }

        return classpath;
    }
}