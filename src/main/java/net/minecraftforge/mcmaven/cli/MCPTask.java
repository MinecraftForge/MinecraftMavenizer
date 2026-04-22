/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import joptsimple.OptionParser;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.forge.Patcher;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import org.jetbrains.annotations.Nullable;

/**
 * Executes a MCPConfig task tree, and provides information from its output.
 * Optionally applies a specific mapping to the resulting source file.
 *
 * Output: {
 *   "config": <MCPConfig Artifact>
 *   "pipeline": "[client|server|joined]"
 *   "output": <path> -- The final patched and optionally renamed sources, or classes
 *   "classes.raw": <path> -- The 'raw' jar file, this is defined as the output
 *                      of the 'merge' or 'strip' tasks. Used to retrieve the obfuscated, but 'cleaned up' jar files
 *   "dependencies": <dep[,dep..]> -- Comma seperated list of depenencies.
 *   "extra": <path> -- The 'extra' jar, which is typically just the data files for Minecraft. But for older servers could contain the library classes.
 *
 *   Only available if there is a 'rename' step.
 *      "classes.srg": <path> -- The 'srg' jar file, this is defined as the output of the 'rename' task.
 *      "mappings.channel": "string"
 *      "mappings.version": "string"
 *      "mappings.obf2srg": <path> -- The mappings file used in the MCP 'rename' step.
 *
 *   Only available if Mappings specified and there is a 'rename' step:
 *     "mappings.zip": <path>
 *     "mappings.map2obf": <path>
 *     "mappings.map2srg": <path>
 *
 *   Only available if decompile is not disabled:
 *     "sources.srg": <path> -- Patched unremaped sources
 *
 *   Only available if decompile not not disabled, and Mappings specified:
 *     "sources.named": <path> -- Patched and mapped sources
 * }
 */
class MCPTask {
    static OptionParser run(String[] args, boolean getParser) throws Exception {
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

        // ignore caches, currently only invalidates HashStore entries
        var ignoreCacheO = parser.accepts("ignore-cache",
            "Forces all cache checks to fail, which results in all tasks re-running");

        // Add extra memory to the java decompile and recompile tasks
        var decompileMemoryO = parser.accepts("decompile-memory",
            "Overrides the -Xmx argument passed into the decompile sub-processes")
            .withRequiredArg();

        // mcp artifact output
        var outputO = parser.accepts("output",
                "File to output a JSON containing paths to extra files")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output.json"));

        // mcp artifact output
        var outputDirO = parser.accepts("output-dir",
            "File to output a JSON containing paths to extra files")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output"));

        var artifactO = parser.accepts("artifact",
            "MCPConfig artifact coordinates")
            .withRequiredArg();

        var versionO = parser.accepts("version",
            "MCPConfig artifact version")
            .withRequiredArg();

        var pipelineO = parser.accepts("pipeline",
            "MCPConfig pipeline to run, typically [client|server|joined]")
            .withRequiredArg().defaultsTo("joined");

        var disableDecompileO = parser.accepts("disable-decompile",
            "Stops processing before the decompile step, useful for getting base of binpatches");

        // Predecompile transformers
        var atO = parser.accepts("at",
            "Access Transformer config file to apply")
            .withOptionalArg().ofType(File.class);

        var sasO = parser.accepts("sas",
            "Side Annotation Stripper confg file to apply")
            .withOptionalArg().ofType(File.class);

        // Mappings to apply to the decompiled source, if we get that far
        var mappingsO = parser.accepts("mappings",
            "Mappings to use for this artifact. Formatted as channel:version")
            .availableUnless(disableDecompileO)
            .withRequiredArg().ofType(String.class).defaultsTo("official");

        //@formatter:on

        if (getParser)
            return parser;

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.release();
            return parser;
        }

        if (options.has(ignoreCacheO))
            Mavenizer.setIgnoreCache();
        if (options.has(decompileMemoryO))
            Mavenizer.setDecompileMemory(options.valueOf(decompileMemoryO));

        var output = options.valueOf(outputO);
        var outputDir = options.has(outputDirO) ? options.valueOf(outputDirO) : null;
        var cacheRoot = options.valueOf(cacheO);
        var jdkCacheRoot = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cacheRoot, "jdks");

        var artifact =
            options.has(artifactO) ? Artifact.from(options.valueOf(artifactO)) :
            options.has(versionO) ? MCP.artifact(options.valueOf(versionO)) :
            null;

        var pipeline = options.valueOf(pipelineO);

        var disableDecompile = options.has(disableDecompileO);
        var ats = new ArrayList<>(options.valuesOf(atO));
        var sas = new ArrayList<>(options.valuesOf(sasO));

        if (artifact == null) {
            LOGGER.error("Missing mcp --version or --artifact");
            LOGGER.release();
            return parser;

        }
        var mappings = !options.has(mappingsO) ? null :
            Mappings.of(options.valueOf(mappingsO)).withMCVersion(MinecraftMaven.mcpToMcVersion(artifact.getVersion()));

        LOGGER.info("  Output:     " + output.getAbsolutePath());
        LOGGER.info("  Output Dir: " + (outputDir == null ? null : outputDir.getAbsolutePath()));
        LOGGER.info("  Decompile:  " + !disableDecompile);
        LOGGER.info("  Cache:      " + cacheRoot.getAbsolutePath());
        LOGGER.info("  JDK Cache:  " + jdkCacheRoot.getAbsolutePath());
        LOGGER.info("  Artifact:   " + artifact);
        LOGGER.info("  Pipeline:   " + pipeline);
        LOGGER.info("  Mappings:   " + (mappings == null ? null : mappings.toString()));
        if (!ats.isEmpty())
            Util.filter(LOGGER, "  Access:     ", ats);
        if (!sas.isEmpty())
            Util.filter(LOGGER, "  SAS:        ", sas);
        LOGGER.info();

        var task = new MCPTask(outputDir, new Cache(cacheRoot, jdkCacheRoot), artifact, pipeline);
        var ret = task.classes();
        if(mappings != null)
            task.mappings(mappings);
        if (!disableDecompile)
            ret = task.decompile(mappings, ats, sas);
        task.data.put("output", ret);

        try {
            JsonData.toJson(task.data, output);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate file: %s".formatted(output.getAbsolutePath()), e);
        }

        return parser;

    }

    private final @Nullable File outputDir;

    private Map<String, String> data = new LinkedHashMap<>();
    private final MCPConfigRepo repo;
    private final MCP mcp;
    private final MCPSide side;
    private final String prefix;

    private MCPTask(@Nullable File outputDir, Cache cache, Artifact artifact, String pipeline) {
        this.outputDir = outputDir;

        this.repo = new MCPConfigRepo(cache, false);
        this.mcp = repo.get(artifact);
        this.side = mcp.getSide(pipeline);

        this.data.put("config", artifact.toString());
        this.data.put("pipeline", pipeline);
        this.prefix = "mcp/" + artifact.getVersion() + '/' + pipeline;

        var deps = new TreeSet<String>();
        this.side.forAllLibraries(art -> deps.add(art.getDescriptor()));
        this.data.put("dependencies", deps.stream().collect(Collectors.joining(",")));

        this.data.put("extra", local(side.getTasks().getExtra().execute(), prefix + "/extra.jar"));
    }

    private String classes() {
        this.data.put("classes.raw", local(side.getTasks().getRawJar().execute(), prefix + "/classes.raw.jar"));
        if (side.getTasks().getSrgJar() == null)
            return this.data.get("classes.raw");

        this.data.put("classes.srg", local(side.getTasks().getSrgJar().execute(), prefix + "/classes.srg.jar"));
        this.data.put("mappings.obf2srg", local(side.getTasks().getMappings().execute(), prefix + "/mappings.obf2srg.tsrg"));
        return this.data.get("classes.srg");
    }

    private void mappings(@Nullable Mappings mappings) {
        this.data.put("mappings.channel", mappings.channel());
        this.data.put("mappings.version", mappings.version());
        this.data.put("mappings.zip", local(mappings.getCsvZip(side).execute(), prefix + "/mappings.zip"));
        this.data.put("mappings.map2obf", local(mappings.getMapped2Obf(side).execute(), prefix + "/mappings.map2obf.tsrg.gz"));
        this.data.put("mappings.map2srg", local(mappings.getMapped2Srg(side).execute(), prefix + "/mappings.map2srg.tsrg.gz"));
    }

    private String decompile(@Nullable Mappings mappings, List<File> ats, List<File> sas) {
        var sourcesTask = side.getSources();

        if (!ats.isEmpty() || !sas.isEmpty()) {
            var hash = Util.sneak(() -> HashFunction.sha1().hash(Stream.concat(ats.stream(), sas.stream()).toList()));
            var dir = new File(side.getBuildFolder(), hash);

            var predecomp = side.getTasks().getPreDecompile();
            for (var cfg : ats) {
                var tmp = predecomp;
                predecomp = Task.named("modifyAccess[" + cfg.getName() + ']', Task.deps(tmp),
                    () -> Patcher.modifyAccess(dir, tmp, cfg, repo.getCache())
                );
            }

            for (var cfg : sas) {
                var tmp = predecomp;
                predecomp = Task.named("stripSides[" + cfg.getName() + ']', Task.deps(tmp),
                    () -> Patcher.stripSides(dir, tmp, cfg, repo.getCache())
                );
            }

            var factory = side.getTasks().child(dir, predecomp);
            sourcesTask = factory.getLastTask();
        }

        LOGGER.info("Creating MCP Source Jar");
        var indent = LOGGER.push();
        try {
            this.data.put("sources.srg", local(sourcesTask.execute(), prefix + "/sources.srg.jar"));
        } finally {
            LOGGER.pop(indent);
        }

        if (mappings == null)
            return this.data.get("sources.srg");

        LOGGER.info("Renaming MCP Source Jar");
        indent = LOGGER.push();
        try {
            var renameTask = new RenameTask(side.getBuildFolder(), side.getName(), side, sourcesTask, mappings, false);
            this.data.put("sources.named", local(renameTask.execute(), prefix + "/sources.named.jar"));
        } finally {
            LOGGER.pop(indent);
        }
        return this.data.get("sources.named");
    }

    private String local(File source, String relative) {
        if (outputDir == null)
            return source.getAbsolutePath();

        var target = new File(outputDir, relative);

        // Incase the output dir is the shared cache
        if (target.getAbsoluteFile().equals(source.getAbsoluteFile()))
            return relative;

        var cache = HashStore.fromFile(target)
            .add("source", source);
        if (Mavenizer.checkCache(target, cache))
            return relative;
        try {
            FileUtils.ensureParent(target);
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            cache.save();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate file: %s".formatted(target.getAbsolutePath()), t);
        }
        return relative;
    }

}
