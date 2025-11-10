/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.tasks.RecompileTask;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
// TODO [MCMavenizer][Documentation] Document
public final class MCPConfigRepo extends Repo {
    private final Map<Artifact, MCP> versions = new HashMap<>();
    private final Map<String, MinecraftTasks> mcTasks = new HashMap<>();
    private final boolean dependenciesOnly;

    public MCPConfigRepo(Cache cache, boolean dependenciesOnly) {
        this.dependenciesOnly = dependenciesOnly;
        super(cache);
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
    public List<PendingArtifact> process(Artifact artifact, Mappings mappings) {
        var module = artifact.getGroup() + ':' + artifact.getName();
        var version = artifact.getVersion();
        if (!module.startsWith("net.minecraft:"))
            throw new IllegalArgumentException("MCPConfigRepo cannot process modules that aren't for group net.minecraft");

        var side = module.substring("net.minecraft:".length());
        boolean isMappings = "mappings".equals(side);
        if (isMappings)
            side = "joined";

        if (side.endsWith("-extra"))
            return processExtra(Constants.MC_GROUP + ':' + side.substring(0, side.length() - "-extra".length()), version);

        var mcp = this.get(Artifact.from("de.oceanlabs.mcp", "mcp_config", version, null, "zip"));
        var mcpSide = mcp.getSide(side);

        var mcpTasks = mcpSide.getTasks();
        var build = mcpSide.getBuildFolder();
        var name = Artifact.from("net.minecraft", side, version);

        var pom = pending("Maven POM", pom(build, side, mcpSide, version), name.withExtension("pom"), false);
        var metadata = pending("Metadata", metadata(build, mcpSide), name.withClassifier("metadata").withExtension("zip"), false, metadataVariant());

        if (isMappings) {
            name = mappings.getArtifact(mcpSide);
            return List.of(
                pending("Mappings", mappings.getCsvZip(mcpSide), name, false),
                pending("Mappings POM", simplePom(build, name), name.withExtension("pom"), false)
            );
        } else if (dependenciesOnly) {
            return List.of(
                pom.withVariants(() -> classVariants(mappings, mcpSide)),
                metadata
            );
        }

        return switch (mappings.channel()) {
            case "notch" -> List.of(pending("Classes", mcpTasks.getRawJar(), name.withClassifier("raw"), false, simpleVariant("obf-notch", new Mappings("notch", null))));
            case "srg", "searge" -> List.of(pending("Classes", mcpTasks.getSrgJar(), name.withClassifier("srg"), false, simpleVariant("obf-searge", new Mappings("searge", null))));
            default -> {
                var pending = new ArrayList<PendingArtifact>();

                var sourcesTask = new RenameTask(build, name.getName(), mcpSide, mcpSide.getSources(), mappings, true);
                var recompile = new RecompileTask(build, name, mcpSide.getMCP(), mcpSide::getClasspath, sourcesTask, mappings);
                var classesTask = mergeExtra(build, side, recompile, mcpSide.getTasks().getExtra(), mappings);

                var sources = pending("Sources", sourcesTask, name.withClassifier("sources"), true, sourceVariant(mappings));
                var classes = pending("Classes", classesTask, name, false, () -> classVariants(mappings, mcpSide));

                pending.addAll(List.of(
                    sources, classes, metadata, pom
                ));

                yield pending;
            }
        };
    }

    public List<PendingArtifact> processExtra(String module, String version) {
        if (!module.startsWith("net.minecraft:"))
            throw new IllegalArgumentException("MCPConfigRepo cannot process modules that aren't for group net.minecraft");

        var side = module.substring("net.minecraft:".length());
        var displayName = Character.toUpperCase(side.charAt(0)) + side.substring(1);
        var mcp = this.get(Artifact.from("de.oceanlabs.mcp", "mcp_config", version, null, "zip"));
        var mcpSide = mcp.getSide(side);

        var build = mcpSide.getBuildFolder();
        var name = Artifact.from("net.minecraft", side + "-extra", version);

        var extraTask = mcpSide.getTasks().getExtra();
        var pomTask = pomExtra(build, side + "-extra", version);

        var extra = pending(displayName + " Extra", extraTask, name, false);
        var pom = pending(displayName + " Maven POM", pomTask, name.withExtension("pom"), false);

        return List.of(extra, pom);
    }

    // TODO [MCMavenizer][client-extra] Band-aid fix for merging for clean! Remove later.
    private static Task mergeExtra(File build, String side, Task recompiled, Task extra, Mappings mappings) {
        return Task.named("mergeExtra[" + side + "][" + mappings + ']', Task.deps(extra, recompiled), () -> {
            var output = new File(mappings.getFolder(build), "recompiled-extra.jar");
            var recompiledF = recompiled.execute();
            var extraF = extra.execute();
            var cache = HashStore
                .fromFile(output)
                .add(recompiledF, extraF);
            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();

            try {
                FileUtils.mergeJars(output, true, extraF, recompiledF);
            } catch (IOException e) {
                Util.sneak(e);
            }

            cache.save();
            return output;
        });
    }

    private static Task metadata(File build, MCPSide side) {
        var minecraftTasks = side.getMCP().getMinecraftTasks();
        return Task.named("metadata[" + side + ']', Task.deps(minecraftTasks.versionJson), () -> {
            var output = new File(build, "metadata.zip");

            // metadata
            var metadataDir = new File(output.getParentFile(), "metadata");
            var versionProperties = new File(metadataDir, "version.properties");

            // metadata/minecraft
            var minecraftDir = new File(metadataDir, "minecraft");
            var versionJson = minecraftTasks.versionJson.execute();

            var cache = HashStore
                .fromFile(output)
                .add(versionJson)
                .add(versionProperties);
            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();

            try {
                FileUtils.ensureParent(output);
                FileUtils.ensure(metadataDir);
                FileUtils.ensure(minecraftDir);

                // version.properties
                try (FileWriter writer = new FileWriter(versionProperties)) {
                    // TODO [MCMavenizer][ForgeRepo] make this configurable later
                    writer.append("version=1").append('\n').flush();
                }

                // version.json
                Files.copy(
                    versionJson.toPath(),
                    new File(minecraftDir, "version.json").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
                cache.add(versionProperties);

                // metadata.zip
                FileUtils.makeZip(metadataDir, output);
            } catch (IOException e) {
                Util.sneak(e);
            }

            cache.save();
            return output;
        });
    }

    private static Task pom(File build, String side, MCPSide mcpSide, String version) {
        return Task.named("pom[" + side + ']', () -> {
            var output = new File(build, side + ".pom");
            var cache = HashStore.fromFile(output);
            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraft", side, version).preferGradleModule().dependencies(dependencies -> {
                mcpSide.forAllLibraries(dependencies::add, Artifact::hasNoOs);
            });

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

    private static Task pomExtra(File build, String side, String version) {
        return Task.named("pom[" + side + ']', () -> {
            var output = new File(build, side + ".pom");
            var cache = HashStore.fromFile(output);
            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraft", side, version);

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
}
