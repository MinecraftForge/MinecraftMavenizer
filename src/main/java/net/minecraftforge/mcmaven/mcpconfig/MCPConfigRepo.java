package net.minecraftforge.mcmaven.mcpconfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.mcmaven.cache.Cache;
import net.minecraftforge.mcmaven.util.Artifact;

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
