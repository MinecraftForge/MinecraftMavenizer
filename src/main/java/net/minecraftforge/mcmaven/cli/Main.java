/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.util.TreeMap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.JsonData;
import net.minecraftforge.mcmaven.impl.forge.ForgeRepo;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.DownloadUtils;

import static net.minecraftforge.mcmaven.impl.util.Constants.LOGGER;

// TODO [MCMaven] Make an actual API with an api package.
/**
 * Main entry point for the tool.
 *
 * @see #main(String[])
 */
public class Main {
    /**
     * The entry point for the Minecraft Mavenizer. Details on usage can be found by running the program with the
     * {@code --help} flag.
     *
     * @param args The command line arguments
     * @throws Exception If any kind of error occurs
     */
    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        // help message
        var helpO = parser.accepts("help", "Displays this help message and exits");

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
            "The specific artifact version to generate, if none is specified, will attempt the 'latest' and 'recommended' for each Minecraft version")
            .withOptionalArg().ofType(String.class);

        // artifact to generate
        var artifactO = parser.accepts("artifact",
            "The artifact to attempt to generate, see the code for supported formats")
            .withRequiredArg().ofType(String.class).defaultsTo(Constants.FORGE_ARTIFACT);

        // root output directory
        var outputO = parser.accepts("output",
            "Root directory to generate the maven repository")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output"));

        var artifacts = new TreeMap<String, String>();
        artifacts.put("forge",  Constants.FORGE_ARTIFACT);
        artifacts.put("mc",     "net.minecraft:joined");
        artifacts.put("client", "net.minecraft:client");
        artifacts.put("server", "net.minecraft:server");
        for (String key : artifacts.keySet())
            parser.accepts(key, "Shorthand for --artifact " + artifacts.get(key));

        OptionSet options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(System.out);
            return;
        }

        var output = options.valueOf(outputO);
        var cache = options.valueOf(cacheO);
        var jdkCache = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cache, "jdks");

        String artifact = options.valueOf(artifactO);
        for (var key : artifacts.keySet()) {
            if (options.has(key))
                artifact = artifacts.get(key);
        }

        var version = options.valueOf(versionO);
        var caches = new Cache(cache, jdkCache);

        JarVersionInfo.of(Main.class).hello(LOGGER::info, true, true);
        LOGGER.info("  Output:    " + output.getAbsolutePath());
        LOGGER.info("  Cache:     " + cache.getAbsolutePath());
        LOGGER.info("  JDK Cache: " + jdkCache.getAbsolutePath());
        LOGGER.info("  Artifact:  " + artifact);
        LOGGER.info("  Version:   " + version);

        if (Constants.FORGE_ARTIFACT.equals(artifact)) {
            var proc = new ForgeRepo(caches, output);
            if (version == null) {
                var data = DownloadUtils.downloadString(Constants.FORGE_PROMOS);
                var promos = JsonData.promosSlim(data);
                for (var ver : promos.versions().reversed())
                    proc.process(ver);
            } else if ("all".equals(version)) {
                var versions = caches.forge.getVersions(Artifact.from(Constants.FORGE_ARTIFACT));
                for (var ver : versions.reversed())
                    proc.process(ver);
            } else {
                proc.process(version);
            }
        } else {
            LOGGER.error("Artifact '%s' is currently Unsupported. Will add later".formatted(artifact));
        }
    }
}
