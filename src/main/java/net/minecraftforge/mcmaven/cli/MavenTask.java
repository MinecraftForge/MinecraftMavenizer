/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import joptsimple.OptionParser;
import joptsimple.OptionSpecBuilder;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentMappings;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.logging.Logger;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

class MavenTask {
    static void run(String[] args) throws Exception {
        // TODO [MCMavenizer] Make this into a --log [level] option
        LOGGER.setEnabled(Logger.Level.INFO);

        var parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        //@formatter:off
        // help message
        var helpO = parser.accepts("help",
            "Displays this help message and exits")
            .forHelp();

        // root cache directory
        var cacheO = parser.accepts("cache",
            "Directory to store data needed for this program")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("cache"));

        // jdk cache directory
        var jdkCacheO = parser.accepts("jdk-cache",
            "Directory to store jdks downloaded from the disoco api")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("cache/jdks"));

        // artifact version (NOT "display the program version")
        var versionO = parser.accepts("version",
            "The specific artifact version to generate")//, if none is specified, will attempt the 'latest' and 'recommended' for each Minecraft version")
            .withOptionalArg().ofType(String.class);

        // artifact to generate
        var artifactO = parser.accepts("artifact",
            "The artifact to attempt to generate, see the code for supported formats")
            .withRequiredArg().ofType(String.class).defaultsTo(Constants.FORGE_ARTIFACT);

        // root output directory
        var outputO = parser.accepts("output",
            "Root directory to generate the maven repository")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output"));

        // dependencies only
        var dependenciesOnlyO = parser.accepts("dependencies-only",
            "Outputs the maven containing only the Gradle Module and POM for the artifact's dependencies without outputting the artifact itself");

        // offline mode, fail on downloads
        var offlineO = parser.accepts("offline",
            "Do not attempt to download anything (allows offline operations, if possible)")
            .forHelp();

        // cache only, fail if out-of-date
        var cacheOnlyO = parser.accepts("cache-only",
            "Only use caches, fail if any downloads need to occur or if a task needs to do work");

        var mappingsO = parser.accepts("mappings",
            "Mappings to use for this artifact. Formatted as channel:version")
            .withRequiredArg().ofType(String.class).defaultsTo("official");

        var parchmentO = parser.accepts("parchment",
            "Version of parchment mappings to use, snapshots are not supported")
            .availableUnless(mappingsO)
            .withRequiredArg();

        var foreignRepositoryO = parser.accepts("repository",
            "EXPERIMENTAL: URL of a foreign maven repository to use for dependencies. The format is \"name,url\". The name must not include any commas.")
            .withRequiredArg().ofType(String.class);

        var globalAuxiliaryVariantsO = parser.accepts("global-auxiliary-variants",
            "Declares sources and javadoc jars as global variants, no matter the mapping version. This is used to work around gradle/gradle#35065");

        var disableGradleO = parser.accepts("disable-gradle",
            "Disabels the gradle module file, and writes all mappings to the main artifact files.");

        var stubO = parser.accepts("stub",
            "Runs any generated jar through a stub tool, deleteing data files and stubing all class files. The resulting jar can be compiled against but is non-functional.");

        var accessTransformerO = parser.accepts("access-transformer",
            "An AccessTransformer config to apply to the artifacts have been built. This is a work around for Gradle's broken ArtifactTransformer system. https://github.com/MinecraftForge/ForgeGradle/issues/1023")
            .availableUnless(stubO)
            .withRequiredArg().ofType(File.class);
        stubO.availableUnless(accessTransformerO);

        var shorthandOptions = new HashMap<String, OptionSpecBuilder>();
        var artifacts = Map.of(
            "forge",  Constants.FORGE_ARTIFACT,
            "fml",    Constants.FMLONLY_ARTIFACT,
            "mc",     "net.minecraft:joined",
            "client", "net.minecraft:client",
            "server", "net.minecraft:server",
            "mapping-data", "net.minecraft:mappings"
        );
        for (var entry : artifacts.entrySet()) {
            var key = entry.getKey();
            var option = parser.accepts(entry.getKey(),
                "Shorthand for --artifact " + entry.getValue());
            shorthandOptions.put(key, option);

            // do not allow with --artifact
            option.availableUnless(artifactO);
        }
        shorthandOptions.forEach((key, option) -> {
            // do not allow with other keys in the artifacts map
            for (var other : shorthandOptions.keySet()) {
                if (!other.equals(key))
                    option.availableUnless(other);
            }
        });
        //@formatter:on

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.release();
            return;
        }

        // global options
        if (options.has(offlineO))
            Mavenizer.setOffline();
        if (options.has(cacheOnlyO))
            Mavenizer.setCacheOnly();

        var output = options.valueOf(outputO);
        var cache = options.valueOf(cacheO);
        var jdkCache = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cache, "jdks");

        Artifact artifact = null;
        for (var entry : artifacts.entrySet()) {
            if (options.has(entry.getKey())) {
                artifact = Artifact.from(entry.getValue());
                break;
            }
        }

        if (artifact == null)
            artifact = Artifact.from(options.valueOf(artifactO));

        if (artifact.getVersion() == null)
            artifact = artifact.withVersion(options.valueOf(versionO));

        var mappings = options.has(parchmentO)
            ? new ParchmentMappings(options.valueOf(parchmentO))
            : Mappings.of(options.valueOf(mappingsO));

        var foreignRepositories = new HashMap<String, String>();
        for (var s : options.valuesOf(foreignRepositoryO)) {
            var split = s.split(",", 2);
            foreignRepositories.put(split[0], split[1]);
        }

        var mcmaven = new MinecraftMaven(output, options.has(dependenciesOnlyO), cache, jdkCache, mappings,
            foreignRepositories, options.has(globalAuxiliaryVariantsO), options.has(disableGradleO), options.has(stubO),
            options.valuesOf(accessTransformerO));
        mcmaven.run(artifact);
    }
}
