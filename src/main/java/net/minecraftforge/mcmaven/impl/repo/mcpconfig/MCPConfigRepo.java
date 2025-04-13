/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.repo.deobf.DeobfuscatingRepo;
import net.minecraftforge.mcmaven.impl.repo.deobf.ProvidesDeobfuscation;
import net.minecraftforge.mcmaven.impl.util.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.OS;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/*
 * Provides the following artifacts:
 *
 * net.minecraft:
 *   client:
 *     MCPVersion:
 *       srg - Srg named SLIM jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   server:
 *     MCPVersion:
 *       srg - Srg named SLIM jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   joined:
 *     MCPVersion:
 *       .pom - Pom meta linking against net.minecraft:client:extra and net.minecraft:client:data
 *       '' - Notch named merged jar file
 *       srg - Srg named jar file.
 *       srg-sources - Srg named decompiled/patched code.
 *   mappings_{channel}:
 *     MCPVersion|MCVersion:
 *       .zip - A zip file containing SRG -> Human readable field and method mappings.
 *         Current supported channels:
 *         'stable', 'snapshot': MCP's crowdsourced mappings.
 *         'official': Official mappings released by Mojang.
 *
 *   Note: It does NOT provide the Obfed named jars for server and client, as that is provided by MinecraftRepo.
 */
// client extra
// TODO [MCMaven][Documentation] Document
public final class MCPConfigRepo extends Repo implements ProvidesDeobfuscation {
    private final Map<Artifact, MCP> versions = new HashMap<>();
    private final Map<String, MinecraftTasks> mcTasks = new HashMap<>();

    public MCPConfigRepo(Cache cache, File output) {
        super(cache, output);
    }

    public MCP get(String version) {
        return this.get(Artifact.from("de.oceanlabs.mcp", "mcp_config", version, null, "zip"));
    }

    public MCP get(Artifact artifact) {
        return this.versions.computeIfAbsent(artifact, this::download);
    }

    private MCP download(Artifact artifact) {
        return new MCP(this, artifact);
    }

    public MinecraftTasks getMCTasks(String version) {
        return this.mcTasks.computeIfAbsent(version, k -> new MinecraftTasks(this.cache.root(), version));
    }

    @Override
    public DeobfuscatingRepo getDeobfuscatingRepo() throws IllegalStateException {
        throw new UnsupportedOperationException("DeobfuscatingRepo is not implemented yet");
    }

    @Override
    public void process(String module, String version) {
        if (!module.startsWith("net.minecraft:"))
            throw new IllegalArgumentException("MCPConfigRepo cannot process modules that aren't for group net.minecraft");

        var side = module.substring("net.minecraft:".length());
        var mcp = this.get(Artifact.from("de.oceanlabs.mcp", "mcp_config", version, null, "zip"));
        var mcpSide = mcp.getSide(side);

        var build = mcpSide.getBuildFolder();
        var name = Artifact.from("net.minecraft", side, version);

        var renamer = new MCPRenamer(build, name, mcpSide);
        var recompiler = new MCPRecompiler(build, name, mcpSide, renamer);

        var sources = pending("Generating Sources", renamer.getSources(), name.withClassifier("sources"));
        var classes = pending("Recompiling Sources", mergeExtra(build, side, recompiler.getClasses(), mcpSide.getTasks().getExtra()), name);
        var pom = pending("Generating Maven POM", pom(build, side, mcpSide, version), name.withExtension("pom"));
        var gradleModule = pending("Generating Gradle Module", gradleModule(build, side, mcpSide, version, classes, sources), name.withExtension("module"));

        this.output(sources, classes, pom, gradleModule);
    }

    // TODO [MCMaven][client-extra] Band-aid fix for merging for clean! Remove later.
    private static Task mergeExtra(File build, String side, Task recompiled, Task extra) {
        return Task.named("mergeExtra[" + side + ']', Set.of(extra, recompiled), () -> {
            var output = new File(build, "recompiled-extra.jar");
            var recompiledF = recompiled.get();
            var extraF = extra.get();
            var cache = HashStore
                .fromFile(output)
                .add(output, recompiledF, extraF);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            try {
                FileUtils.mergeJars(output, true, extraF, recompiledF);
            } catch (IOException e) {
                Util.sneak(e);
            }

            cache.add(output).save();
            return output;
        });
    }

    private static Task pom(File build, String side, MCPSide mcpSide, String version) {
        return Task.named("pom[" + side + ']', () -> {
            var output = new File(build, side + ".pom");
            var cache = HashStore.fromFile(output).add(output);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraft", side, version).withGradleMetadata();

            Util.make(mcpSide.getMCLibraries(), l -> l.removeIf(a -> a.getOs() != OS.UNKNOWN)).forEach(a -> {
                builder.dependencies().add(a, "compile");
            });
            Util.make(mcpSide.getMCPConfigLibraries(), l -> l.removeIf(a -> a.getOs() != OS.UNKNOWN)).forEach(a -> {
                builder.dependencies().add(a, "compile");
            });

            FileUtils.ensureParent(output);
            try (var os = new FileOutputStream(output)) {
                os.write(builder.build().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | ParserConfigurationException | TransformerException e) {
                Util.sneak(e);
            }

            cache.add(output).save();
            return output;
        });
    }

    // TODO CLEANUP
    // TODO [MCMaven][ForgeRepo] store partial variants as files in a "variants" folder, then merge them into the module
    private static Task gradleModule(File build, String side, MCPSide mcpSide, String version, PendingArtifact classes, PendingArtifact sources) {
        return Task.named("gradleModule[" + side + ']', () -> {
            var output = new File(build, side + ".module");
            var classesF = classes.get();
            var sourcesF = sources.get();
            var cache = HashStore
                .fromFile(output)
                .add(output, classesF, sourcesF);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            int dashIdx = version.indexOf('-');
            @Deprecated final var versionWithoutMCP = dashIdx > -1 ? version.substring(0, version.indexOf('-')) : version;
            @Deprecated final var official = "official-" + versionWithoutMCP;

            // set the mappings version. unused for now.
            // if the current mappings = the official mappings, set to null so we don't duplicate the variants
            final var mappingsVersion = Util.replace("official-" + versionWithoutMCP, s -> {
                if (official.equals(s)) {
                    return null;
                }

                return s;
            });

            var module = GradleModule.of("net.minecraft", side, version);

            // TODO move this variant creation to it's own method. it is easily reproducible
            // official
            var officialClasses = Util.make(new GradleModule.Variant(), variant -> {
                variant.name = "classes-" + official;
                variant.attributes = Map.of(
                    "org.gradle.usage", "java-runtime",
                    "org.gradle.category", "library",
                    "org.gradle.dependency.bundling", "external",
                    "org.gradle.libraryelements", "jar",
                    "net.minecraftforge.mappings.channel", "official",
                    "net.minecraftforge.mappings.version", versionWithoutMCP
                );
                variant.files = List.of(new GradleModule.Variant.File(classes.getArtifact().getFilename(), classesF));
                variant.dependencies = Util.make(new ArrayList<>(), dependencies -> {
                    mcpSide.getMCLibraries().forEach(a -> dependencies.add(GradleModule.Variant.Dependency.of(a)));
                    mcpSide.getMCPConfigLibraries().forEach(a -> dependencies.add(GradleModule.Variant.Dependency.of(a)));
                });
            });
            var officialSources = Util.make(new GradleModule.Variant(), variant -> {
                variant.name = "sources-" + official;
                variant.attributes = Map.of(
                    "org.gradle.usage", "java-runtime",
                    "org.gradle.category", "documentation",
                    "org.gradle.dependency.bundling", "external",
                    "org.gradle.docstype", "sources",
                    "org.gradle.libraryelements", "jar",
                    "net.minecraftforge.mappings.channel", "official",
                    "net.minecraftforge.mappings.version", versionWithoutMCP
                );
                variant.files = List.of(new GradleModule.Variant.File(sources.getArtifact().getFilename(), sourcesF));
            });
            module.variant(officialClasses);
            module.variant(officialSources);

            // ALSO TODO add parchment mappings and other mappings support

            FileUtils.ensureParent(output);
            try {
                JsonData.toJson(module, output);
            } catch (IOException e) {
                Util.sneak(e);
            }

            cache.add(output).save();
            return output;
        });
    }
}
