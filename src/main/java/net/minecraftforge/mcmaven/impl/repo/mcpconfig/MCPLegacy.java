/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import com.google.gson.reflect.TypeToken;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.JDKCache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.MCFile;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

public class MCPLegacy {
    public static Artifact artifact(String version) {
        return Artifact.from("de.oceanlabs.mcp", "mcp", version, "srg", "zip");
    }

    private final MCPConfigRepo repo;
    private final Artifact name;
    private final boolean isPython;
    private final String mcVersion;
    private final File data;
    private final String dataHash;
    private final File build;
    private final MinecraftTasks mcTasks;

    private final Map<String, Child> children = new HashMap<>();
    private final Map<String, Task> extracts = new HashMap<>();
    private final Task mappings;
    private final Task exceptorJson;
    private final Task exceptorConfig;
    private final Task statics;
    private final Task listLibraries;

    private Child child;

    public MCPLegacy(MCPConfigRepo repo, Artifact name, @Nullable String python) {
        this.repo = repo;
        this.isPython = python != null;
        this.mcVersion = name.getVersion();

        if (python != null) {
            this.name = name.withVersion(name.getVersion() + '-' + python.replace(".zip", "")).withClassifier(null);
            this.data = new File(this.repo.getCache().root(), "mcp/legacy/" + python);
            if (!this.data.exists()) {
                if (!DownloadUtils.tryDownloadFile(this.data, Constants.MCP_DOWNLOADS + python)) {
                    if (this.data.exists())
                        this.data.delete();
                    throw new IllegalStateException("Failed to download legacy MCP " + Constants.MCP_DOWNLOADS + python);
                }
            }
        } else {
            this.name = name;
            this.data = this.repo.getCache().maven().download(name);
            if (!this.data.exists())
                throw new IllegalStateException("Failed to download " + name);
        }

        this.dataHash = Util.sneak(() -> HashFunction.sha1().hash(this.data));

        this.build = new File(this.repo.getCache().root(), "mcp/" + this.name.getFolder());
        this.mcTasks = this.repo.getMCTasks(this.mcVersion); // Legacy stuff, there is no variant so use the full version

        // TODO: [Mavenizer] Maybe write a task to convert the old python to newer formated artifacts?
        var prefix = this.isPython ? "conf/" : "";
        this.mappings = extract(prefix + "joined.srg");
        this.exceptorJson = extract(prefix + "exceptor.json"); // Possibly non-existant?
        this.exceptorConfig = extract(prefix + "joined.exc");
        this.statics = extract(this.isPython ? "conf/STATIC_METHODS.txt" : "static_methods.txt"); // may not exist
        this.listLibraries = listLibraries();
    }

    public Artifact getName() {
        return this.name;
    }

    public MinecraftTasks getMinecraftTasks() {
        return this.mcTasks;
    }

    public Cache getCache() {
        return this.repo.getCache();
    }

    public File getBuildFolder() {
        return this.build;
    }

    public File getData() {
        return this.data;
    }

    public String getDataHash() {
        return this.dataHash;
    }

    public Task getMappings() {
        return this.mappings;
    }

    public Task getExceptorJson() {
        return this.exceptorJson;
    }

    public int getJavaTarget() {
        // The vanilla launcher uses Java 8 for all legacy versions
        // And the MCP archives have variant patches that deal with the decompile differences
        // Also, modern macs simply do not have java 5 & 6 distros. So we have to use java 8
        // My bulk testing works fine, I do not set the -target version when recompiling, Not
        // sure if I care to do that.
        /*
        var comp = new ComparableVersion(this.mcVersion);
        if (comp.compareTo(MC_1_6_1) < 0)
            return 5;
        if (comp.compareTo(MC_1_12) < 0)
            return 6;
        */
        return 8;
    }

    public List<File> getClasspath() {
        var classpath = new ArrayList<File>();
        for (var lib : this.getMinecraftTasks().getClientLibraries())
            classpath.add(lib.file());

        // They added annotations in 1.7.10 so we need null annotations
        var comp = new ComparableVersion(this.mcVersion);
        if (comp.compareTo(MC_1_7_10) >= 0) {
            var artifact = Artifact.from("com.google.code.findbugs:jsr305:1.3.9");
            classpath.add(this.getCache().maven().download(artifact));
        }

        return classpath;
    }

    public Child getChild() {
        if (this.child == null)
            this.child = new Child(this.build, null);
        return this.child;
    }

    public Child getChild(FG2Userdev forge) {
        var accessTransformer = forge.extract("merged_at.cfg").execute();
        var hash = Util.hash(HashFunction.sha1(), accessTransformer);
        var key = forge.getName().getName() + '/' + hash;
        var ret = this.children.get(key);
        if (ret == null) {
            var base = new File(this.build, key);
            ret = new Child(base, accessTransformer);
            this.children.put(key, ret);
        }
        return ret;
    }

    private String prefix() {
        return '[' + this.name.getVersion() + ']';
    }

    private Task extract(String name) {
        var ret = this.extracts.get(name);
        if (ret == null) {
            ret = Task.named(prefix() + "extract[" + name + ']', () -> extractImpl(name));
            this.extracts.put(name, ret);
        }
        return ret;
    }

    private File extractImpl(String name) {
        var target = new File(this.build, name);
        var cache = Util.cache(target)
            .addKnown("data", dataHash);

        if (Mavenizer.checkCache(target, cache))
            return target;

        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry(name);
            if (entry == null)
                throw except("Missing Data: " + name);

            FileUtils.ensureParent(target);

            try (var os = new FileOutputStream(target)) {
                zip.getInputStream(entry).transferTo(os);
            }

            target.setLastModified(entry.getLastModifiedTime().toMillis());

            cache.save();
            return target;
        } catch (IOException e) {
            throw except("Failed to extract `" + name + '`', e);
        }
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid MCP Dependency: " + this.name + " - " + message);
    }
    private RuntimeException except(String message, Throwable t) {
        return new IllegalArgumentException("Invalid MCP Dependency: " + this.name + " - " + message, t);
    }

    private Task listLibraries() {
        var json = this.getMinecraftTasks().versionJson;
        var output = new File(this.build, "libraries.txt");
        var maven = this.repo.getCache().minecraft();
        return Task.named(prefix() + "list-libraries",
            Task.deps(json), () -> MCPTaskFactory.listLibraries(json, output, maven)
        );
    }

    private Task exceptorConfig() {
        var base = this.exceptorConfig;
        var statics = this.statics;
        var mappings = getMappings();
        return Task.named(prefix() + "exceptor-config",
            Task.deps(base, statics, mappings), () ->
                exceptorConfig(base.execute(), statics.execute(), mappings.execute())
        );
    }

    private File exceptorConfig(File base, File statics, File mappings) {
        var output = new File(this.build, "exceptor.exc");
        var cache = Util.cache(output)
            .add("base", base)
            .add("statics", statics)
            .add("mappings", mappings);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var excMap = new HashMap<String, String>();
        try (var reader = new BufferedReader(new FileReader(base, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#"))
                    excMap.put(line, null);
                else {
                    var pts = line.split("=", 2);
                    excMap.put(pts[0], pts[1]);
                }
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        var staticsSet = new HashSet<String>();
        try (var reader = new BufferedReader(new FileReader(statics, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; )
                staticsSet.add(line);
        } catch (IOException e) {
            return Util.sneak(e);
        }

        // Generate default exc lines from srg
        try {
            var map = IMappingFile.load(mappings);
            for (var cls : map.getClasses()) {
                for (var mtd : cls.getMethods()) {
                    var name = mtd.getMapped();
                    if (!name.startsWith("func_"))
                        continue;

                    var prefix = "p_" + name.split("_", 3)[1];
                    var args = new ArrayList<String>();
                    int idx = staticsSet.contains(name) ? 0 : 1; // Static methods don't have 'this'

                    var chrs = mtd.getDescriptor().toCharArray();
                    for (int x = 1; x < chrs.length; x++) {
                        var c = chrs[x];
                        if (c == ')')
                            break;

                        // its prefix + bytecode index, so need to double increment for doubles/longs
                        args.add(prefix + "_" + idx++ + "_");
                        switch (c) {
                            case '[': // Arrays
                                while (x < chrs.length && chrs[x] == '[')
                                    x++;
                                if (chrs[x] == 'L') {
                                    while (x < chrs.length && chrs[x] != ';')
                                        x++;
                                }
                                break;
                            case 'D': // double
                            case 'J': // long
                                idx++;
                                break;
                            case 'L': // object
                                while (x < chrs.length && chrs[x] != ';')
                                    x++;
                        }
                    }

                    if (!args.isEmpty()) {
                        var key = cls.getMapped() + "." + name + mtd.getMappedDescriptor();
                        var info = excMap.get(key);
                        if (info == null)
                            info = "";
                        else {
                            var offset = info.indexOf('|');
                            if (offset != -1)
                                info = info.substring(0, offset);
                        }
                        excMap.put(key, info + '|' + String.join(",", args));
                    }
                }
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        FileUtils.ensureParent(output);
        try (var out = new BufferedWriter(new FileWriter(output, StandardCharsets.UTF_8))) {
            var keys = new ArrayList<>(excMap.keySet());
            Collections.sort(keys);

            for (var key : keys) {
                out.write(key);
                var value = excMap.get(key);
                if (value != null) {
                    out.write('=');
                    out.write(value);
                }
                out.write('\n');
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        cache.save();
        return output;
    }

    private Task rename() {
        var merged = merge();
        var mappings = this.getMappings();
        return Task.named(prefix() + "rename",
            Task.deps(merged, mappings), () -> this.rename(merged.execute(), mappings.execute())
        );
    }

    // We used to use SpecialSource, but im going to try with renamer, it should be fine...
    private File rename(File input, File mappings) {
        var tool = this.repo.getCache().maven().download(Constants.RENAMER);
        var output = new File(this.build, "joined-renamed.jar");
        var log = new File(this.build, "joined-renamed.log");
        var cache = Util.cache(output)
            .add("tool", tool)
            .add("input", input)
            .add("mappings", mappings);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var args = List.of(
            "--input", input.getAbsolutePath(),
            "--output", output.getAbsolutePath(),
            "--map", mappings.getAbsolutePath(),
            "--disable-abstract-param" // this is taken care of by the cleanup task
        );

        var jdk = this.repo.getCache().jdks().tryGet(Constants.RENAMER_JAVA_VERSION);
        var ret = ProcessUtils.runJar(jdk, this.build, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run Renamer (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private Task merge() {
        var client = this.getMinecraftTasks().versionFile(MCFile.CLIENT_JAR);
        var server = splitServer();
        return Task.named(prefix() + "merge",
            Task.deps(client, server), () -> this.merge(client, server)
        );
    }

    private File merge(Task clientTask, Task serverTask) {
        var tool = this.repo.getCache().maven().download(Constants.LEGACY_MERGETOOL);
        var client = clientTask.execute();
        var server = serverTask.execute();
        var output = new File(this.build, "joined.jar");
        var log = new File(this.build, "joined.log");
        var cache = Util.cache(output)
            .add("tool", tool)
            .add("client", client)
            .add("server", server);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var args = List.of(
            "--merge",
            "--client", client.getAbsolutePath(),
            "--server", server.getAbsolutePath(),
            "--output", output.getAbsolutePath(),
            "--inject",
            "--ann", this.mcVersion, // Pick based on MC version
            //"--ann", "NMF", // net.minecraftforge.fml.relauncher
            "--keep-data"
        );

        var jdk = this.repo.getCache().jdks().tryGet(Constants.LEGACY_MERGETOOL_JAVA_VERSION);
        var ret = ProcessUtils.runJar(jdk, this.build, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MergeTool (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private Task splitServer() {
        var input = this.getMinecraftTasks().extractServer();
        return Task.named(prefix() + "splitServer",
            Task.deps(input), () -> this.splitServer(input.execute())
        );
    }

    private static final String[] LEGACY_SPLIT_SERVER = new String[] {
        "org/bouncycastle/",
        "org/apache/",
        "com/google/",
        "com/mojang/authlib/",
        "com/mojang/util/",
        "gnu/trove/",
        "io/netty/",
        "javax/annotation/",
        "argo/",
        "it/"
    };
    private static final String LEGACY_SPLIT_SERVER_HASH = HashFunction.sha1().hash(String.join("\0", LEGACY_SPLIT_SERVER));
    /// Typically we would want to split the server based on the whitelist from the mapping file
    /// But I haven't written the unit tests to verify that all the old versions are correct with that
    /// And FG2 just uses wildcards.
    /// So i'm being lazy and just copying the whildcards
    private File splitServer(File input) {
        var output = new File(this.build, "server-split.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .addKnown("filter", LEGACY_SPLIT_SERVER_HASH);

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);

        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                boolean filtered = false;
                for (var filter : LEGACY_SPLIT_SERVER) {
                    if (entry.getName().startsWith(filter)) {
                        filtered = true;
                        break;
                    }
                }
                if (filtered)
                    continue;

                var newEntry = new ZipEntry(entry.getName());
                newEntry.setSize(entry.getSize());
                newEntry.setTime(entry.getTime());
                zout.putNextEntry(newEntry);
                zin.transferTo(zout);
                zout.closeEntry();
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        cache.save();
        return output;
    }

    public class Child {
        private final MavenCache maven;
        private final JDKCache jdks;
        private final File build;
        private final File accessTransformer;
        private final String prefix;
        private final MCPCfg cfg;
        private final Task inject;

        public Child(File build, File accessTransformer) {
            this.maven = MCPLegacy.this.repo.getCache().maven();
            this.jdks = MCPLegacy.this.repo.getCache().jdks();
            this.build = build;
            this.accessTransformer = accessTransformer;
            this.prefix = MCPLegacy.this.prefix();
            this.cfg = MCPCfg.get(MCPLegacy.this.mcVersion);
            this.inject = mcpInject();
        }

        public Task getFinalStep() {
            return this.inject;
        }

        private Task mcpInject() {
            var input = cleanup();
            return Task.named(prefix + "mcp-inject",
                Task.deps(input), () -> mcpInject(input.execute())
            );
        }

        private File mcpInject(File input) {
            var output = new File(this.build, "mcp-inject.jar");
            var cache = Util.cache(output)
                .add("input", input)
                .addKnown("mcp", MCPLegacy.this.dataHash);

            if (Mavenizer.checkCache(output, cache))
                return output;

            FileUtils.ensureParent(output);
            if (output.exists())
                output.delete();

            try (var zout = new ZipOutputStream(new FileOutputStream(output))) {

                var packages = new HashSet<String>();
                // Copy everything from one jar to the other, gathering java packages
                try (var zin = new ZipInputStream(new FileInputStream(input))) {
                    for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                        var name = entry.getName();

                        var newEntry = new ZipEntry(name);
                        newEntry.setTime(entry.getTime());
                        zout.putNextEntry(newEntry);
                        zin.transferTo(zout);
                        zout.closeEntry();

                        if (name.endsWith(".java")) {
                            var idx = name.lastIndexOf('/');
                            packages.add(name.substring(0, idx));
                        }
                    }
                }

                var prefix = MCPLegacy.this.isPython ? "conf/patches/inject/" : "patches/inject/";
                try (var zin = new ZipInputStream(new FileInputStream(MCPLegacy.this.data))) {
                    for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                        if ((prefix + "package-info-template.java").equals(entry.getName())) {
                            var template = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                            for (var pkg : packages) {
                                var name = pkg + "/package-info.java";
                                zout.putNextEntry(new ZipEntry(FileUtils.getStableEntry(name)));
                                zout.write(template.replaceAll("\\{PACKAGE\\}", pkg.replace('/', '.')).getBytes(StandardCharsets.UTF_8));
                                zout.closeEntry();
                            }
                        } else if (entry.getName().startsWith(prefix + "common/")) {
                            if (entry.isDirectory())
                                continue;
                            var name = entry.getName().substring(22);
                            zout.putNextEntry(new ZipEntry(FileUtils.getStableEntry(name)));
                            zin.transferTo(zout);
                            zout.closeEntry();
                        }
                    }
                }
            } catch (IOException e) {
                return Util.sneak(e);
            }

            cache.save();
            return output;
        }

        private Task cleanup() {
            var input = this.decompile();
            return Task.named(prefix + "cleanup",
                Task.deps(input), () -> cleanup(input.execute())
            );
        }

        private File cleanup(File input) {
            var cleanup = this.cfg.cleanup;
            var tool = maven.download(cleanup.artifact);
            var output = new File(this.build, "cleaned.jar");
            var log = new File(this.build, "cleaned.log");
            var cache = Util.cache(output)
                .add("tool", tool)
                .add("input", input)
                .add("args", String.join(", ", cleanup.args))
                .addKnown("mcp", MCPLegacy.this.dataHash);

            if (Mavenizer.checkCache(output, cache))
                return output;

            var args = new ArrayList<String>(List.of(
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--patches", MCPLegacy.this.data.getAbsolutePath(),
                "--patches-prefix", (MCPLegacy.this.isPython ? "conf/" : "") + "patches/minecraft_merged_ff/"
            ));
            /*
            if (MCPLegacy.this.child != this) // We're on a Forge version, so pull out the side annotations and inject it later
                args.add("--filter-fml");
            */
            args.addAll(cleanup.args);

            var jdk = jdks.tryGet(Constants.MCPCLEANUP_JAVA_VERSION);
            var ret = ProcessUtils.runJar(jdk, this.build, log, tool, List.of(), args);
            if (ret.exitCode != 0)
                throw new IllegalStateException("Failed to run MCPCleanup (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

            cache.save();
            return output;
        }

        private Task decompile() {
            var input = this.exceptor();
            var libraries = MCPLegacy.this.listLibraries;
            return Task.named(prefix + "decompile",
                Task.deps(input, libraries), () -> decompile(input.execute(), libraries.execute())
            );
        }

        private File decompile(File input, File libraries) {
            var output = new File(this.build, "decompiled.jar");
            var log = new File(this.build, "decompiled.log");

            var decompiler = this.cfg.decompiler;

            var tool = maven.download(decompiler.artifact);

            int java_version = MCPLegacy.this.getJavaTarget();

            var cache = Util.cache(output)
                .add("input", input)
                .add("tool", tool)
                .add("args", String.join(", ", decompiler.args))
                .addKnown("java", Integer.toString(java_version));

            if (Mavenizer.checkCache(output, cache))
                return output;

            var jvm = List.of("-Xmx3G"); // This is what FG2.3 has, older versions have 512M, we're on modern machines 3G should be fine

            var temp = output;
            List<String> run;
            if (decompiler.legacy) {
                // Older versions don't support directly outputting to a single jar file, they require a directory and will output to dir\{input_file_name}
                var dir = new File(this.build, "decompile-legacy-temp");
                if (!dir.exists())
                    dir.mkdir();
                temp = new File(dir, input.getName());

                run = new ArrayList<String>(decompiler.args.size());
                for (var arg : decompiler.args) {
                    if ("{libraries}".equals(arg)) {
                        // This is a version that doens't support the -cfg arg
                        // So we have to specify all the libraries via command line
                        // But this could easily explode to more then the max command size
                        // So we need to move all libraries into a common directory and use relative names
                        var workDir = log.getParentFile();
                        var libsDir = new File(workDir, "decompile-libs");
                        try {
                            var libs = Files.readAllLines(libraries.toPath());
                            if (!libsDir.exists())
                                libsDir.mkdirs();
                            for (var lib : libs) {
                                if (lib.startsWith("-e=")) {
                                    var source = new File(lib.substring(3));
                                    var target = new File(libsDir, source.getName());
                                    if (!target.exists()) // This doesn't check hashes, but IDGF
                                        Files.copy(source.toPath(), target.toPath());
                                    run.add("-e=decompile-libs/" + target.getName());
                                } else {
                                    run.add(lib); // Shouldn't happen, but just in case
                                }
                            }
                        } catch (IOException e) {
                            return Util.sneak(e);
                        }
                    } else {
                        run.add(arg);
                    }
                }
                run.add(input.getAbsolutePath());
                run.add(dir.getAbsolutePath());
            } else {
                run = new ArrayList<String>(decompiler.args);
                run.addAll(List.of(
                    "-cfg", libraries.getAbsolutePath(),
                    input.getAbsolutePath(),
                    output.getAbsolutePath()
                ));
            }

            if (temp.exists())
                temp.delete();

            var jdk = jdks.tryGet(java_version);
            var ret = StupidHacks.runDecompiler(jdk, log, tool, jvm, run);
            if (ret.exitCode != 0 || !temp.exists())
                throw new IllegalStateException("Failed to run decompiler (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

            // Move the decompiled code to the output file if we're using legacy code
            if (temp != output) {
                try {
                    Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    return Util.sneak(e);
                }
            }

            cache.save();
            return output;
        }

        private Task exceptor() {
            var input = this.modifyAccess();
            var config = MCPLegacy.this.exceptorConfig();
            var json = this.cfg.exceptor.json ? this.modifyExceptorAccess(input) : null;
            return Task.named(prefix + "exceptor",
                Task.deps(input, config, json), () -> this.exceptor(input.execute(), config.execute(), json)
            );
        }

        // We used to use SpecialSource, but im going to try with renamer, it should be fine...
        private File exceptor(File input, File config, Task jsonTask) {
            var except = this.cfg.exceptor;
            var json = jsonTask == null ? null : jsonTask.execute();
            var tool = maven.download(Constants.MCINJECTOR);
            var output = new File(this.build, "joined-mci.jar");
            var log = new File(this.build, "joined-mci.log");
            var internalLog = new File(this.build, "joined-mci-internal.log");
            var cache = Util.cache(output)
                .add("tool", tool)
                .add("input", input)
                .add("config", config);
            if (json != null)
                cache.add("json", json);

            if (Mavenizer.checkCache(output, cache))
                return output;

            var args = new ArrayList<>(List.of(
                "--jarIn", input.getAbsolutePath(),
                "--jarOut", output.getAbsolutePath(),
                "--mapIn", config.getAbsolutePath(),
                "--log", internalLog.getAbsolutePath(), // Annoying but we have to have two logs to actually have the logs be written
                "--generateParams"
            ));
            if (json != null)
                args.addAll(List.of("--jsonIn", json.getAbsolutePath()));
            if (except.markers)
                args.add("--applyMarkers");
            if (except.lvt)
                args.addAll(List.of("--lvt", "LVT"));

            var jdk = jdks.tryGet(Constants.MCINJECTOR_JAVA_VERSION);
            var ret = ProcessUtils.runJar(jdk, this.build, log, tool, Collections.emptyList(), args);
            if (ret.exitCode != 0)
                throw new IllegalStateException("Failed to run MCInjector (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

            internalLog.delete(); // this is a duplicate file, everything is in the main log

            cache.save();
            return output;
        }

        private Task modifyExceptorAccess(Task inputTask) {
            var config = MCPLegacy.this.exceptorJson;
            return Task.named("modifyExceptorAccess",
                Task.deps(inputTask, config), () -> this.modifyExceptorAccess(inputTask.execute(), config.execute())
            );
        }

        private File modifyExceptorAccess(File input, File config) {
            var output = new File(this.build, "exceptor-ated.json");
            var cache = Util.cache(output)
                .add("input", input)
                .add("config", config);

            if (this.accessTransformer != null)
                cache.add("at", this.accessTransformer);

            if (Mavenizer.checkCache(output, cache))
                return output;

            try {
                var struct = JsonData.fromJson(config, MCI_CONFIG);

                // Fix the inner class access that we modify
                if (this.accessTransformer != null) {
                    for (var line : Files.readAllLines(this.accessTransformer.toPath(), StandardCharsets.UTF_8)) {
                        var idx = line.indexOf('#');
                        if (idx != -1)
                            line = line.substring(0, idx);
                        line = line.trim().replace('.', '/');
                        if (line.isEmpty())
                            continue;

                        var pts = line.split(" ");
                        idx = pts[1].indexOf('$');
                        if (pts.length != 2 || idx == -1)
                            continue;

                        var access = pts[0];
                        var cls = pts[1];

                        var parent = pts[1].substring(0, idx);
                        for (var cfg : new MCInjectorConfig[] { struct.get(parent), struct.get(pts[1]) }) {
                            if (cfg == null || cfg.innerClasses == null)
                                continue;
                            for (var inner : cfg.innerClasses) {
                                if (cls.equals(inner.inner_class))
                                    inner.fixAccess(access);
                            }
                        }
                    }
                }

                // remove any inner classes that doesn't exist.. Not sure why we do this but old code is old
                try (var zip = new ZipFile(input)) {
                    for (var itr = struct.entrySet().iterator(); itr.hasNext(); ) {
                        var entry = itr.next();
                        var outerName = entry.getKey();
                        var cfg = entry.getValue();

                        if (zip.getEntry(outerName + ".class") == null) {
                            itr.remove();
                            continue;
                        }

                        if (cfg.innerClasses == null)
                            continue;

                        for (var inner = cfg.innerClasses.iterator(); inner.hasNext(); ) {
                            var innerName = inner.next().inner_class;
                            if (zip.getEntry(innerName + ".class") == null)
                                inner.remove();
                        }
                    }
                }

                JsonData.toJson(struct, output);
                cache.save();
                return output;
            } catch (IOException e) {
                return Util.sneak(e);
            }
        }

        private Task modifyAccess() {
            var renamed = MCPLegacy.this.rename();
            if (this.accessTransformer == null)
                return renamed;
            return Task.named(prefix + "modifyAccess",
                Task.deps(renamed), () -> this.modifyAccess(renamed.execute())
            );
        }

        private File modifyAccess(File input) {
            var tool = maven.download(Constants.ACCESS_TRANSFORMER);
            var output = new File(this.build, "modify-access.jar");
            var log = new File(this.build, "modify-access.log");
            var cache = Util.cache(output)
                .add("tool", tool)
                .add("input", input)
                .add("config", this.accessTransformer);

            if (Mavenizer.checkCache(output, cache))
                return output;

            var args = List.of(
                "--inJar", input.getAbsolutePath(),
                "--outJar", output.getAbsolutePath(),
                "--atfile", this.accessTransformer.getAbsolutePath()
            );

            var jdk = jdks.tryGet(Constants.ACCESS_TRANSFORMER_JAVA_VERSION);
            var ret = ProcessUtils.runJar(jdk, this.build, log, tool, Collections.emptyList(), args);
            if (ret.exitCode != 0)
                throw new IllegalStateException("Failed to run Access Transformer (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

            cache.save();
            return output;
        }
    }

    private static final TypeToken<LinkedHashMap<String, MCInjectorConfig>> MCI_CONFIG = new TypeToken<>() {};
    public record MCInjectorConfig(@Nullable EnclosingMethod enclosingMethod, @Nullable ArrayList<InnerClass> innerClasses) {
        public record EnclosingMethod(String desc, String name, String owner) { }
        public static class InnerClass {
            public @Nullable String access;
            public String inner_class;
            public String inner_name;
            public String outer_class;
            public @Nullable String start;

            private static final int ACC_DEFAULT = 0x0000;
            private static final int ACC_PUBLIC = 0x0001;
            private static final int ACC_PRIVATE = 0x0002;
            private static final int ACC_PROTECTED = 0x0004;
            private static final int ACC_FINAL = 0x0010;

            void fixAccess(String wanted) {
                int access = this.access == null ? 0 : Integer.parseInt(this.access, 16);
                int ret = access & ~7;
                int t = 0;

                if (wanted.startsWith("public"))
                    t = ACC_PUBLIC;
                else if (wanted.startsWith("private"))
                    t = ACC_PRIVATE;
                else if (wanted.startsWith("protected"))
                    t = ACC_PROTECTED;

                ret |= switch (access & 7) {
                    case ACC_PRIVATE   -> t;
                    case ACC_DEFAULT   -> (t != ACC_PRIVATE ? t : ACC_DEFAULT);
                    case ACC_PROTECTED -> (t != ACC_PRIVATE && t != ACC_DEFAULT ? t : ACC_PROTECTED);
                    case ACC_PUBLIC    -> ACC_PUBLIC;
                    default            -> access & 7;
                };

                if (wanted.endsWith("-f"))
                    ret &= ~ACC_FINAL;
                else if (wanted.endsWith("+f"))
                    ret |= ACC_FINAL;
                this.access = ret == 0 ? null : Integer.toHexString(ret);
            }
        }
    }

    // The main difference between FG versions was the decompiler we invoked/embeded
    private static final ComparableVersion MC_1_6_1  = new ComparableVersion("1.6.1");
    private static final ComparableVersion MC_1_7_10 = new ComparableVersion("1.7.10");
    private static final ComparableVersion MC_1_8_8  = new ComparableVersion("1.8.8");
    private static final ComparableVersion MC_1_9    = new ComparableVersion("1.9");
    private static final ComparableVersion MC_1_9_4  = new ComparableVersion("1.9.4");
    private static final ComparableVersion MC_1_12   = new ComparableVersion("1.12");

    private record Exceptor(boolean json, boolean markers, boolean lvt) {
        private static Exceptor get(ComparableVersion mc) {
            var json = true;
            var markers = true;
            var lvt = false;
            if (mc.compareTo(MC_1_8_8) >= 0) {
                json = false;
                lvt = true;
            }
            if (mc.compareTo(MC_1_9) >= 0)
                markers = false;
            return new Exceptor(json, markers, lvt);
        }
    }
    private record Cleanup(Artifact artifact, List<String> args) {
        private static Cleanup get(String mc) {
            var args = List.<String>of();
            if ("1.8".equals(mc))
                args = List.of("--fix-generic-params", "--fml", "--fernflower",
                    // These patches are enum sythetic arg fixes which cleaup does in a generic way
                    "--ignore-patch", "net/minecraft/network/EnumConnectionState.java",
                    "--ignore-patch", "net/minecraft/util/EnumChatFormatting.java",
                    "--ignore-patch", "net/minecraft/util/EnumParticleTypes.java"
                );
            else if ("1.8.8".equals(mc))
                args = List.of("--fix-generic-params");//, "--fernflower", "--enum-args=false", "--enum-constructors=false");
            return new Cleanup(Constants.MCPCLEANUP, args);
        }
    }
    private record Decompiler(Artifact artifact, boolean legacy, List<String> args) {

        private static final List<String> DECOMPILER_ARGS = List.of(
            "-din=1", // DECOMPILE_INNER
            "-dgs=1", // DECOMPILE_GENERIC_SIGNATURES
            "-asc=1", // ASCII_STRING_CHARACTERS
            "-iec=1", // INCLUDE_ENTIRE_CLASSPATH
            "-rsy=1", // REMOVE_SYNTHETIC
            "-rbr=1", // REMOVE_BRIDGE
            "-lit=0", // LITERALS_AS_IS
            // UNIT_TEST_MODE = 0
            "-mpm=0", // MAX_PROCESSING_METHOD
            //"-jvn=1", // Use JAD Renamer, FG3 has an 'advanced' renamer which renames abstract names, but we can do that in MCPCleanup
            "--var-renamer", "mcp", // This is the 'Advanced' renamer from FG3
            "--disable-buffer-casts" // Disables the java9 fixes for Buffer methods, it should be fine because we recompile with java 8.
        );
        private static final List<String> DECOMPILER_ARGS_18 = List.of(
            "-din=1", // DECOMPILE_INNER
            "-rbr=0", // REMOVE_BRIDGE
            "-dgs=1", // DECOMPILE_GENERIC_SIGNATURES
            "-asc=1", // ASCII_STRING_CHARACTERS
            "-log=WARN"
        );
        private static final List<String> DECOMPILER_ARGS_188 = List.of(
            "-din=1", // DECOMPILE_INNER
            "-rbr=1", // REMOVE_BRIDGE
            "-dgs=1", // DECOMPILE_GENERIC_SIGNATURES
            "-asc=1", // ASCII_STRING_CHARACTERS
            "-rsy=1", // REMOVE_SYNTHETIC
            "-iec=1", // INCLUDE_ENTIRE_CLASSPATH
            "-jvn=1", // Use JAD Renamer
            "-log=WARN",
            "{libraries}" // Will be expanded to a list of all libraries using -e=
        );
        private static Decompiler get(ComparableVersion mc) {
            var decompTool = Constants.FERNFLOWER_FG_2_0_LEGACY;
            var decompArgs = DECOMPILER_ARGS_188;
            var legacy = true;

            if (mc.compareTo(MC_1_12) >= 0) {
                decompTool = Constants.FERNFLOWER_FG_2_3;
                decompArgs = DECOMPILER_ARGS;
                legacy = false;
            } else if (mc.compareTo(MC_1_8_8) >= 0) {
                decompTool = Constants.FERNFLOWER_FG_2_2;
                decompArgs = DECOMPILER_ARGS;
                legacy = false;
            }
            /*
            else if (mc.compareTo(MC_1_9_4) >= 0)
                decompTool = Constants.FERNFLOWER_FG_2_0_194;
            else if (mc.compareTo(MC_1_8_8) >= 0)
                decompTool = Constants.FERNFLOWER_FG_2_0_194;
            */
            else { // 1.8
                decompArgs = DECOMPILER_ARGS_18;
            }
            return new Decompiler(decompTool, legacy, decompArgs);
        }
    }
    private record MCPCfg(
        Exceptor exceptor,
        Cleanup cleanup,
        Decompiler decompiler
    ) {
        private static MCPCfg get(String mc) {
            var comp = new ComparableVersion(mc);
            var except = Exceptor.get(comp);
            var cleanup = Cleanup.get(mc);
            var decomp = Decompiler.get(comp);
            return new MCPCfg(except, cleanup, decomp);
        }
    }
}
