/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.util.Set;

import joptsimple.OptionParser;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentMappings;
import net.minecraftforge.mcmaven.impl.repo.forge.Patcher;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Log;

// TODO [Mavenizer][MCPTask] Cleanup. Works well but is a mess.
public class MCPTask {
    public static void run(String[] args) throws Exception {
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

        // mcp artifact output
        var outputO = parser.accepts("output",
            "Root directory to generate the maven repository")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output.jar"));

        var artifactO = parser.accepts("artifact",
            "MCPConfig artifact coordinates")
            .withRequiredArg();

        var versionO = parser.accepts("version",
            "MCPConfig artifact version")
            .withRequiredArg();

        var pipelineO = parser.accepts("pipeline",
            "MCPConfig pipeline to run, typically [client|server|joined]")
            .withRequiredArg().defaultsTo("joined");

        var rawO = parser.accepts("raw",
            "Use to output a raw jar file without any MCPConfig transformations.");

        var seargeO = parser.accepts("searge",
            "Use with --raw to output the raw jar file renamed with SRG names")
            .availableIf(rawO);

        var atO = parser.accepts("at",
            "Access Transformer config file to apply")
            .availableUnless(rawO).withOptionalArg().ofType(File.class);

        var sasO = parser.accepts("sas",
            "Side Annotation Stripper confg file to apply")
            .availableUnless(rawO).withOptionalArg().ofType(File.class);

        var mappingsO = parser.accepts("mappings",
            "Use to enable using official mappings")
            .availableUnless(rawO);

        var parchmentO = parser.accepts("parchment",
            "Version of parchment mappings to use, snapshots are not supported")
            .availableIf(mappingsO).withRequiredArg();
        //@formatter:on

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(Log.INFO);
            Log.release();
            return;
        }

        var output = options.valueOf(outputO);
        var cacheRoot = options.valueOf(cacheO);
        var jdkCacheRoot = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cacheRoot, "jdks");

        var artifact =
            options.has(artifactO) ? Artifact.from(options.valueOf(artifactO)) :
            options.has(versionO) ? Artifact.from("de.oceanlabs.mcp", "mcp_config", options.valueOf(versionO), null, "zip") :
            null;

        var pipeline = options.valueOf(pipelineO);
        var ats = options.has(atO) ? options.valueOf(atO) : null;
        var sas = options.has(sasO) ? options.valueOf(sasO) : null;

        if (artifact == null) {
            Log.error("Missing mcp --version or --artifact");
            Log.release();
            return;
        }

        var repo = new MCPConfigRepo(new Cache(cacheRoot, jdkCacheRoot));
        Log.info("  Output:     " + output.getAbsolutePath());
        Log.info("  Cache:      " + cacheRoot.getAbsolutePath());
        Log.info("  JDK Cache:  " + jdkCacheRoot.getAbsolutePath());
        Log.info("  Artifact:   " + artifact);
        Log.info("  Pipeline:   " + pipeline);
        if (options.has(rawO)) {
            Log.info("  Raw Names:  " + (options.has(seargeO) ? "Searge" : "Notch"));
        } else {
            Log.info("  Access:     " + (ats == null ? null : ats.getAbsolutePath()));
            Log.info("  SAS:        " + (sas == null ? null : sas.getAbsolutePath()));
        }
        Log.info();

        var mcp = repo.get(artifact);
        var side = mcp.getSide(pipeline);

        if (options.has(rawO)) {
            var searge = options.has(seargeO);
            var cache = HashStore.fromFile(output)
                .addKnown("obfuscation", searge ? "srg" : "notch");

            Task rawTask = searge ? side.getTasks().getSrgJar() : side.getTasks().getRawJar();
            File raw;

            Log.info("Creating Raw Jar");
            var indent = Log.push();
            try {
                raw = rawTask.execute();
                cache.add("raw", raw);
            } finally {
                Log.pop(indent);
            }

            if (!output.exists() || !cache.isSame()) {
                try {
                    org.apache.commons.io.FileUtils.copyFile(raw, output);
                    cache.save();
                } catch (Throwable t) {
                    throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
                }
            }

            return;
        }

        var sourcesTask = side.getSources();

        if (ats != null || sas != null) {
            var hash = Util.hash(HashFunction.SHA1, ats, sas);
            var dir = new File(side.getBuildFolder(), hash);

            var predecomp = side.getTasks().getPreDecompile();
            if (ats != null) {
                var tmp = predecomp;
                predecomp = Task.named("modifyAccess", Task.deps(tmp), () -> Patcher.modifyAccess(dir, tmp, ats, repo.getCache()));
            }

            if (sas != null) {
                var tmp = predecomp;
                predecomp = Task.named("stripSides", Task.deps(tmp), () -> Patcher.stripSides(dir, tmp, sas, repo.getCache()));
            }

            var factory = side.getTasks().child(dir, predecomp);
            sourcesTask = factory.getLastTask();
        }

        File sources = null;
        {
            Log.info("Creating MCP Source Jar");
            var indent = Log.push();
            try {
                sources = sourcesTask.execute();
            } finally {
                Log.pop(indent);
            }
        }

        var cache = HashStore.fromFile(output)
            .add("sources", sources);

        if (options.has(mappingsO)) {
            Log.info("Renaming MCP Source Jar");
            var indent = Log.push();
            try {
                var mappings = options.has(parchmentO)
                    ? new ParchmentMappings(options.valueOf(parchmentO))
                    : new Mappings("official", null).withMCVersion(MinecraftMaven.mcpToMcVersion(artifact.getVersion()));

                var renameTask = new RenameTask(side.getBuildFolder(), pipeline, side, sourcesTask, mappings);
                sources = renameTask.execute();
            } finally {
                Log.pop(indent);
            }

            cache.add("renamed", sources);
        }

        if (!output.exists() || !cache.isSame()) {
            try {
                org.apache.commons.io.FileUtils.copyFile(sources, output);
                cache.save();
            } catch (Throwable t) {
                throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
            }
        }
    }
}
