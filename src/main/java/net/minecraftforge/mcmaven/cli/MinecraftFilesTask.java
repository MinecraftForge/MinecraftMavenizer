/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import joptsimple.OptionParser;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.ArtifactFile;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.MCFile;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

/**
 * Downlaods and prepares Minecraft related files.
 * Currently the only goal of this is to gain access to vanilla files.
 * No actual processing, except for extraction of the bundled server jar is done.
 *
 * The paths contained in this json file are system specific and thus can not be shared
 * across workspaces. Unless you specify the --output-dir option which will copy all files
 * to the target directory and the json will list relative paths.
 *
 * Output:
 * {
 *   "id": <Minecraft Version>
 *   "version": <path> -- Version json file
 *   "client": <path>, -- The raw client jar
 *   "server": <path>, -- The raw server jar
 *
 *   "server.extracted": <path> -- equal to "server" if the server jar is not a bundle
 *
 *   "client.libraries": <path> -- File containing a list of all client libraries, reguardless of OS
 *   "server.libraries": <path> -- File containing a list of all server libraries, empty if the server jar is not a bundle.
 *
 *   "client.mappings": <path>
 *   "server.mappings": <path> -- Official mappings file, null if none specified in version json
 *
 *   // TODO: [Mavenizer] Download client assets
 *   //"client.assets": <path> -- Path to the root 'assets' directory, only if asked for using --assets
 * }
 */
class MinecraftFilesTask {
    static OptionParser run(String[] args, boolean getParser) throws Exception {
        var parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        //@formatter:off
        // help message
        var helpO = parser.accepts("help",
            "Displays this help message and exits")
            .forHelp();

        var cacheO = parser.accepts("cache",
            "Directory to store data needed for this program")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("cache"));

        var outputO = parser.accepts("output",
            "File to output a JSON containing paths to extra files")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output.json"));

        var outputDirO = parser.accepts("output-dir",
            "Folder to output all files to, if specified, paths in output json will be relative to this")
            .withRequiredArg().ofType(File.class).defaultsTo(new File("output"));

        var versionO = parser.accepts("version",
            "Minecraft version")
            .withRequiredArg().required();
        //@formatter:on

        if (getParser)
            return parser;

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.release();
            return parser;
        }


        var outputDir = options.has(outputDirO) ? options.valueOf(outputDirO) : null;
        var output = options.valueOf(outputO);

        var cacheRoot = options.valueOf(cacheO);
        var version = options.valueOf(versionO);

        LOGGER.info("  Output:     " + output.getAbsolutePath());
        LOGGER.info("  Output-Dir: " + (outputDir == null ? "null" : outputDir.getAbsolutePath()));
        LOGGER.info("  Version:    " + version);
        LOGGER.info("  Cache:      " + cacheRoot.getAbsolutePath());
        LOGGER.info();

        var task = new MinecraftFilesTask(output, outputDir, version, cacheRoot);
        task.run();

        return parser;
    }

    private final File output;
    private final File outputDir;
    private final String version;
    private final MCPConfigRepo repo;
    private final MinecraftTasks tasks;

    private MinecraftFilesTask(File output, File outputDir, String version, File cacheRoot) {
        this.output = output;
        this.outputDir = outputDir;
        this.version = version;
        repo = new MCPConfigRepo(new Cache(cacheRoot, new File(cacheRoot, "jdks")), false);
        tasks = repo.getMCTasks(version);
    }

    private void run() {
        var data = new LinkedHashMap<String, String>();
        var versionJson = tasks.versionJson.execute();
        var json = JsonData.minecraftVersion(versionJson);
        var server = tasks.versionFile(MCFile.SERVER_JAR).execute();
        var serverExtracted = tasks.extractServer().execute();
        var root = outputDir != null ? new File(outputDir, version) : tasks.versionCache();

        data.put("id", version);
        data.put("version", local(versionJson, version + "/version.json"));
        data.put("client", local(tasks.versionFile(MCFile.CLIENT_JAR).execute(), version + "/client.jar"));
        data.put("client.libraries", libraries(root, "client", tasks.getClientLibraries()));
        data.put("server", local(server, version + "/server.jar"));
        if (server.getAbsoluteFile().equals(serverExtracted.getAbsoluteFile()))
            data.put("server.extracted", data.get("server"));
        else {
            data.put("server.extracted", local(serverExtracted, version + "/server-extracted.jar"));
            data.put("server.libraries", libraries(root, "server", tasks.getServerLibraries()));
        }
        if (json.getDownload("client_mappings") != null)
            data.put("client.mappings", local(tasks.versionFile(MCFile.CLIENT_MAPPINGS).execute(), version +"/client_mappings.txt"));
        if (json.getDownload("server_mappings") != null)
            data.put("server.mappings", local(tasks.versionFile(MCFile.SERVER_MAPPINGS).execute(), version +"/server_mappings.txt"));

        try {
            JsonData.toJson(data, output);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate file: %s".formatted(output.getAbsolutePath()), e);
        }
    }

    private String libraries(File root, String side, List<ArtifactFile> libraries) {
        var libs = new ArrayList<String>();
        for (var af : libraries)
            libs.add(local(af.file(), "libraries/" + af.artifact().getPath()));

        var target = new File(root, side + "-libraries.txt");

        try {
            FileUtils.ensureParent(target);
            Files.writeString(target.toPath(), libs.stream().collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate file: %s".formatted(target.getAbsolutePath()), e);
        }

        return local(target, version + '/' + side + "-libraries.txt");
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
