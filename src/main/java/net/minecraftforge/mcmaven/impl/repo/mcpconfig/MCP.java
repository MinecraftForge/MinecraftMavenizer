/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import net.minecraftforge.mcmaven.impl.util.GlobalOptions;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MCPConfig;
import net.minecraftforge.util.hash.HashStore;

// TODO [MCMaven][Documentation] Document
/**
 * The MCP build process.
 */
public class MCP {
    private final MCPConfigRepo repo;
    private final Artifact name;
    private final File data;
    private final MCPConfig.V2 config;
    private final File build;
    private final MinecraftTasks mcTasks;

    private final Map<String, MCPSide> sides = new HashMap<>();

    public MCP(MCPConfigRepo repo, Artifact name) {
        this.repo = repo;
        this.name = name;

        this.data = this.getCache().maven().download(name);
        if (!this.data.exists())
            throw new IllegalStateException("Failed to download " + name);

        this.config = loadConfig(name, this.data);

        validateConfig();

        this.build = new File(this.repo.getCache().root(), "mcp/" + this.name.getFolder());
        this.mcTasks = this.repo.getMCTasks(this.config.version);

        for (var side : this.config.steps.keySet())
            sides.put(side, new MCPSide(this, side));
    }

    private static MCPConfig.V2 loadConfig(Artifact name, File data) {
        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry("config.json");
            if (entry == null)
                throw new IllegalStateException("Invalid MCPConfig dependency: " + name + " - Missing config.json");
            var cfg_data = zip.getInputStream(entry).readAllBytes();

            int spec = JsonData.configSpec(cfg_data);

            if (spec == 2 || spec == 3 || spec == 4)
                return JsonData.mcpConfigV2(zip.getInputStream(entry));
            else if (spec == 1)
                return new MCPConfig.V2(JsonData.mcpConfig(zip.getInputStream(entry)));
            else
                throw new IllegalStateException("Invalid MCP Config: " + name + " - Unknown Spec: " + spec);
        } catch (IOException e) {
            throw new RuntimeException("Invalid MCPConfig dependency: " + name, e);
        }
    }

    private void validateConfig() {
        if (config.steps == null || config.steps.isEmpty())
            throw new IllegalStateException("Invalid MCP Config: " + name + " - Missing steps");

        for (var side : config.steps.keySet()) {
            var steps = config.steps.get(side);
            for (int x = 0; x < steps.size(); x++) {
                var type = steps.get(x).get("type");
                if (type == null)
                    throw new IllegalStateException("Invalid MCP Config: " + name + " - Step " + side + "[" + x + "] Missing `type`");

                switch (type) {
                    case "downloadClientMappings":
                    case "downloadServerMappings":
                        // Added in spec 2
                        if (config.spec < 2)
                            throw new IllegalStateException("Invalid MCP Config: " + name + " - Step " + side + "[" + x + "] `" + type + "` is only supported on spec 2 or higher, found spec: " + config.spec);
                        break;
                    case "listLibraries":
                        // Spec v3 adds 'bundle' to the listLibraries task.
                        if (config.spec < 3 && steps.get(x).containsKey("bundle"))
                            throw new IllegalStateException("Invalid MCP Config: " + name + " - Step " + side + "[" + x + "] `listLibraries.bundle` is only supported on spec 3 or higher, found spec: " + config.spec);
                        break;
                }
            }
        }

        // java_version
        if (config.spec < 4 && config.functions != null) {
            for (var func : config.functions.values()) {
                if (func.java_version != null)
                    throw new IllegalStateException("Invalid MCP Config: " + name + " - Function `java_version` property is only supported on spec 4 or higher, found spec: " + config.spec);
            }
        }
    }

    public MCPConfig.V2 getConfig() {
        return this.config;
    }

    public File getData() {
        return this.data;
    }

    public MCPSide getSide(String side) {
        var ret = this.sides.get(side);
        if (ret == null)
            throw new IllegalArgumentException("Invalid MCP Side: " + side);
        return ret;
    }

    public Artifact getName() {
        return this.name;
    }

    public MinecraftTasks getMinecraftTasks() {
        return this.mcTasks;
    }

    public Cache getCache() {
        return this.repo.getCache();
    }

    public File getBuildFolder() {
        return this.build;
    }

    // TODO [MCMaven] Support Parchment
    public Task getMappings(String mappings) {
        var side = this.getSide(MCPSide.JOINED);
        var client = this.mcTasks.versionFile("client_mappings", "txt");
        var server = this.mcTasks.versionFile("server_mappings", "txt");
        return Task.named("srg2names[" + "official" + ']',
            Set.of(client, server),
            () -> getMappings(side.getTasks().getMappings(), client, server)
        );
    }

    private File getMappings(Task srgMappings, Task clientTask, Task serverTask) {
        var tool = this.getCache().maven().download(Constants.INSTALLER_TOOLS);

        var output = new File(this.build, "data/mappings/official.zip");
        var log = new File(this.build, "data/mappings/official.txt");

        var mappings = srgMappings.execute();
        var client = clientTask.execute();
        var server = serverTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("mappings", mappings);
        cache.add("client", client);
        cache.add("server", server);

        if (output.exists() && cache.exists())
            return output;

        GlobalOptions.assertNotCacheOnly();

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

        var jdk = this.getCache().jdks().get(Constants.INSTALLER_TOOLS_JAVA_VERSION);
        if (jdk == null)
            throw new IllegalStateException("Failed to find JDK for version " + Constants.INSTALLER_TOOLS_JAVA_VERSION);

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }
}
