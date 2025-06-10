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
import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentMappings;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.logging.Log;

class MavenTask {
    static void run(String[] args) throws Exception {
        // TODO [MCMavenizer] Make this into a --log [level] option
        Log.enabled = Log.Level.INFO;

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

        // offline mode, fail on downloads
        var offlineO = parser.accepts("offline",
            "Do not attempt to download anything (allows offline operations, if possible)")
            .forHelp();

        // cache only, fail if out-of-date
        var cacheOnlyO = parser.accepts("cache-only",
            "Only use caches, fail if any downloads need to occur or if a task needs to do work");

        var parchmentO = parser.accepts("parchment",
            "Version of parchment mappings to use, snapshots are not supported")
            .withRequiredArg();

        var shorthandOptions = new HashMap<String, OptionSpecBuilder>();
        var artifacts = Map.of(
            "forge",  Constants.FORGE_ARTIFACT,
            "fml",    Constants.FMLONLY_ARTIFACT,
            "mc",     "net.minecraft:joined",
            "client", "net.minecraft:client",
            "server", "net.minecraft:server"
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
            parser.printHelpOn(Log.INFO);
            Log.release();
            return;
        }

        // global options
        if (options.has(offlineO))
            GlobalOptions.setOffline();
        if (options.has(cacheOnlyO))
            GlobalOptions.setCacheOnly();

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
            : new Mappings("official", null);

        var mcmaven = new MinecraftMaven(output, cache, jdkCache, mappings);
        mcmaven.run(artifact);
    }
}
