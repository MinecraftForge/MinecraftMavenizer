/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IRenamer;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;
import net.minecraftforge.srgutils.IMappingFile.IParameter;
import net.minecraftforge.util.hash.HashStore;
import org.jetbrains.annotations.Nullable;

import de.siegmar.fastcsv.reader.CsvReader;

public class Mappings {
    public static final String CHANNEL_ATTR = "net.minecraftforge.mappings.channel";
    public static final String VERSION_ATTR = "net.minecraftforge.mappings.version";

    protected enum Tasks {
        CSVs("srg2names"),
        MappedToSrg("mapped2srg"),
        MappedToObf("mapped2obf");

        private final String name;

        private Tasks(String name) {
            this.name = name;
        }
    };

    protected record Key(Tasks type, MCPSide side) {}

    protected final Map<Key, Task> tasks = new HashMap<>();
    private final String channel;
    private final @Nullable String version;

    public static Mappings of(String mappingsNotation) {
        var split = mappingsNotation.split(":", 2);
        var channel = split[0];
        var version = split.length > 1 ? split[1] : null;

        return "parchment".equalsIgnoreCase(channel)
            ? new ParchmentMappings(version)
            : new Mappings(channel, version);
    }

    public record Data(Map<String, String> names, Map<String, String> docs) { }
    public static Data load(File data) throws IOException {
        var names = new HashMap<String, String>();
        var docs = new HashMap<String, String>();
        try (var zip = new ZipFile(data)) {
            var entries = zip.stream().filter(e -> e.getName().endsWith(".csv")).toList();
            for (var entry : entries) {
                try (var reader = CsvReader.builder().ofNamedCsvRecord(new InputStreamReader(zip.getInputStream(entry)))) {
                    for (var row : reader) {
                        var header = row.getHeader();
                        var obf = header.contains("searge") ? "searge" : "param";
                        var searge = row.getField(obf);
                        names.put(searge, row.getField("name"));
                        if (header.contains("desc")) {
                            String desc = row.getField("desc");
                            if (!desc.isBlank())
                                docs.put(searge, desc);
                        }
                    }
                }
            }
        }

        return new Data(names, docs);
    }

    public Mappings(String channel, @Nullable String version) {
        this.channel = channel;
        this.version = version;
    }

    public String channel() {
        return this.channel;
    }

    public @Nullable String version() {
        return this.version;
    }

    @Override
    public String toString() {
        return channel() + (version() == null ? "" : '-' + version());
    }

    public File getFolder(File root) {
        return new File(root, channel() + '/' + version());
    }

    public boolean isPrimary() {
        // This is the 'primary' mapping and thus what we publish as the root artifacts.
        // Not as gradle module metadata only variants.
        // Basically the thing that looks like a normal maven artifact
        return true;
    }

    public Mappings withMCVersion(String version) {
        return new Mappings(channel(), version);
    }

    public Artifact getArtifact(MCPSide side) {
        //net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip
        var mcpVersion = side.getMCP().getName().getVersion();
        var mcVersion = side.getMCP().getConfig().version;
        var artifactVersion = mcpVersion;
        if (this.version != null && !mcVersion.equals(this.version))
            artifactVersion = mcpVersion + '-' + this.version;

        return Artifact.from(Constants.MC_GROUP, "mappings_" + this.channel, artifactVersion)
            .withExtension("zip");
    }

    public Task getCsvZip(MCPSide side) {
        var key = new Key(Tasks.CSVs, side);
        var ret = tasks.get(key);
        if (ret != null)
            return ret;

        var mc = side.getMCP().getMinecraftTasks();
        var srg = side.getTasks().getMappings();
        var client = mc.versionFile("client_mappings", "txt");
        var server = mc.versionFile("server_mappings", "txt");
        ret = Task.named("srg2names[" + this + ']',
            Task.deps(srg, client, server),
            () -> getMappings(side, srg, client, server)
        );
        tasks.put(key, ret);
        return ret;
    }

    public Task getMapped2Srg(MCPSide side) {
        return getTsrg(side, Tasks.MappedToSrg);
    }

    public Task getMapped2Obf(MCPSide side) {
        return getTsrg(side, Tasks.MappedToObf);
    }

    private Task getTsrg(MCPSide side, Tasks type) {
        var key = new Key(type, side);
        var ret = tasks.get(key);
        if (ret != null)
            return ret;

        var srg = side.getTasks().getMappings();
        var csv = getCsvZip(side);
        ret = Task.named(type.name + '[' + this + ']',
            Task.deps(srg, csv),
            () -> makeTsrg(side, srg, csv, type == Tasks.MappedToObf)
        );
        tasks.put(key, ret);
        return ret;
    }

    private File getMappings(MCPSide side, Task srgMappings, Task clientTask, Task serverTask) {
        var tool = side.getMCP().getCache().maven().download(Constants.INSTALLER_TOOLS);

        var root = getFolder(new File(side.getMCP().getBuildFolder(), "data/mapings"));
        var output = new File(root, "official.zip");
        var log = new File(root, "official.log");

        var mappings = srgMappings.execute();
        var client = clientTask.execute();
        var server = serverTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("mappings", mappings);
        cache.add("client", client);
        cache.add("server", server);

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        var args = List.of(
            "--task",
            "MAPPINGS_CSV",
            "--srg",
            mappings.getAbsolutePath(),
            "--client",
            client.getAbsolutePath(),
            "--server",
            server.getAbsolutePath(),
            "--output",
            output.getAbsolutePath()
        );

        File jdk;
        try {
            jdk = side.getMCP().getCache().jdks().get(Constants.INSTALLER_TOOLS_JAVA_VERSION);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + Constants.INSTALLER_TOOLS_JAVA_VERSION, e);
        }

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private File makeTsrg(MCPSide side, Task srgTask, Task csvTask, boolean toObf) {
        var root = getFolder(new File(side.getMCP().getBuildFolder(), "data/mapings"));
        var output = new File(root, channel() + '-' + version + '-' + (toObf ? "srg" : "obf") + ".tsrg.gz");

        var srg = srgTask.execute();
        var csv = csvTask.execute();

        var cache = HashStore.fromFile(output)
            .add("srg", srg)
            .add("csv", csv);

        if (output.exists() && cache.isSame())
            return output;

        try {
            var names = Mappings.load(csv).names();

            var map = IMappingFile.load(srg); // obf2srg
            if (!toObf)
                map = map.reverse().chain(map); // srg2obf + obf2srg = srg2srg

            // Now we rename target2mapped
            map = map.rename(new IRenamer() {
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

            // Write in reversed == mapped2target
            map.write(output.getAbsoluteFile().toPath(), IMappingFile.Format.TSRG2, true);
        } catch (IOException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }
}
