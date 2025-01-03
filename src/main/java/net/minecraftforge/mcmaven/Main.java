/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven;

import java.io.File;
import java.util.TreeMap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.mcmaven.cache.Cache;
import net.minecraftforge.mcmaven.data.JsonData;
import net.minecraftforge.mcmaven.forge.ForgeRepo;
import net.minecraftforge.mcmaven.util.Artifact;
import net.minecraftforge.mcmaven.util.Constants;
import net.minecraftforge.mcmaven.util.DownloadUtils;
import net.minecraftforge.mcmaven.util.JarVersionLookupHandler;
import net.minecraftforge.mcmaven.util.Log;

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

        File output = options.valueOf(outputO);
        File cache = options.valueOf(cacheO);
        File jdkCache = null;
        if (!options.has(cacheO) || options.has(jdkCacheO))
            jdkCache = options.valueOf(jdkCacheO);
        else
            jdkCache = new File(cache, "jdks");

        String artifact = options.valueOf(artifactO);
        for (var key : artifacts.keySet()) {
            if (options.has(key))
                artifact = artifacts.get(key);
        }

        var version = options.valueOf(versionO);
        var caches = new Cache(cache, jdkCache);

        log("Minecarft Mavenizer " + JarVersionLookupHandler.getInfo(Main.class).impl().version().orElse(""));
        log("  Output:    " + output.getAbsolutePath());
        log("  Cache:     " + cache.getAbsolutePath());
        log("  JDK Cache: " + jdkCache.getAbsolutePath());
        log("  Artifact:  " + artifact);
        log("  Version:   " + version);

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
            log("Currently Unsupported, Will add later");
        }
    }

    private static void log(String message) {
        Log.log(message);
    }
}
