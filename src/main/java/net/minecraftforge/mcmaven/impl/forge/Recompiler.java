/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.forge;

import net.minecraftforge.mcmaven.impl.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.Log;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// TODO: [MCMaven] Move this Renamer to MCP, since renaming is not forge-specific.
/**
 * This class is responsible for naming the unnamed sources provided by the {@link Patcher}.
 */
class Recompiler {
    private final ForgeRepo forge;
    private final Artifact name;
    private final File data;
    private final Patcher patcher;
    private final MCP mcp;

    private final Map<String, Task> extracts = new HashMap<>();
    private final Task last;

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    /**
     * Creates a new renamer for the given patcher.
     *
     * @param forge   The Forge repo
     * @param name    The developement artifact (usually userdev)
     * @param patcher The patcher to get the unnamed sources from
     */
    Recompiler(ForgeRepo forge, Artifact name, Patcher patcher, Renamer renamer) {
        this.forge = forge;
        this.name = name;
        this.patcher = patcher;
        this.mcp = renamer.mcp;

        this.data = this.forge.cache.forge.download(name);
        if (!this.data.exists())
            throw new IllegalStateException("Failed to download " + name);

        var recompile = this.recompileSources(renamer.getNamedSources(), this.forge.build);
        this.last = this.injectData(recompile, this.forge.build);
    }

    private static void log(String message) {
        Log.log(message);
    }

    private static void debug(String message) {
        Log.debug(message);
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

    private Task injectData(Task input, File outputDir) {
        var output = new File(outputDir, "injectedData.jar");
        return Task.named("injectData[" + this.name.getName() + ']',
            Set.of(input),
            () -> injectDataImpl(input, output)
        );
    }

    private File injectDataImpl(Task inputTask, File outputJar) {
        var cache = HashStore.fromFile(outputJar);

        var recompiledJar = inputTask.execute();

        var universals = new ArrayList<File>();
        for (var p : this.patcher.getStack()) {
            universals.add(this.forge.cache.forge.download(Artifact.from(p.config.universal)));
        }

        cache.add("recompiled", recompiledJar);

        if (outputJar.exists() && cache.isSame()) {
            return outputJar;
        }

        try {
            var jars = new ArrayList<File>();
            jars.addAll(universals);
            jars.add(recompiledJar);

            FileUtils.mergeJars(outputJar, true, (file, name) -> file == recompiledJar || !name.endsWith(".class"), jars.toArray(File[]::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        cache.save();
        return outputJar;
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
        var javaTarget = this.patcher.getMCP().getConfig().java_target;
        var sourcesJar = inputTask.execute();

        cache.add("sources", sourcesJar);

        if (outputJar.exists() && cache.isSame()) {
            return outputJar;
        }

        var jdk = this.forge.cache.jdks.get(javaTarget);
        if (jdk == null) {
            throw new IllegalStateException("JDK not found: " + javaTarget);
        }

        ProcessUtils.recompileJar(jdk, this.patcher.getClasspath(), sourcesJar, outputJar, this.forge.build);

        cache.save();
        return outputJar;
    }
}
