/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.GradleAttributes;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    protected static PendingArtifact pending(String message, Task task, Artifact artifact, boolean auxiliary) {
        return pending(message, task, artifact, auxiliary, (Task)null);
    }

    protected static PendingArtifact pending(String message, Task task, Artifact artifact, boolean auxiliary, Supplier<GradleModule.Variant[]> variants) {
        return pending(message, task, artifact, auxiliary, variantTask(task, variants));
    }

    protected static PendingArtifact pending(String message, Task task, Artifact artifact, boolean auxiliary, @Nullable Task variants) {
        return new PendingArtifact(message, task, artifact, auxiliary, variants);
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

    protected Supplier<GradleModule.Variant[]> metadataVariant() {
        return () -> new GradleModule.Variant[] {
            GradleModule.Variant.of("metadata")
                .attribute("org.gradle.usage", "metadata")
        };
    }

    protected static Task variantTask(Task parent, Supplier<GradleModule.Variant[]> supplier) {
        return Task.named(parent.name() + "[variants]", Task.deps(parent), () -> {
            var variants = supplier.get();

            var variantFile = new File(parent.execute().getAbsolutePath() + ".variants");
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

    protected GradleModule.Variant[] classVariants(Mappings mappings, MCPSide side) {
        return classVariants(mappings, side, List.of(), List.of(), List.of());
    }

    // Classes needs a variant for each OS type so that we can have different natives
    protected GradleModule.Variant[] classVariants(Mappings mappings, MCPSide side, Collection<Artifact> extraDeps, Collection<Artifact> extraCompileDeps, Collection<Artifact> extraRuntimeDeps) {
        var all = new ArrayList<Artifact>();
        var natives = new HashMap<GradleAttributes.OperatingSystemFamily, List<Artifact>>();

        for (var artifact : side.getMCLibraries()) {
            var osVariants = EnumSet.noneOf(GradleAttributes.OperatingSystemFamily.class);
            for (var os : artifact.getOs()) {
                var variant = GradleAttributes.OperatingSystemFamily.from(os);
                if (variant != null)
                    osVariants.add(variant);
            }

            if (osVariants.isEmpty()) {
                all.add(artifact);
            } else {
                for (var variant : osVariants) {
                    natives.computeIfAbsent(variant, k -> new ArrayList<>()).add(artifact);
                }
            }
        }

        all.addAll(side.getMCPConfigLibraries());

        for (var extra : extraDeps) {
            if (extra != null)
                all.add(extra);
        }

        var java = Util.replace(
            JsonData.minecraftVersion(side.getMCP().getMinecraftTasks().versionJson.execute()),
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
        for (var e : natives.entrySet()) {
            var variant = GradleModule.Variant.of("classes-" + e.getKey().getValue(), common);
            variant.attribute(e.getKey());
            variant.deps(e.getValue());
            variants.add(variant);
        }

        var apiVariant = GradleModule.Variant.of("api-classes", common);
        apiVariant.attribute("org.gradle.usage", "java-api");
        variants.add(apiVariant);

        return variants.toArray(new GradleModule.Variant[0]);
    }

    protected static Task simplePom(File build, Artifact artifact) {
        return Task.named("pom[" + artifact.getName() + ']', () -> {
            var output = new File(build, artifact.getName() + '-' + artifact.getVersion() + ".pom");
            var cache = HashStore.fromFile(output);
            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();

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
        private final boolean auxiliary;
        private final @Nullable Task variants;

        private PendingArtifact(String message, Task task, Artifact artifact, boolean auxiliary, @Nullable Task variants) {
            this.message = message;
            this.task = task;
            this.artifact = artifact;
            this.auxiliary = auxiliary;
            this.variants = variants;
        }

        @Override
        public File get() {
            if (this.task.resolved())
                return this.task.execute();

            try {
                LOGGER.info(this.message);
                LOGGER.push();
                return this.task.execute();
            } finally {
                if (this.variants != null)
                    this.variants.execute();

                LOGGER.pop();
            }
        }

        public Task getAsTask() {
            return task;
        }

        public Artifact getArtifact() {
            return artifact;
        }

        public boolean isAuxiliary() {
            return auxiliary;
        }

        public @Nullable Task getVariants() {
            return variants;
        }
    }
}
