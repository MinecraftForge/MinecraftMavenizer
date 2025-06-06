/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Takes a jar containing compiled class files, and injects extra data/resources from a patcher into it.
 */
public final class InjectTask implements Supplier<Task> {
    private final File build;
    private final Artifact name;
    private final Cache cache;
    private final Patcher patcher;
    private final Mappings mappings;
    private final Task task;

    InjectTask(File build, Cache cache, Artifact name, Patcher patcher, Task input, Mappings mappings) {
        this.build = mappings.getFolder(build);
        this.name = name;
        this.cache = cache;
        this.patcher = patcher;
        this.mappings = mappings;
        this.task = this.injectData(input);
    }

    @Override
    public Task get() {
        return this.task;
    }

    private Task injectData(Task input) {
        return Task.named("injectData[" + this.name.getName() + "][" + mappings + ']',
            Set.of(input),
            () -> injectDataImpl(input, new File(this.build, "injected.jar"))
        );
    }

    private File injectDataImpl(Task inputTask, File outputJar) {
        var cache = HashStore.fromFile(outputJar);

        var recompiledJar = inputTask.execute();
        cache.add("recompiled", recompiledJar);

        var universals = new ArrayList<File>();
        for (var p : this.patcher.getStack()) {
            if (p.config.universal != null) {
                var universal = this.cache.maven().download(Artifact.from(p.config.universal));
                universals.add(universal);
                cache.add(universal);
            }
        }

        if (outputJar.exists() && cache.isSame())
            return outputJar;

        GlobalOptions.assertNotCacheOnly();
        cache.clear().add("recompiled", recompiledJar);

        try {
            var jars = new ArrayList<File>();
            jars.addAll(universals);
            jars.add(recompiledJar);

            FileUtils.mergeJars(outputJar, true, (file, name) -> file == recompiledJar || !name.endsWith(".class"), jars.toArray(File[]::new));
        } catch (IOException e) {
            return Util.sneak(e);
        }

        cache.save();
        return outputJar;
    }
}
