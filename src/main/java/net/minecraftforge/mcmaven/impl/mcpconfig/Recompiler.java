/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mcpconfig;

import net.minecraftforge.mcmaven.impl.HasNamedSources;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashStore;

import java.io.File;
import java.util.Set;

// TODO: [MCMaven] Move this Renamer to MCP, since renaming is not forge-specific.
class Recompiler {
    private final File build;
    private final MCP mcp;
    private final MCPSide side;
    private final Artifact name;

    private final Task last;

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    Recompiler(File build, Artifact name, MCPSide side, HasNamedSources renamer) {
        this.build = build;
        this.mcp = side.getMCP();
        this.side = side;
        this.name = name;

        this.last = this.recompileSources(renamer.getNamedSources(), build);
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message);
    }

    private RuntimeException except(String message, Throwable e) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message, e);
    }

    /** @return The final named sources */
    public Task getRecompiledJar() {
        return this.last;
    }

    private Task recompileSources(Task input, File outputDir) {
        var output = new File(outputDir, "recompiled.jar");
        return Task.named("recompile[" + this.name.getName() + ']',
            Set.of(input),
            () -> recompileSourcesImpl(input, output)
        );
    }

    private File recompileSourcesImpl(Task inputTask, File outputJar) {
        var cache = HashStore.fromFile(outputJar);
        var javaTarget = this.mcp.getConfig().java_target;
        var sourcesJar = inputTask.execute();

        cache.add("sources", sourcesJar);

        if (outputJar.exists() && cache.isSame()) {
            return outputJar;
        }

        var jdk = this.mcp.getCache().jdks.get(javaTarget);
        if (jdk == null) {
            throw new IllegalStateException("JDK not found: " + javaTarget);
        }

        ProcessUtils.recompileJar(jdk, this.side.getClasspath(), sourcesJar, outputJar, this.build);

        cache.save();
        return outputJar;
    }
}
