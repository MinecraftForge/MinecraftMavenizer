/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.tasks.RecompileTask;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Log;

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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

// TODO: [MCMavenizer][ForgeRepo] For now, the ForgeRepo needs to be fully complete with everything it has to do.
// later, we can worry about refactoring it so that other repositories such as MCP (clean) and FMLOnly can function.
// And yes, I DO want this tool to support as far back as possible. But for now, we worry about UserDev3 and up.
// - Jonathing

/** Represents the Forge repository. */
public final class ForgeRepo extends Repo {
    // TODO: [MCMavenizer][FGVersion] Handle this as an edge-case in FGVersion
    private static final ComparableVersion USERDEV3_START = new ComparableVersion("1.12.2-14.23.5.2851");
    private static final ComparableVersion USERDEV3_END = new ComparableVersion("1.12.2-14.23.5.2860");

    final MCPConfigRepo mcpconfig;
    final File globalBuild;

    /**
     * Creates a new Forge repository.
     *
     * @param cache     The cache directory
     * @param mcpconfig The MCPConfig repo
     */
    public ForgeRepo(Cache cache, MCPConfigRepo mcpconfig) {
        super(cache);
        this.mcpconfig = mcpconfig;
        this.globalBuild = new File(cache.root(), "forge/.global");
    }

    @Override
    public List<PendingArtifact> process(Artifact artifact, Mappings mappings) {
        var module = artifact.getGroup() + ':' + artifact.getName();
        var version = artifact.getVersion();
        if (!Constants.FORGE_ARTIFACT.equals(module))
            throw new IllegalArgumentException("Unknown or unsupported module: " + module);

        var fg = FGVersion.fromForge(version);
        Log.info("Processing Minecraft Forge (userdev): " + version);
        var indent = Log.push();
        try {
            if (fg == null)
                throw new IllegalArgumentException("Python version unsupported!");

            // TODO [MCMavenizer][Backporting] You know what has to be done eventually...
            if (fg.ordinal() < FGVersion.v3.ordinal())
                throw new IllegalArgumentException("Only FG 3+ currently supported");

            if (fg.ordinal() <= FGVersion.v6.ordinal())
                return processV3(version, mappings);

            throw new IllegalArgumentException("Forge version %s is not supported yet".formatted(version));
        } finally {
            Log.pop(indent);
        }
    }

    private static Artifact getUserdev(String forge) {
        var forgever = new ComparableVersion(forge);
        // userdev3, old attempt to make 1.12.2 FG3 compatible
        var userdev3 = forgever.compareTo(USERDEV3_START) >= 0 && forgever.compareTo(USERDEV3_END) <= 0;
        return Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, userdev3 ? "userdev3" : "userdev", "jar");
    }

    /// This handles UserDev3 artifacts, which are anything created using FG 3->6
    ///
    /// We need to generate the following artifacts:
    /// - `net.minecraftforge:forge:{version}`
    ///   - default:
    ///     - The default jar contains the recompiled class files, patcher assets
    ///   - sources:
    ///     - Source files used to recompile the default jar.
    ///   - metadata.zip:
    ///     - Metadata about the version, such as runs.json and version.json.
    /// - `net.minecraft:{mcp-version}:client`
    ///   - extra:
    ///     - This is the client jar file with class files removed. This is for legacy versions which expect it to
    /// exist.
    /// - `net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip`
    ///   - A zip file containing fields, methods, and params.csv files mapping SRG->MCP names.
    ///
    /// All variants need to provide their gradle module metadata information to be merged as needed.
    ///
    /// Currently, we don't support any non-standard variants. So there is no merging. But my idea is that the custom
    /// mapping channels would be custom attributes. As well as a new flag to skip the client-extra, and merge it in the
    /// main jar instead.
    ///
    /// If the mappings are `official`, we also need to generate:
    ///   - pom:
    ///     - Standard maven pom file that contains all dependency information.
    // Made this an MD comment to make it easier to read in IDE - Jonathan
    private List<PendingArtifact> processV3(String version, Mappings mappings) {
        var name = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, version);
        var userdev = getUserdev(version);

        var build = new File(this.cache.root(), "forge/" + userdev.getFolder());

        var patcher = new Patcher(build, this, userdev);
        var joined = patcher.getMCP().getSide(MCPSide.JOINED);
        var sourcesTask = new RenameTask(build, userdev, joined, patcher.get(), mappings);
        var recompile = new RecompileTask(build, name, patcher.getMCP(), patcher::getClasspath, sourcesTask, mappings);
        var classesTask = new InjectTask(build, this.cache, name, patcher, recompile, mappings);

        var extraCoords = Artifact.from(Constants.MC_GROUP, Constants.MC_CLIENT + "-extra", patcher.getMCP().getName().getVersion());
        var mappingCoords = mappings.getArtifact(joined);

        var mapzip = pending("Mappings Zip", mappings.getCsvZip(joined), mappingCoords, false);
        var mappom = pending("Mappings POM", simplePom(build, mappingCoords), mappingCoords.withExtension("pom"), false);

        var sources = pending("Sources", sourcesTask, name.withClassifier("sources"), true, sourceVariant(mappings));
        var classes = pending("Classes", classesTask, name, false, () -> classVariants(mappings, patcher, extraCoords, mappingCoords));
        var metadata = pending("Metadata", metadata(build, patcher), name.withClassifier("metadata").withExtension("zip"), false);

        var pom = pending("Maven POM", pom(build, patcher, version, extraCoords, mappingCoords), name.withExtension("pom"), false);

        var extraOutput = this.mcpconfig.processExtra(Constants.MC_GROUP + ':' + Constants.MC_CLIENT, patcher.getMCP().getName().getVersion());
        return Stream.concat(
            extraOutput.stream(),
            Stream.of(mapzip, mappom, sources, classes, pom, metadata)
        ).toList();
    }

    private static Task metadata(File build, Patcher patcher) {
        return Task.named("metadata[forge]", Task.deps(patcher.getMCP().getMinecraftTasks().versionJson), () -> {
            var output = new File(build, "metadata.zip");

            // metadata
            var metadataDir = new File(output.getParentFile(), "metadata");
            var versionProperties = new File(metadataDir, "version.properties");

            // metadata/launcher
            var launcherDir = new File(metadataDir, "launcher");
            var runsJsonStr = JsonData.toJson(patcher.config.runs);

            // metadata/minecraft
            var minecraftDir = new File(metadataDir, "minecraft");
            var versionJson = patcher.getMCP().getMinecraftTasks().versionJson.execute();

            var cache = HashStore
                .fromFile(output)
                .add("data", patcher.getDataHash())
                .add(versionJson)
                .addKnown("version", "1");
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            try {
                FileUtils.ensureParent(output);
                FileUtils.ensure(metadataDir);
                FileUtils.ensure(launcherDir);
                FileUtils.ensure(minecraftDir);

                // version.properties
                try (FileWriter writer = new FileWriter(versionProperties)) {
                    // TODO [MCMavenizer][ForgeRepo] make this configurable later
                    writer.append("version=1").append('\n').flush();
                }

                // runs.json
                Files.writeString(
                    new File(launcherDir, "runs.json").toPath(),
                    runsJsonStr,
                    StandardCharsets.UTF_8
                );

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

    private static Task pom(File build, Patcher patcher, String version, Artifact clientExtra, Artifact mappings) {
        return Task.named("pom[forge]", () -> {
            var output = new File(build, "forge.pom");
            var cache = HashStore.fromFile(output)
                .addKnown("data", patcher.getDataHash())
                .addKnown("extra", Util.replace(clientExtra, Object::toString))
                .addKnown("mappings", Util.replace(mappings, Object::toString))
                ;

            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraftforge", "forge", version).preferGradleModule().dependencies(dependencies -> {
                if (clientExtra != null)
                    dependencies.add(clientExtra);

                if (mappings != null)
                    dependencies.add(mappings);

                patcher.forAllLibraries(dependencies::add, Artifact::hasNoOs);
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

    protected GradleModule.Variant[] classVariants(Mappings mappings, Patcher patcher, Artifact... extraDeps) {
        var extra = new ArrayList<>(Arrays.asList(extraDeps));
        extra.addAll(patcher.getArtifacts());
        return super.classVariants(mappings, patcher.getMCPSide(), extra.toArray(Artifact[]::new));
    }
}
