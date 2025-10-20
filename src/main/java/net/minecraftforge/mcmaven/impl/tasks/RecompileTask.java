/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.tasks;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashStore;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Takes a jar containing java source files.
 * A list of libraries
 * And recompiles them to a jar file containing all class files.
 */
public final class RecompileTask implements Task {
    private final File build;
    private final Artifact name;
    private final MCP mcp;
    private final Supplier<List<File>> classpath;
    private final Mappings mappings;
    private final Task task;

    public RecompileTask(File build, Artifact name, MCP mcp, Supplier<List<File>> classpath, Task sources, Mappings mappings) {
        this.build = mappings.getFolder(build);
        this.name = name;
        this.mcp = mcp;
        this.classpath = classpath;
        this.mappings = mappings;
        this.task = this.recompileSources(sources);
    }

    @Override
    public File execute() {
        return this.task.execute();
    }

    @Override
    public boolean resolved() {
        return this.task.resolved();
    }

    @Override
    public String name() {
        return this.task.name();
    }

    protected Task recompileSources(Task input) {
        var output = new File(this.build, "recompiled.jar");
        return Task.named("recompile[" + this.name.getName() + "][" + mappings + ']',
            Task.deps(input),
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

        Mavenizer.assertNotCacheOnly();

        File jdk;
        try {
            jdk = this.mcp.getCache().jdks().get(javaTarget);
        } catch (Exception e) {
            throw new IllegalStateException("JDK not found: " + javaTarget, e);
        }

        ProcessUtils.recompileJar(jdk, this.classpath.get(), sourcesJar, outputJar, this.build);

        cache.save();
        return outputJar;
    }
}
