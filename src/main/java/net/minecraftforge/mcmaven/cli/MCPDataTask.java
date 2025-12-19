/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipFile;

import joptsimple.OptionParser;
import net.minecraftforge.mcmaven.impl.MinecraftMaven;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.mappings.ParchmentMappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IRenamer;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;
import net.minecraftforge.srgutils.IMappingFile.IParameter;
import net.minecraftforge.util.logging.Logger;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

// TODO [Mavenizer][MCPDataTask] This is a copy of FG6's ExtractMCPData task.
// its not the best, but I dont want to re-wrok INSTALLER_TOOLS to put the tsrg in the mappings zip
public class MCPDataTask {
    public static void run(String[] args) throws Exception {
        int ret = runI(args);
        if (ret != 0) {
            LOGGER.release();
            //System.exit(ret);
        }
    }

    private static int runI(String[] args) throws Exception {
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

        var outputO = parser.accepts("output",
            "File to place output data into")
            .withRequiredArg().ofType(File.class);

        var artifactO = parser.accepts("artifact",
            "MCPConfig artifact coordinates")
            .withRequiredArg();

        var versionO = parser.accepts("version",
            "MCPConfig artifact version")
            .availableUnless(artifactO)
            .withRequiredArg();

        var officialO = parser.accepts("mappings",
            "Use to enable using official mappings");

        var parchmentO = parser.accepts("parchment",
            "Version of parchment mappings to use, snapshots are not supported")
            .availableUnless(officialO)
            .withRequiredArg();

        officialO
            .availableUnless(parchmentO);

        var keyO = parser.accepts("key",
            "The key for which data file to extract")
            .withRequiredArg();
        //@formatter:on

        var options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            return -1;
        }

        var output = options.valueOf(outputO);
        var cacheRoot = options.valueOf(cacheO);
        var jdkCacheRoot = !options.has(cacheO) || options.has(jdkCacheO)
            ? options.valueOf(jdkCacheO)
            : new File(cacheRoot, "jdks");

        var artifact =
            options.has(artifactO) ? Artifact.from(options.valueOf(artifactO)) :
            options.has(versionO) ? MCP.artifact(options.valueOf(versionO)) :
            null;

        if (artifact == null) {
            LOGGER.error("Missing mcp --version or --artifact");
            return -2;
        }

        var mcVersion = MinecraftMaven.mcpToMcVersion(artifact.getVersion());
        Mappings mappings;
        if (options.has(officialO))
            mappings = new Mappings("official", null).withMCVersion(mcVersion);
        else if (options.has(parchmentO))
            mappings = new ParchmentMappings(options.valueOf(parchmentO)).withMCVersion(mcVersion);
        else
            mappings = null;

        var key = options.valueOf(keyO);
        if (key == null) {
            LOGGER.error("Missing --key option");
            return -3;
        }

        var repo = new MCPConfigRepo(new Cache(cacheRoot, jdkCacheRoot), false);
        LOGGER.info("  Output:     " + output.getAbsolutePath());
        LOGGER.info("  Cache:      " + cacheRoot.getAbsolutePath());
        LOGGER.info("  JDK Cache:  " + jdkCacheRoot.getAbsolutePath());
        LOGGER.info("  Artifact:   " + artifact);
        LOGGER.info("  Key:        " + key);
        if (mappings != null)
            LOGGER.info("  Mappings:   " + mappings);
        LOGGER.info();

        var mcp = repo.get(artifact);
        var side = mcp.getSide("joined");

        var cfg = mcp.getConfig().getData("joined");
        var path = cfg.get(key);
        if (path == null && "statics".equals(key))
            path = "config/static_methods.txt";

        if (path == null) {
            LOGGER.error("Could not find data entry for '%s'".formatted(key));
            return -4;
        }

        try (ZipFile zip = new ZipFile(mcp.getData())) {
            var entry = zip.getEntry(path);
            if (entry == null) {
                LOGGER.error("Invalid config zip, missing file: " + path);
                return -5;
            }

            if ("mappings".equals(key)) {
                var ret = IMappingFile.load(zip.getInputStream(entry));

                if (mcp.getConfig().official) {
                    var mc = mcp.getMinecraftTasks();
                    var client = mc.versionFile(MinecraftTasks.Files.CLIENT_MAPPINGS);
                    var server = mc.versionFile(MinecraftTasks.Files.SERVER_MAPPINGS);

                    var obf2OffClient = IMappingFile.load(client.execute());
                    var obf2OffServer = IMappingFile.load(server.execute());
                    var obf2Off = obf2OffClient.merge(obf2OffServer).reverse();

                    ret = ret.rename(new IRenamer() {
                        @Override
                        public String rename(IMappingFile.IClass value) {
                            return obf2Off.remapClass(value.getOriginal());
                        }
                    });
                }

                if (mappings != null) {
                    var csv = mappings.getCsvZip(side).execute();
                    var names = Mappings.load(csv).names();
                    ret = ret.rename(new IRenamer() {
                        @Override
                        public String rename(IField value) {
                            return names.getOrDefault(value.getMapped(), value.getMapped());
                        }

                        @Override
                        public String rename(IMethod value) {
                            return names.getOrDefault(value.getMapped(), value.getMapped());
                        }

                        @Override
                        public String rename(IParameter value) {
                            return names.getOrDefault(value.getMapped(), value.getMapped());
                        }
                    });
                }


                ret.write(output.getAbsoluteFile().toPath(), IMappingFile.Format.TSRG2, false);
            } else {
                Files.copy(zip.getInputStream(entry), output.toPath());
            }
        }

        return 0;
    }
}
