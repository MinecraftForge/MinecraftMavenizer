/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashStore;
import org.jetbrains.annotations.Nullable;

public class Mappings {
    public static final String CHANNEL_ATTR = "net.minecraftforge.mappings.channel";
    public static final String VERSION_ATTR = "net.minecraftforge.mappings.version";

    protected final Map<MCPSide, Task> tasks = new HashMap<>();
    private final String channel;
    private final @Nullable String version;

    public static Mappings of(String mappingsNotation) {
        var split = mappingsNotation.split(":", 2);
        var channel = split[0];
        var version = split.length > 1 ? split[1] : null;

        return "parchment".equalsIgnoreCase(channel)
            ? new ParchmentMappings(version)
            : new Mappings(channel, version);
    }

    public Mappings(String channel, @Nullable String version) {
        this.channel = channel;
        this.version = version;
    }

    public String channel() {
        return this.channel;
    }

    public @Nullable String version() {
        return this.version;
    }

    @Override
    public String toString() {
        return channel() + (version() == null ? "" : '-' + version());
    }

    public File getFolder(File root) {
        return new File(root, channel() + '/' + version());
    }

    public boolean isPrimary() {
        // This is the 'primary' mapping and thus what we publish as the root artifacts.
        // Not as gradle module metadata only variants.
        // Basically the thing that looks like a normal maven artifact
        return true;
    }

    public Mappings withMCVersion(String version) {
        return new Mappings(channel(), version);
    }

    public Artifact getArtifact(MCPSide side) {
        //net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip
        var mcpVersion = side.getMCP().getName().getVersion();
        var mcVersion = side.getMCP().getConfig().version;
        var artifactVersion = mcpVersion;
        if (this.version != null && !mcVersion.equals(this.version))
            artifactVersion = mcpVersion + '-' + this.version;

        return Artifact.from(Constants.MC_GROUP, "mappings_" + this.channel, artifactVersion)
            .withExtension("zip");
    }

    public Task getCsvZip(MCPSide side) {
        var ret = tasks.get(side);
        if (ret != null)
            return ret;

        var mc = side.getMCP().getMinecraftTasks();
        var srg = side.getTasks().getMappings();
        var client = mc.versionFile("client_mappings", "txt");
        var server = mc.versionFile("server_mappings", "txt");
        ret = Task.named("srg2names[" + this + ']',
            Task.deps(srg, client, server),
            () -> getMappings(side, srg, client, server)
        );
        tasks.put(side, ret);

        return ret;
    }

    private File getMappings(MCPSide side, Task srgMappings, Task clientTask, Task serverTask) {
        var tool = side.getMCP().getCache().maven().download(Constants.INSTALLER_TOOLS);

        var root = getFolder(new File(side.getMCP().getBuildFolder(), "data/mapings"));
        var output = new File(root, "official.zip");
        var log = new File(root, "official.log");

        var mappings = srgMappings.execute();
        var client = clientTask.execute();
        var server = serverTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("mappings", mappings);
        cache.add("client", client);
        cache.add("server", server);

        if (output.exists() && cache.isSame())
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

        var jdk = side.getMCP().getCache().jdks().get(Constants.INSTALLER_TOOLS_JAVA_VERSION);
        if (jdk == null)
            throw new IllegalStateException("Failed to find JDK for version " + Constants.INSTALLER_TOOLS_JAVA_VERSION);

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }
}
