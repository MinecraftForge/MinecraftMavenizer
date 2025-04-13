/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.util.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.ClassesProvider;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPRecompiler;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPRenamer;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.Task;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

// TODO: [MCMaven] Move this Renamer to MCP, since renaming is not forge-specific.
/**
 * This class is responsible for naming the unnamed sources provided by the {@link Patcher}.
 */
public final class ForgeRecompiler extends MCPRecompiler implements ClassesProvider {
    private final Cache cache;
    private final Patcher patcher;

    private final Task last;

    /**
     * Creates a new renamer for the given patcher.
     *
     * @param name    The developement artifact (usually userdev)
     * @param patcher The patcher to get the unnamed sources from
     */
    ForgeRecompiler(File build, Cache cache, Artifact name, Patcher patcher, MCPRenamer renamer) {
        super(build, name, patcher.getMCP(), patcher::getClasspath, renamer);
        this.cache = cache;
        this.patcher = patcher;

        this.last = this.injectData(super.getClasses());
    }

    /** @return The final named sources */
    @Override
    public Task getClasses() {
        return this.last;
    }

    private Task injectData(Task input) {
        return Task.named("injectData[" + this.name.getName() + ']',
            Set.of(input),
            () -> injectDataImpl(input, new File(this.build, "injectedData.jar"))
        );
    }

    private File injectDataImpl(Task inputTask, File outputJar) {
        var cache = HashStore.fromFile(outputJar);

        var recompiledJar = inputTask.execute();
        cache.add("recompiled", recompiledJar);

        var universals = new ArrayList<File>();
        for (var p : this.patcher.getStack()) {
            cache.add(new File(this.cache.maven().getFolder(), Artifact.from(p.config.universal).getPath()));
        }

        if (outputJar.exists() && cache.isSame())
            return outputJar;

        GlobalOptions.assertNotCacheOnly();
        cache.clear().add("recompiled", recompiledJar);

        for (var p : this.patcher.getStack()) {
            var universal = this.cache.maven().download(Artifact.from(p.config.universal));
            cache.add(universal);
            universals.add(universal);
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
}
