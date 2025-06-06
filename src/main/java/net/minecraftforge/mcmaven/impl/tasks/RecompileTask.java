/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.tasks;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashStore;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Takes a jar containing java source files.
 * A list of libraries
 * And recompiles them to a jar file containing all class files.
 */
public final class RecompileTask implements Supplier<Task> {
    private final File build;
    private final Artifact name;
    private final MCP mcp;
    private final Supplier<List<File>> classpath;
    private final Task task;

    public RecompileTask(File build, Artifact name, MCP mcp, Supplier<List<File>> classpath, Task sources) {
        this.build = build;
        this.name = name;
        this.mcp = mcp;
        this.classpath = classpath;
        this.task = this.recompileSources(sources);
    }

    /** @return The final recompiled classes */
    @Override
    public Task get() {
        return this.task;
    }

    protected Task recompileSources(Task input) {
        var output = new File(this.build, "recompiled.jar");
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

        if (outputJar.exists() && cache.isSame())
            return outputJar;

        GlobalOptions.assertNotCacheOnly();

        var jdk = this.mcp.getCache().jdks().get(javaTarget);
        if (jdk == null) {
            throw new IllegalStateException("JDK not found: " + javaTarget);
        }

        ProcessUtils.recompileJar(jdk, this.classpath.get(), sourcesJar, outputJar, this.build);

        cache.save();
        return outputJar;
    }
}
