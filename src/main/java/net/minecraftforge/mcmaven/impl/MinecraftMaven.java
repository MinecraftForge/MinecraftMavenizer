package net.minecraftforge.mcmaven.impl;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.forge.ForgeRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.logging.Log;

import java.io.File;

public record MinecraftMaven(File output, Cache cache) {
    private static final String DISPLAY_NAME = "Minecraft Mavenizer";

    public MinecraftMaven(File output, File cacheRoot, File jdkCacheRoot) {
        this(output, new Cache(cacheRoot, jdkCacheRoot));
    }

    public MinecraftMaven {
        JarVersionInfo.of(DISPLAY_NAME, this).hello(Log::info, true, false);
        Log.info("  Output:    " + output.getAbsolutePath());
        Log.info("  Cache:     " + cache.root().getAbsolutePath());
        Log.info("  JDK Cache: " + cache.jdks().root().getAbsolutePath());
        Log.info();
    }

    public void minecraft(String module, String version) {
        Log.info("Processing Minecraft dependency: %s:%s".formatted(module, version));

        if (Constants.FORGE_ARTIFACT.equals(module)) {
            var repo = new ForgeRepo(this.cache, this.output);
            switch (version) {
                case "all" -> {
                    var versions = this.cache.maven().getVersions(Artifact.from(Constants.FORGE_ARTIFACT));
                    for (var ver : versions.reversed())
                        repo.process(module, ver);
                }
                case null -> throw new IllegalArgumentException("No version specified for Forge");
                default -> repo.process(module, version);
            }
        } else if (module.startsWith("net.minecraft:")) {
            var repo = new MCPConfigRepo(this.cache, this.output);
            if (version == null)
                throw new IllegalArgumentException("No version specified for MCPConfig");

            repo.process(module, version);
        } else {
            throw new IllegalArgumentException("Artifact '%s' is currently Unsupported. Will add later".formatted(module));
        }
    }
}
