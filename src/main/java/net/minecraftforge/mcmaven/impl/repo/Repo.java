/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.GradleAttributes;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

public abstract class Repo {
    protected final Cache cache;

    protected Repo(Cache cache) {
        this.cache = cache;
    }

    public final Cache getCache() {
        return cache;
    }

    public abstract List<PendingArtifact> process(String module, String version, Mappings mappings);

    protected static PendingArtifact pending(String message, Task task, Artifact artifact) {
        return pending(message, task, artifact, (Task)null);
    }

    protected static PendingArtifact pending(String message, Task task, Artifact artifact, Supplier<GradleModule.Variant[]> variants) {
        return pending(message, task, artifact, variantTask(task, variants));
    }

    protected static PendingArtifact pending(String message, Task task, Artifact artifact, @Nullable Task variants) {
        return new PendingArtifact(message, task, artifact, variants);
    }

    // Sources has no dependencies, so just need to specify the attributes
    protected Supplier<GradleModule.Variant[]> sourceVariant(Mappings mappings) {
        return () -> new GradleModule.Variant[] {
            GradleModule.Variant.of("sources")
                .attribute("org.gradle.usage", "java-runtime")
                .attribute("org.gradle.category", "documentation")
                .attribute("org.gradle.dependency.bundling", "external")
                .attribute("org.gradle.docstype", "sources")
                .attribute("org.gradle.libraryelements", "jar")
                .attribute(Mappings.CHANNEL_ATTR, mappings.channel())
                .attribute(Mappings.VERSION_ATTR, mappings.version())
        };
    }

    protected static Task variantTask(Task parent, Supplier<GradleModule.Variant[]> supplier) {
        return Task.named(parent.name() + "[variants]", List.of(parent), () -> {
            var variants = supplier.get();

            var variantFile = new File(parent.get().getAbsolutePath() + ".variants");
            try {
                FileUtils.ensureParent(variantFile);
                JsonData.toJson(variants, variantFile);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to write artifact variants: %s".formatted(variantFile), t);
            }
            return variantFile;
        });
    }

    // Classes needs a variant for each OS type so that we can have different natives
    protected GradleModule.Variant[] classVariants(Mappings mappings, MCPSide side, Artifact... extraDeps) {
        var all = new ArrayList<Artifact>();
        var natives = new HashMap<GradleAttributes.OperatingSystemFamily, List<Artifact>>();

        for (var artifact : side.getMCLibraries()) {
            var variant = GradleAttributes.OperatingSystemFamily.from(artifact.getOs());
            if (variant == null)
                all.add(artifact);
            else
                natives.computeIfAbsent(variant, k -> new ArrayList<>()).add(artifact);
        }

        for (var artifact : side.getMCPConfigLibraries())
            all.add(artifact);

        for (var extra : extraDeps) {
            if (extra != null)
                all.add(extra);
        }

        var java = Util.replace(
            JsonData.minecraftVersion(side.getMCP().getMinecraftTasks().versionJson.get()),
            v -> v.javaVersion != null ? v.javaVersion.majorVersion : null
        );

        Consumer<GradleModule.Variant> common = v -> {
            v.attribute("org.gradle.usage", "java-runtime")
            .attribute("org.gradle.category", "library")
            .attribute("org.gradle.dependency.bundling", "external")
            .attribute("org.gradle.libraryelements", "jar")
            .attribute("org.gradle.jvm.environment", "standard-jvm")
            .attribute(Mappings.CHANNEL_ATTR, mappings.channel())
            .attribute(Mappings.VERSION_ATTR, mappings.version())
            ;

            if (java != null)
                v.attribute("org.gradle.jvm.version", java);

            v.deps(all);
        };


        var variants = new ArrayList<GradleModule.Variant>();
        variants.add(GradleModule.Variant.of("classes", common));
        for (var e : natives.entrySet()) {
            var variant = GradleModule.Variant.of("classes-" + e.getKey().getValue(), common);
            variant.deps(e.getValue());
            variants.add(variant);
        }

        return variants.toArray(new GradleModule.Variant[0]);
    }

    public static final class PendingArtifact implements Supplier<File> {
        private final String message;
        private final Task task;
        private final Artifact artifact;
        private final @Nullable Task variants;

        private PendingArtifact(String message, Task task, Artifact artifact, @Nullable Task variants) {
            this.message = message;
            this.task = task;
            this.artifact = artifact;
            this.variants = variants;
        }

        @Override
        public File get() {
            if (this.task.resolved())
                return this.task.get();

            try {
                Log.info(this.message);
                Log.push();
                return this.task.execute();
            } finally {
                Log.pop();
            }
        }

        public Task getAsTask() {
            return task;
        }

        public Artifact getArtifact() {
            return artifact;
        }

        public @Nullable Task getVariants() {
            return variants;
        }
    }
}
