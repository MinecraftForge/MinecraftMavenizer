/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.deobf.DeobfuscatingRepo;
import net.minecraftforge.mcmaven.impl.repo.deobf.ProvidesDeobfuscation;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPRenamer;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.GlobalOptions;
import net.minecraftforge.mcmaven.impl.util.GradleAttributes;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.OS;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

// TODO: [MCMaven][ForgeRepo] For now, the ForgeRepo needs to be fully complete with everything it has to do.
// later, we can worry about refactoring it so that other repositories such as MCP (clean) and FMLOnly can function.
// And yes, I DO want this tool to support as far back as possible. But for now, we worry about UserDev3 and up.
// - Jonathing

/** Represents the Forge repository. */
public final class ForgeRepo extends Repo implements ProvidesDeobfuscation {
    // TODO: [MCMaven][FGVersion] Handle this as an edge-case in FGVersion
    private static final ComparableVersion
        USERDEV3_START = new ComparableVersion("1.12.2-14.23.5.2851"),
        USERDEV3_END = new ComparableVersion("1.12.2-14.23.5.2860");

    final MCPConfigRepo mcpconfig;
    final File globalBuild;

    /**
     * Creates a new Forge repository.
     *
     * @param cache  The cache directory
     * @param output The output directory.
     */
    public ForgeRepo(Cache cache, File output) {
        super(cache, output);
        this.mcpconfig = new MCPConfigRepo(cache, output);
        this.globalBuild = new File(cache.root(), "forge/.global");
    }

    @Override
    public DeobfuscatingRepo getDeobfuscatingRepo() throws IllegalStateException {
        throw new UnsupportedOperationException("DeobfuscatingRepo is not implemented yet");
    }

    @Override
    public void process(String module, String version) {
        //noinspection SwitchStatementWithTooFewBranches - fmlonly will be added later
        switch (module) {
            case Constants.FORGE_ARTIFACT/*, Constants.FMLONLY_ARTIFACT */:
                break;
            default:
                throw new IllegalArgumentException("Unknown or unsupported module: " + module);
        }

        var fg = FGVersion.fromForge(version);
        Log.info("Processing Minecraft Forge (userdev): " + version);
        var indent = Log.push();
        try {
            switch (fg) {
                // TODO [MCMaven][Backporting] You know what has to be done eventually...
                case null -> throw new IllegalArgumentException("Python version unsupported!");
                case v1_1, v1_2, v2, v2_1, v2_2, v2_3 ->
                    throw new IllegalArgumentException("Only FG 3+ currently supported");
                case v3, v4, v5, v6 -> processV3(version, "official", forgeToMcVersion(version));
            }
        } finally {
            Log.pop(indent);
        }
    }

    @SuppressWarnings("unused")
    private static String forgeToMcVersion(String version) {
        // Save for a few april-fools versions, Minecraft doesn't use _ in their version names.
        // So when Forge needs to reference a version of Minecraft that uses - in the name, it replaces
        // it with _
        // This could cause issues if we ever support a version with _ in it, but fuck it I don't care right now.
        int idx = version.indexOf('-');
        return version.substring(0, idx).replace('_', '-');
    }

    private static Artifact getUserdev(String forge) {
        var forgever = new ComparableVersion(forge);
        // userdev3, old attempt to make 1.12.2 FG3 compatible
        var userdev3 = forgever.compareTo(USERDEV3_START) >= 0 && forgever.compareTo(USERDEV3_END) <= 0;
        return Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, userdev3 ? "userdev3" : "userdev", "jar");
    }

    private void processV3(String version, String channel, String mapping) {
        var name = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, version);
        var userdev = getUserdev(version);

        var build = new File(this.cache.root(), "forge/" + userdev.getFolder());

        var patcher = new Patcher(build, this, userdev);
        var renamer = new MCPRenamer(build, userdev, patcher.getMCP().getSide(MCPSide.JOINED), patcher);
        var recompiler = new ForgeRecompiler(build, this.cache, name, patcher, renamer);

        var mcVersion = patcher.getMCP().getName().getVersion();

        var sources = pending("Generating Sources", renamer.getSources(), name.withClassifier("sources"));
        var classes = pending("Recompiling Sources", recompiler.getClasses(), name);

        var mcName = Artifact.from("net.minecraft", "client", mcVersion);
        var clientExtra = pending("Getting Client Extra", patcher.getMCPSide().getTasks().getExtra(), mcName.withClassifier("extra"));
        var clientExtraPom = pending("Generating Client POM", clientExtraPom(build, patcher, mcVersion), mcName.withExtension("pom"));
        var clientExtraGradleModule = pending("Generating Client Gradle Module", clientExtraGradleModule(build, patcher, clientExtra), mcName.withExtension("module"));

        var pom = pending("Generating Maven POM", pom(build, patcher, version, clientExtra), name.withExtension("pom"));
        var gradleModule = pending("Generating Gradle Module", gradleModule(build, patcher, version, classes, sources, clientExtra), name.withExtension("module"));
        var metadata = pending("Generating Metadata", metadata(build, patcher), name.withClassifier("metadata").withExtension("zip"));

        this.output(sources, classes, clientExtra, clientExtraPom, clientExtraGradleModule, pom, gradleModule, metadata);
    }

    private static Task metadata(File build, Patcher patcher) {
        return Task.named("metadata[forge]", Set.of(patcher.getMCP().getMinecraftTasks().versionJson), () -> {
            var output = new File(build, "metadata.zip");

            // metadata
            var metadataDir = new File(output.getParentFile(), "metadata");
            var versionProperties = new File(metadataDir, "version.properties");

            // metadata/launcher
            var launcherDir = new File(metadataDir, "launcher");
            var runsJsonStr = JsonData.toJson(patcher.config.runs);

            // metadata/minecraft
            var minecraftDir = new File(metadataDir, "minecraft");
            var versionJson = patcher.getMCP().getMinecraftTasks().versionJson.get();

            var cache = HashStore
                .fromFile(output).add(output)
                .add("runs", runsJsonStr.getBytes(StandardCharsets.UTF_8))
                .add(versionJson)
                .add(versionProperties);
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
                    // TODO [MCMaven][ForgeRepo] make this configurable later
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

            cache.add(output).save();
            return output;
        });
    }

    private static Task pom(File build, Patcher patcher, String version, PendingArtifact clientExtra) {
        return Task.named("pom[forge]", () -> {
            var output = new File(build, "forge.pom");
            var cache = HashStore.fromFile(output).add(output);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraftforge", "forge", version).withGradleMetadata();

            builder.dependencies().add(clientExtra.getArtifact(), "compile");

            for (var a : patcher.getArtifacts()) {
                builder.dependencies().add(a, "compile");
            }

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
    private static Task gradleModule(File build, Patcher patcher, String version, PendingArtifact classes, PendingArtifact sources, PendingArtifact clientExtra) {
        return Task.named("gradleModule[forge]", Set.of(classes.getAsTask(), sources.getAsTask(), clientExtra.getAsTask()), () -> {
            var output = new File(build, "forge.module");
            var classesF = classes.get();
            var sourcesF = sources.get();
            var cache = HashStore
                .fromFile(output)
                .add(output, classesF, sourcesF);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            @Deprecated var mcVersionWithoutMCP = forgeToMcVersion(version);
            @Deprecated var officialVersion = "official-" + mcVersionWithoutMCP;

            // set the mappings version. unused for now.
            // if the current mappings = the official mappings, set to null so we don't duplicate the variants
            final var mappingsVersion = Util.replace("official-" + mcVersionWithoutMCP, s -> {
                if (officialVersion.equals(s)) {
                    return null;
                }

                return s;
            });

            var module = GradleModule.of("net.minecraftforge", "forge", version);

            // TODO move this variant creation to it's own method. it is easily reproducible
            // official
            var officialClasses = Util.make(new GradleModule.Variant(), variant -> {
                variant.name = "classes-" + officialVersion;
                variant.attributes = Map.of(
                    "org.gradle.usage", "java-runtime",
                    "org.gradle.category", "library",
                    "org.gradle.dependency.bundling", "external",
                    "org.gradle.libraryelements", "jar",
                    "net.minecraftforge.mappings.channel", "official",
                    "net.minecraftforge.mappings.version", mcVersionWithoutMCP
                );
                variant.files = List.of(new GradleModule.Variant.File(classes.getArtifact().getFilename(), classesF));
                variant.dependencies = Util.make(new ArrayList<>(), dependencies -> {
                    dependencies.add(GradleModule.Variant.Dependency.of(clientExtra.getArtifact()));
                    for (var a : patcher.getArtifacts()) {
                        dependencies.add(GradleModule.Variant.Dependency.of(a));
                    }
                });
            });
            var officialSources = Util.make(new GradleModule.Variant(), variant -> {
                variant.name = "sources-" + officialVersion;
                variant.attributes = Map.of(
                    "org.gradle.usage", "java-runtime",
                    "org.gradle.category", "documentation",
                    "org.gradle.dependency.bundling", "external",
                    "org.gradle.docstype", "sources",
                    "org.gradle.libraryelements", "jar",
                    "net.minecraftforge.mappings.channel", "official",
                    "net.minecraftforge.mappings.version", mcVersionWithoutMCP
                );
                variant.files = List.of(new GradleModule.Variant.File(sources.getArtifact().getFilename(), sourcesF));
            });
            module.variant(officialClasses);
            module.variant(officialSources);

            // TODO [MCMaven][ForgeRepo] This is a mess. Clean it up.
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

    private static Task clientExtraPom(File build, Patcher patcher, String mcVersion) {
        return Task.named("clientExtraPom[forge]", () -> {
            var output = new File(build, "clientExtra.pom");
            var cache = HashStore.fromFile(output).add(output);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();

            var builder = new POMBuilder("net.minecraft", "client", mcVersion).withGradleMetadata();

            var side = patcher.getMCP().getSide(MCPSide.JOINED);

            Util.make(side.getMCLibraries(), l -> l.removeIf(a -> a.getOs() != OS.UNKNOWN)).forEach(a -> {
                builder.dependencies().add(a, "compile");
            });
            Util.make(side.getMCPConfigLibraries(), l -> l.removeIf(a -> a.getOs() != OS.UNKNOWN)).forEach(a -> {
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

    private static Task clientExtraGradleModule(File build, Patcher patcher, PendingArtifact clientExtra) {
        return Task.named("clientExtraModule[forge]", Set.of(clientExtra.getAsTask()), () -> {
            var output = new File(build, "clientExtra.module");
            var clientExtraF = clientExtra.get();
            var cache = HashStore
                .fromFile(output)
                .add(output, clientExtraF);
            if (output.exists() && cache.isSame())
                return output;

            GlobalOptions.assertNotCacheOnly();
            var side = patcher.getMCPSide();

            var module = GradleModule.of(clientExtra.getArtifact());
            var files = List.of(new GradleModule.Variant.File(clientExtra.getArtifact().getFilename(), clientExtraF));

            var variants = module.nativeVariants();
            var all = new ArrayList<GradleModule.Variant.Dependency>();

            for (var artifact : side.getMCLibraries()) {
                var selected = variants.get(GradleAttributes.NativeDescriptor.from(artifact.getOs()));
                var dependency = GradleModule.Variant.Dependency.of(artifact);

                if (selected != null) {
                    selected.dependencies.add(dependency);
                } else {
                    all.add(dependency);
                }
            }

            for (var variant : variants.values()) {
                variant.files = files;
                variant.dependencies.addAll(all);
            }

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
