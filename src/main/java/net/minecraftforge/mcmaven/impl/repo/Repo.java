/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.GradleAttributes;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.OS;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

public abstract class Repo {
    protected final Cache cache;

    protected Repo(Cache cache) {
        this.cache = cache;
    }

    public final Cache getCache() {
        return cache;
    }

    public abstract List<PendingArtifact> process(Artifact artifact, Mappings mappings);

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

    protected Supplier<GradleModule.Variant[]> simpleVariant(String name, Mappings mappings) {
        return () -> new GradleModule.Variant[] {
            GradleModule.Variant
                .of(name)
                .attribute("org.gradle.category", "library")
                .attribute("org.gradle.libraryelements", "jar")
                .attribute(Mappings.CHANNEL_ATTR, mappings.channel())
                .attribute(Mappings.VERSION_ATTR, mappings.version())
        };
    }

    // Classes needs a variant for each OS type so that we can have different natives
    protected GradleModule.Variant[] classVariants(Mappings mappings, MCPSide side, Artifact... extraDeps) {
        var all = new ArrayList<Artifact>();
        var natives = new EnumMap<GradleAttributes.OperatingSystemFamily, List<Artifact>>(GradleAttributes.OperatingSystemFamily.class);
        natives.put(GradleAttributes.OperatingSystemFamily.WINDOWS, new ArrayList<>());
        natives.put(GradleAttributes.OperatingSystemFamily.MACOS, new ArrayList<>());
        natives.put(GradleAttributes.OperatingSystemFamily.LINUX, new ArrayList<>());

        Consumer<Artifact> addToVariants = artifact -> {
            if (artifact == null) return;

            if (GradleAttributes.OperatingSystemFamily.allowsAll(artifact)) {
                all.add(artifact);
            } else {
                for (var os : artifact.getOs()) {
                    GradleAttributes.OperatingSystemFamily variant = GradleAttributes.OperatingSystemFamily.from(os);
                    natives.get(variant).add(artifact);
                }
            }
        };

        side.getMCLibraries().forEach(addToVariants);
        side.getMCPConfigLibraries().forEach(addToVariants);
        Arrays.asList(extraDeps).forEach(addToVariants);

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
        // TODO [MCMavenizer][Gradle Modules] Cannot have a common variant because it has incomplete dependencies (missing natives)
        //  Launching the game wouldn't work because of that. If we need a common variant, it would need to include everything
        //  But since FG7 will never not have the OS attribute, it wouldn't be used anyways.
        //variants.add(GradleModule.Variant.of("classes", common));
        natives.forEach((os, deps) -> {
            variants.add(
                GradleModule.Variant
                    .of("classes-" + os.getValue(), common)
                    .attribute(os)
                    .deps(deps)
            );
        });

        return variants.toArray(new GradleModule.Variant[0]);
    }

    protected static Task simplePom(File build, Artifact artifact) {
        return Task.named("pom[" + artifact.getName() + ']', () -> {
            var output = new File(build, artifact.getName() + '-' + artifact.getVersion() + ".pom");
            var cache = HashStore.fromFile(output);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            var builder = new POMBuilder(artifact.getGroup(), artifact.getName(), artifact.getVersion());

            FileUtils.ensureParent(output);
            try (var os = new FileOutputStream(output)) {
                os.write(builder.build().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | ParserConfigurationException | TransformerException e) {
                Util.sneak(e);
            }

            cache.save();
            return output;
        });
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
                if (this.variants != null)
                    this.variants.execute();

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
