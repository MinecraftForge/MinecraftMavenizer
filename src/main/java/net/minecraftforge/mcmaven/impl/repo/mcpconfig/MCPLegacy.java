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
import java.io.InputStreamReader;
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
import net.minecraftforge.mcmaven.impl.repo.forge.FGVersion;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.MCFile;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.NewLineDetector;
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
    private final ComparableVersion mcVersionComp;
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
        this.mcVersionComp = new ComparableVersion(this.mcVersion);

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
            var fixed = StupidHacks.fixMCPArtifact(name);
            this.data = this.repo.getCache().maven().download(fixed);
            if (!this.data.exists())
                throw new IllegalStateException("Failed to download " + fixed);
        }

        this.dataHash = Util.sneak(() -> HashFunction.sha1().hash(this.data));

        this.build = new File(this.repo.getCache().root(), "mcp/" + this.name.getFolder());
        this.mcTasks = this.repo.getMCTasks(this.mcVersion); // Legacy stuff, there is no variant so use the full version

        // TODO: [Mavenizer] Maybe write a task to convert the old python to newer formated artifacts?
        var prefix = this.isPython ? "conf/" : "";
        this.mappings = extract(prefix + "joined.srg");
        this.exceptorJson = extract(prefix + "exceptor.json");
        this.exceptorConfig = extract(prefix + "joined.exc");

        if (this.mcVersionComp.compareTo(MC_1_8) < 0)
            this.statics = null;
        else
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

    public int getJavaTarget() {
        // The vanilla launcher uses Java 8 for all legacy versions
        // And the MCP archives have variant patches that deal with the decompile differences
        // Also, modern macs simply do not have java 5 & 6 distros. So we have to use java 8
        // My bulk testing works fine, I do not set the -target version when recompiling, Not
        // sure if I care to do that.
        var comp = new ComparableVersion(this.mcVersion);
        if (comp.compareTo(MC_1_6_1) < 0)
            return 5;
        if (comp.compareTo(MC_1_12) < 0)
            return 6;
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
            this.child = new Child(this.build, null, null);
        return this.child;
    }

    public Child getChild(FG2Userdev forge, Task atTask, @Nullable FGVersion legacyFG) {
        var accessTransformer = atTask.execute();
        var hash = Util.hash(HashFunction.sha1(), accessTransformer);

        var key = forge.getName().getName();
        if (legacyFG != null)
            key += '-' + legacyFG.toString();
        key += '/' + hash;

        var ret = this.children.get(key);
        if (ret == null) {
            var base = new File(this.build, key);
            ret = new Child(base, accessTransformer, legacyFG);
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

        FileUtils.ensureParent(target);

        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry(name);
            if (entry == null)
                throw except("Missing Data: " + name);

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
        if (this.statics == null)
            return base;
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
        private final @Nullable FGVersion legacyFG;
        private final String prefix;
        private final MCPCfg cfg;
        private final Task inject;

        public Child(File build, File accessTransformer, @Nullable FGVersion legacyFG) {
            this.maven = MCPLegacy.this.repo.getCache().maven();
            this.jdks = MCPLegacy.this.repo.getCache().jdks();
            this.build = build;
            this.accessTransformer = accessTransformer;
            this.legacyFG = legacyFG;
            this.prefix = MCPLegacy.this.prefix();
            this.cfg = MCPCfg.get(MCPLegacy.this.mcVersionComp, legacyFG);
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

        private Task writeSorts() {
            if (this.cfg.decompiler.sorts == null)
                return null;
            return Task.named(prefix + "sort", () -> writeSortsImpl());
        }

        private File writeSortsImpl() {
            var output = new File(this.build, "sorts.cfg");
            var sorts = new StringBuilder();
            for (var sort : this.cfg.decompiler.sorts) {
                sorts.append("-sort=")
                    .append(sort)
                    .append('\n');
            }
            var cache = Util.cache(output)
                .add("sorts", sorts.toString());

            if (Mavenizer.checkCache(output, cache))
                return output;

            FileUtils.ensureParent(output);
            try {
                Files.writeString(output.toPath(), sorts);
            } catch (IOException e) {
                return Util.sneak(e);
            }

            cache.save();
            return output;
        }

        private Task decompile() {
            var input = this.exceptor();
            var libraries = MCPLegacy.this.listLibraries;
            var sorts = this.writeSorts();
            return Task.named(prefix + "decompile",
                Task.deps(input, libraries, sorts), () -> decompile(input.execute(), libraries.execute(), sorts == null ? null : sorts.execute())
            );
        }

        private File decompile(File input, File libraries, File sorts) {
            var output = new File(this.build, "decompiled.jar");
            var log = new File(this.build, "decompiled.log");

            var decompiler = this.cfg.decompiler;

            var tool = maven.download(decompiler.artifact);

            int java_version = MCPLegacy.this.getJavaTarget();
            // Mac OSX doesn't have Java below 6, so we must use 8.
            if (java_version < 8)
                java_version = 8;

            var cache = Util.cache(output)
                .add("input", input)
                .add("tool", tool)
                .add("args", String.join(", ", decompiler.args))
                .addKnown("java", Integer.toString(java_version));

            if (sorts != null)
                cache.add("sorts", sorts);

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
                if (sorts != null)
                    run.addAll(List.of("-cfg", sorts.getAbsolutePath()));

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
                if (sorts != null)
                    run.addAll(List.of("-cfg", sorts.getAbsolutePath()));
                run.addAll(List.of(
                    "-cfg", libraries.getAbsolutePath(),
                    input.getAbsolutePath(),
                    output.getAbsolutePath()
                ));
            }

            if (temp.exists())
                temp.delete();

            var jdk = jdks.tryGet(java_version);
            //jdk = new File("C:\\Program Files\\java\\jdk1.7.0_80");
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
            var customArgs = new ArrayList<String>();
            if (except.generate)
                customArgs.add("--generateParams");
            if (except.markers)
                customArgs.add("--applyMarkers");
            if (except.lvt)
                customArgs.addAll(List.of("--lvt", "LVT"));

            var cache = Util.cache(output)
                .add("tool", tool)
                .add("input", input)
                .add("config", config)
                .add("args", String.join(", ", customArgs));
            if (json != null)
                cache.add("json", json);

            if (Mavenizer.checkCache(output, cache))
                return output;

            var args = new ArrayList<>(List.of(
                "--jarIn", input.getAbsolutePath(),
                "--jarOut", output.getAbsolutePath(),
                "--mapIn", config.getAbsolutePath(),
                "--log", internalLog.getAbsolutePath() // Annoying but we have to have two logs to actually have the logs be written
            ));
            args.addAll(customArgs);
            if (json != null)
                args.addAll(List.of("--jsonIn", json.getAbsolutePath()));

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

            // Obfed access transformers, we need to remap them
            var config = MCPLegacy.this.mcVersionComp.compareTo(MC_1_6_4) <= 0
                ? renameAts()
                : null;

            return Task.named(prefix + "modifyAccess",
                Task.deps(renamed, config), () -> this.modifyAccess(renamed.execute(), config == null ? null : config.execute())
            );
        }

        private File modifyAccess(File input, @Nullable File config) {
            var tool = maven.download(Constants.ACCESS_TRANSFORMER);
            var output = new File(this.build, "modify-access.jar");
            var log = new File(this.build, "modify-access.log");
            if (config == null)
                config = this.accessTransformer;

            var cache = Util.cache(output)
                .add("tool", tool)
                .add("input", input)
                .add("config", config);

            if (Mavenizer.checkCache(output, cache))
                return output;

            var args = List.of(
                "--inJar", input.getAbsolutePath(),
                "--outJar", output.getAbsolutePath(),
                "--atfile", config.getAbsolutePath(),
                "--ignore-invalid" // Needed for some old Forge versions with invalid lines
            );

            var jdk = jdks.tryGet(Constants.ACCESS_TRANSFORMER_JAVA_VERSION);
            var ret = ProcessUtils.runJar(jdk, this.build, log, tool, Collections.emptyList(), args);
            if (ret.exitCode != 0)
                throw new IllegalStateException("Failed to run Access Transformer (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

            cache.save();
            return output;
        }

        private Task renameAts() {
            var mappings = MCPLegacy.this.getMappings();

            return Task.named(prefix + "renameAts",
                Task.deps(mappings), () -> this.renameAts(mappings.execute())
            );
        }

        // Obfusciated access transformers for 1.6.4 and below
        // Uses an old format that AccessTransformer doesn't support, so I transform it here, fairly easy to do
        // https://github.com/MinecraftForge/FML/blob/f1b3381e61fac1a0ae90f521223c6bc613eb4888/common/cpw/mods/fml/common/asm/transformers/AccessTransformer.java#L120
        //
        // If we reworked the process to apply ATs before renaming, then all that really needs to be done is to replace the first . with a space
        // But This is simple enough to do and should work
        private File renameAts(File mappings) {
            var output = new File(this.build, "renamed-at.cfg");
            var cache = Util.cache(output)
                .add("input", this.accessTransformer)
                .add("mappings", mappings);

            if (Mavenizer.checkCache(output, cache))
                return output;

            FileUtils.ensureParent(output);
            try (var out = new FileOutputStream(output);
                 var inf = new FileInputStream(this.accessTransformer)
            ) {
                var map = IMappingFile.load(mappings);
                var reader = new NewLineDetector(new InputStreamReader(inf));

                String line;
                while ((line = reader.readLine()) != null) {
                    String comment = null;
                    int idx = line.indexOf('#');
                    if (idx != -1) {
                        comment = line.substring(idx);
                        line = idx == 0 ? "" : line.substring(0, idx);
                    }

                    line = line.trim();
                    if (!line.isEmpty()) {
                        idx = line.indexOf(' ');
                        if (idx == -1) { // Must have both access and traget
                            Mavenizer.LOGGER.debug("Invalid access transformer line: " + line);
                            continue;
                        }

                        var target = line.substring(idx + 1);
                        var transformation = line.substring(0, idx + 1);
                        idx = target.indexOf('.');

                        // Class target
                        if (idx == -1) {
                            line = transformation + map.remapClass(target);
                        } else {
                            var cls = target.substring(0, idx);
                            target = target.substring(idx + 1);
                            var mcls = map.getClass(cls);

                            // There are no mappings for this class, so assume its not obfed
                            if (mcls != null) {
                                idx = target.indexOf('(');

                                if (idx == -1) { // Field
                                    line = transformation + map.remapClass(cls) + ' ' + mcls.remapField(target);
                                } else { // Method
                                    var name = target.substring(0, idx);
                                    var desc = target.substring(idx);
                                    line = transformation + map.remapClass(cls) + ' ' + mcls.remapMethod(name, desc) + map.remapDescriptor(desc);
                                }
                            }
                        }
                    }


                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    if (comment != null) {
                        if (!line.isEmpty())
                            out.write(' ');
                        out.write(comment.getBytes(StandardCharsets.UTF_8));
                    }
                    out.write('\n');
                }
            } catch (IOException e) {
                return Util.sneak(e);
            }

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
    private static final ComparableVersion MC_1_6_4  = new ComparableVersion("1.6.4");
    private static final ComparableVersion MC_1_7_2  = new ComparableVersion("1.7.2");
    private static final ComparableVersion MC_1_7_10 = new ComparableVersion("1.7.10");
    private static final ComparableVersion MC_1_8    = new ComparableVersion("1.8");
    private static final ComparableVersion MC_1_8_8  = new ComparableVersion("1.8.8");
    private static final ComparableVersion MC_1_8_9  = new ComparableVersion("1.8.9");
    private static final ComparableVersion MC_1_9    = new ComparableVersion("1.9");
    private static final ComparableVersion MC_1_9_4  = new ComparableVersion("1.9.4");
    private static final ComparableVersion MC_1_12   = new ComparableVersion("1.12");

    private record Exceptor(boolean json, boolean markers, boolean lvt, boolean generate) {
        private static Exceptor get(ComparableVersion mc) {
            var json = true;
            var markers = true;
            var lvt = false;
            var generate = true;

            if (mc.compareTo(MC_1_6_4) <= 0)
                json = false;

            if (mc.compareTo(MC_1_8_8) >= 0) {
                json = false;
                lvt = true;
            }

            if (mc.compareTo(MC_1_9) >= 0)
                markers = false;
            if (mc.compareTo(MC_1_7_2) <= 0)
                generate = false;
            return new Exceptor(json, markers, lvt, generate);
        }
    }
    private record Cleanup(Artifact artifact, List<String> args) {
        private static Cleanup get(ComparableVersion mc) {
            var args = List.<String>of();
            if (mc.compareTo(MC_1_8_9) >= 0) {
                args = List.of();
            } else if (mc.compareTo(MC_1_8_8) >= 0) {
                args = List.of("--fix-generic-params");//, "--fernflower", "--enum-args=false", "--enum-constructors=false");
            } else if (mc.compareTo(MC_1_8) >= 0) {
                args = List.of("--fix-generic-params", "--fml", "--fernflower",
                    // These patches are enum sythetic arg fixes which cleaup does in a generic way
                    "--ignore-patch", "net/minecraft/network/EnumConnectionState.java",
                    "--ignore-patch", "net/minecraft/util/EnumChatFormatting.java",
                    "--ignore-patch", "net/minecraft/util/EnumParticleTypes.java"
                );
            } else if (mc.compareTo(MC_1_7_10) >= 0) {
                args = List.of("--fix-generic-params", "--fml", "--fernflower");
            } else if (mc.compareTo(MC_1_7_2) >= 0) {
                args = List.of("--mode", "1.7.2");
            } else {
                args = List.of("--mode", "1.6.4");
            }
            return new Cleanup(Constants.MCPCLEANUP, args);
        }
    }
    private record Decompiler(Artifact artifact, boolean legacy, List<String> args, List<String> sorts) {

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
        /*
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
        */

        private static String sort(String... args) {
            return String.join(",", args);
        }
        private static Decompiler get(ComparableVersion mc, @Nullable FGVersion legacyFG) {
            if (mc.compareTo(MC_1_12) >= 0)
                return new Decompiler(Constants.FERNFLOWER_FG_2_3, false, DECOMPILER_ARGS, null);
            else if (mc.compareTo(MC_1_8_8) >= 0)
                return new Decompiler(Constants.FERNFLOWER_FG_2_2, false, DECOMPILER_ARGS, null);
            else if (mc.compareTo(MC_1_8) >= 0)
                return new Decompiler(Constants.FERNFLOWER_FG_2_0_LEGACY, true, DECOMPILER_ARGS_18, null);
            else if (mc.compareTo(MC_1_7_2) >= 0 && legacyFG == null) // Forge 1.7.2 uses FG 1.1 and 1.2 so we need this check
                return new Decompiler(Constants.FERNFLOWER_FG_2_0_LEGACY, true, DECOMPILER_ARGS_18, null);
            else {
                // Old Fernflower uses HashSet to iterate over inner classes
                // Which the iteration order has changed a few times between java versions
                // So unfortunately, we have to hardcode some specifc sorting to make patches apply
                var sorts = switch (mc.toString()) {
                    case "1.7.2" -> List.of(
                        sort("net/minecraft/client/gui/inventory/GuiContainerCreative", "CreativeSlot", "ContainerCreative"),
                        sort("net/minecraft/client/renderer/texture/Stitcher", "Slot", "Holder"),
                        sort("net/minecraft/client/settings/GameSettings", "SwitchOptions", "Options"),
                        sort("net/minecraft/enchantment/EnchantmentHelper", "IModifier", "ModifierDamage", "ModifierLiving", "HurtIterator", "DamageIterator"),
                        sort("net/minecraft/entity/player/EntityPlayer", "EnumStatus", "EnumChatVisibility"),
                        sort("net/minecraft/world/biome/BiomeGenBase", "SpawnListEntry", "Height", "TempCategory"),
                        sort("net/minecraft/world/gen/structure/ComponentScatteredFeaturePieces", "Feature", "JunglePyramid", "SwampHut", "DesertPyramid"),
                        sort("net/minecraft/world/gen/structure/StructureMineshaftPieces", "Cross", "Room", "Corridor", "Stairs"),
                        sort("net/minecraft/world/gen/structure/StructureStrongholdPieces", "Stairs", "Straight", "Library", "PortalRoom", "ChestCorridor", "RoomCrossing", "StairsStraight", "Stairs2", "Prison", "LeftTurn", "RightTurn", "Stones", "Stronghold", "Stronghold$Door", "Crossing", "Corridor", "SwitchDoor", "PieceWeight"),
                        sort("net/minecraft/world/gen/structure/StructureVillagePieces",
                                "Well", "Village", "Hall", "House1", "Church", "House4Garden", "Path",
                                "House2", "Start", "House3", "WoodHut", "Field1", "Field2", "Road", "Torch", "PieceWeight"),
                        sort("net/minecraft/world/storage/MapData", "MapCoord", "MapInfo")
                    );
                    default -> null;
                };
                return new Decompiler(Constants.FERNFLOWER_FG_1_0, true, DECOMPILER_ARGS_18, sorts);
            }
        }
    }
    private record MCPCfg(
        Exceptor exceptor,
        Cleanup cleanup,
        Decompiler decompiler
    ) {
        private static MCPCfg get(ComparableVersion mc, @Nullable FGVersion legacyFG) {
            var except = Exceptor.get(mc);
            var cleanup = Cleanup.get(mc);
            var decomp = Decompiler.get(mc, legacyFG);
            return new MCPCfg(except, cleanup, decomp);
        }
    }
}
