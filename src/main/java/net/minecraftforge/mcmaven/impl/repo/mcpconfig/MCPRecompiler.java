/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.ClassesProvider;
import net.minecraftforge.mcmaven.impl.repo.SourcesProvider;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashStore;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// TODO: [MCMaven] Move this Renamer to MCP, since renaming is not forge-specific.
public class MCPRecompiler implements ClassesProvider {
    protected final File build;
    protected final Artifact name;
    private final MCP mcp;
    private final Supplier<List<File>> classpath;

    private final Task last;

    MCPRecompiler(File build, Artifact name, MCPSide mcpSide, SourcesProvider sources) {
        this(build, name, mcpSide.getMCP(), mcpSide::getClasspath, sources);
    }

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    protected MCPRecompiler(File build, Artifact name, MCP mcp, Supplier<List<File>> classpath, SourcesProvider sources) {
        this.build = build;
        this.name = name;
        this.mcp = mcp;
        this.classpath = classpath;

        this.last = this.recompileSources(sources.getSources());
    }

    /** @return The final named sources */
    @Override
    public Task getClasses() {
        return this.last;
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
