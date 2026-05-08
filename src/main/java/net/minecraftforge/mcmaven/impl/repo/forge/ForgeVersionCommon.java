/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Artifact;

public interface ForgeVersionCommon {
    String getDataHash();

    int getJavaTarget();
    List<File> getClasspath();

    Artifact getMCPArtifact();
    String getMinecraftVersion();
    @Nullable List<String> getModules();

    default void forAllLibraries(Consumer<Artifact> consumer) {
        this.forAllLibraries(consumer, null);
    }
    void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter);
    List<Artifact> getLibraries();
    List<String> getCompileOnly();
    List<String> getRuntimeOnly();

    MinecraftTasks getMinecraftTasks();
}