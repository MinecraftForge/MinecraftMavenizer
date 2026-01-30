/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.io.IOException;
import joptsimple.OptionParser;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.MCPSetupFiles;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentMappings;
import net.minecraftforge.mcmaven.impl.repo.forge.Patcher;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPTaskFactory;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;
import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import net.minecraftforge.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

// TODO [Mavenizer][MCPTask] Cleanup. Works well but is a mess.
class MCPTask {
    static OptionParser run(String[] args, boolean getParser) throws Exception {
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

        // mcp artifact output
        var outputO = parser.accepts("output",
            "File to output the final jar")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output.jar"));

        // mcp artifact output
        var outputFilesO = parser.accepts("output-files",
            "File to output a JSON containing paths to extra files")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("files.json"));

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

        if (getParser)
            return parser;

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.release();
            return parser;
        }

        var output = options.valueOf(outputO);
        var outputFiles = options.valueOf(outputFilesO);
        var cacheRoot = options.valueOf(cacheO);
        var jdkCacheRoot = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cacheRoot, "jdks");

        var artifact =
            options.has(artifactO) ? Artifact.from(options.valueOf(artifactO)) :
            options.has(versionO) ? MCP.artifact(options.valueOf(versionO)) :
            null;

        var pipeline = options.valueOf(pipelineO);
        var ats = options.has(atO) ? options.valueOf(atO) : null;
        var sas = options.has(sasO) ? options.valueOf(sasO) : null;

        if (artifact == null) {
            LOGGER.error("Missing mcp --version or --artifact");
            LOGGER.release();
            return parser;
        }

        var repo = new MCPConfigRepo(new Cache(cacheRoot, jdkCacheRoot), false);
        LOGGER.info("  Output:     " + output.getAbsolutePath());
        LOGGER.info("  Cache:      " + cacheRoot.getAbsolutePath());
        LOGGER.info("  JDK Cache:  " + jdkCacheRoot.getAbsolutePath());
        LOGGER.info("  Artifact:   " + artifact);
        LOGGER.info("  Pipeline:   " + pipeline);
        if (options.has(rawO)) {
            LOGGER.info("  Raw Names:  " + (options.has(seargeO) ? "Searge" : "Notch"));
        } else {
            LOGGER.info("  Access:     " + (ats == null ? null : ats.getAbsolutePath()));
            LOGGER.info("  SAS:        " + (sas == null ? null : sas.getAbsolutePath()));
        }
        LOGGER.info();

        var mcp = repo.get(artifact);
        var side = mcp.getSide(pipeline);

        if (options.has(rawO)) {
            var searge = options.has(seargeO);
            var cache = HashStore.fromFile(output)
                .addKnown("obfuscation", searge ? "srg" : "notch");

            Task rawTask = searge ? side.getTasks().getSrgJar() : side.getTasks().getRawJar();
            File raw;

            LOGGER.info("Creating Raw Jar");
            var indent = LOGGER.push();
            try {
                raw = rawTask.execute();
                cache.add("raw", raw);
            } finally {
                LOGGER.pop(indent);
            }

            if (!output.exists() || !cache.isSame()) {
                try {
                    org.apache.commons.io.FileUtils.copyFile(raw, output);
                    if (outputFiles != null)
                        writeFiles(side.getTasks(), outputFiles);
                    cache.save();
                } catch (Throwable t) {
                    throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
                }
            }

            return parser;
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
            LOGGER.info("Creating MCP Source Jar");
            var indent = LOGGER.push();
            try {
                sources = sourcesTask.execute();
            } finally {
                LOGGER.pop(indent);
            }
        }

        var cache = HashStore.fromFile(output)
            .add("sources", sources);

        if (options.has(mappingsO)) {
            LOGGER.info("Renaming MCP Source Jar");
            var indent = LOGGER.push();
            try {
                var mappings = options.has(parchmentO)
                    ? new ParchmentMappings(options.valueOf(parchmentO))
                    : new Mappings("official", null).withMCVersion(MinecraftMaven.mcpToMcVersion(artifact.getVersion()));

                var renameTask = new RenameTask(side.getBuildFolder(), pipeline, side, sourcesTask, mappings, false);
                sources = renameTask.execute();
            } finally {
                LOGGER.pop(indent);
            }

            cache.add("renamed", sources);
        }

        if (!output.exists() || !cache.isSame()) {
            try {
                org.apache.commons.io.FileUtils.copyFile(sources, output);
                if (outputFiles != null)
                    writeFiles(side.getTasks(), outputFiles);
                cache.save();
            } catch (Throwable t) {
                throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
            }
        }

        return parser;
    }

    // TODO [Mavenizer][Extra MCPTask Files] do this better
    private static void writeFiles(MCPTaskFactory mcpTaskFactory, File output) {
        var files = new MCPSetupFiles();
        files.versionManifest = getTaskPath(mcpTaskFactory, "downloadManifest");
        files.versionJson = getTaskPath(mcpTaskFactory, "downloadJson");
        files.clientRaw = getTaskPath(mcpTaskFactory, "downloadClient");
        files.serverRaw = getTaskPath(mcpTaskFactory, "downloadServer");
        files.serverExtracted = getTaskPath(mcpTaskFactory, "extractServer");
        files.clientMappings = mcpTaskFactory.downloadClientMappings().execute().getAbsolutePath();
        files.serverMappings = mcpTaskFactory.downloadServerMappings().execute().getAbsolutePath();
        files.librariesList = getTaskPath(mcpTaskFactory, "listLibraries");

        try {
            JsonData.toJson(files, output);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write extra files data: " + output.getPath(), e);
        }
    }

    // TODO Do this better
    private static @Nullable String getTaskPath(MCPTaskFactory mcpTaskFactory, String step) {
        try {
            return mcpTaskFactory.findStep(step).execute().getAbsolutePath();
        } catch (Exception e) {
            LOGGER.error("Cannot serialize output path for MCP step: " + step);
            return null;
        }
    }
}
