/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.reflect.TypeToken;
import io.codechicken.diffpatch.cli.PatchOperation;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.PatchMode;
import io.codechicken.diffpatch.util.Input.MultiInput;
import io.codechicken.diffpatch.util.Output.MultiOutput;
import io.codechicken.diffpatch.util.archiver.ArchiveFormat;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MCPConfig;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;
import org.jetbrains.annotations.Nullable;

// TODO [MCMavenizer][Documentation] Document
public class MCPTaskFactory {
    private final MCPConfig.V2 cfg;
    private final MCPSide side;
    private final File build;
    private final @Nullable MCPTaskFactory parent;
    private final List<Map<String, String>> steps;
    private final Map<String, Task> data = new HashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Task preStrip;
    private final Task rawJar;
    private final Task mappings;
    private final Task srgJar;
    private final Task preDecomp;
    private final Task last;

    private final BiPredicate<File, String> injectFileFilter;
    private @Nullable List<Lib> libraries = null;

    private MCPTaskFactory(MCPTaskFactory parent, File build, Task preDecomp) {
        this.parent = parent;
        this.build = build;
        this.side = parent.side;
        this.cfg = parent.cfg;
        this.steps = parent.steps;
        this.preStrip = parent.preStrip;
        this.rawJar = parent.rawJar;
        this.mappings = parent.mappings;
        this.srgJar = parent.srgJar;
        this.preDecomp = preDecomp;

        var foundDecomp = false;
        Task last = null;
        for (var step : this.steps) {
            var type = step.get("type");
            var name = step.getOrDefault("name", type);

            Task task;
            if ("decompile".equals(name)) {
                foundDecomp = true;

                // Replace decompile input with our modified task
                var inputTask = step.get("input");
                inputTask = inputTask.substring(1, inputTask.length() - 7);
                tasks.replace(inputTask, preDecomp);

                task = createTask(step);
                tasks.put(name, task);
            } else if (!foundDecomp) {
                task = this.parent.findStep(name);
                tasks.put(name, task);
            } else {
                task = createTask(step);
                tasks.put(name, task);
            }

            last = task;
        }
        this.last = last;

        this.injectFileFilter = this.getFileFilter();
    }

    public MCPTaskFactory(MCPSide side, File build) {
        this.parent = null;
        this.side = side;
        this.build = build;
        this.cfg = this.side.getMCP().getConfig();

        var entries = cfg.getData(this.side.getName());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            data.put(key, Task.named("extract[" + key + ']', () -> extract(key, value)));
        }

        this.steps = cfg.getSteps(this.side.getName());
        if (this.steps.isEmpty())
            throw except("Does not contain requested side `" + side.getName() + "`");

        Task prestrip = null, rawJar = null, mappings = null, srgJar = null, predecomp = null, last = null;
        for (var step : this.steps) {
            var type = step.get("type");
            var name = step.getOrDefault("name", type);

            var task = createTask(step);
            tasks.put(name, task);
            last = task;

            switch (name) {
                case "strip", "stripClient":
                    prestrip = this.findStep(step.get("input"));
                case "merge":
                    rawJar = this.findStep(name);
                    break;
                case "decompile":
                    predecomp = this.findStep(step.get("input"));
                    break;
                case "rename":
                    srgJar = this.findStep(name);

                    var value = step.getOrDefault("mappings", "{mappings}");
                    if (!value.startsWith("{") || !value.endsWith("}"))
                        throw except("Expected `rename` step's `mappings` argument to be a variable");

                    if (value.endsWith("Output}"))
                        mappings = this.findStep(value);
                    else
                        mappings = this.findData(value);
                default:
                    break;
            }
        }

        if (prestrip == null)
            throw except("Could not find `strip%s` step".formatted(MCPSide.JOINED.equals(side.getName()) ? "Client" : ""));

        if (rawJar == null)
            throw except("Could not find `%s` task".formatted(MCPSide.JOINED.equals(side.getName()) ? "merge" : "strip"));

        if (mappings == null)
            throw except("Could not find `mappings` task");

        if (srgJar == null)
            throw except("Could not find `rename` task");

        if (predecomp == null)
            throw except("Could not find `decompile` step");

        if (last == null)
            throw except("No steps defined");

        this.preStrip = prestrip;
        this.rawJar = rawJar;
        this.mappings = mappings;
        this.srgJar = srgJar;
        this.preDecomp = predecomp;
        this.last = last;
        //this.mappingHelper = new MappingTasks(mappings);

        this.injectFileFilter = this.getFileFilter();
    }

    private static final BiPredicate<File, String> TRUE = (_, _) -> true;
    private static final BiPredicate<File, String> NOT_CONTAINS_CLIENT = (_, s) -> !s.contains("client");
    private BiPredicate<File, String> getFileFilter() {
        return this.side.containsClient() ? TRUE : NOT_CONTAINS_CLIENT;
    }

    public MCPTaskFactory child(File dir, Task predecomp) {
        return new MCPTaskFactory(this, dir, predecomp);
    }

    public Task getRawJar() {
        return this.rawJar;
    }

    public Task getMappings() {
        return this.mappings;
    }

    public Task getSrgJar() {
        return this.srgJar;
    }

    public Task getPreDecompile() {
        return this.preDecomp;
    }

    public Task getLastTask() {
        return this.last;
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid MCP Dependency: " + this.side.getMCP().getName() + " - " + message);
    }

    private File getData() {
        return this.side.getMCP().getData();
    }

    public Task findStep(String name) {
        if (name.startsWith("{") && name.endsWith("Output}"))
            name = name.substring(1, name.length() - 7);

        var ret = this.tasks.get(name);
        if (ret == null)
            throw except("Unknown task `" + name + '`');

        return ret;
    }

    public Task findData(String name) {
        if (this.parent != null)
            return this.parent.findData(name);

        if (name.startsWith("{") && name.endsWith("}"))
            name = name.substring(1, name.length() - 1);

        var ret = this.data.get(name);
        if (ret == null)
            throw except("Unknown data entry `" + name + "`");

        return ret;
    }

    private File extract(String key, String value) {
        if (value.endsWith("/"))
            return extractFolder(key, value);
        return extractSingle(key, value);
    }

    private File extractSingle(String key, String value) {
        var idx = value.lastIndexOf('/');
        var filename = idx == -1 ? value : value.substring(idx);
        var target = new File(this.build, "data/" + key + '/' + filename);

        var cache = HashStore.fromFile(target);
        cache.add("mcp", getData());

        if (target.exists() && cache.isSame())
            return target;

        Mavenizer.assertNotCacheOnly();

        try (var zip = new ZipFile(getData())) {
            var entry = zip.getEntry(value);
            if (entry == null)
                throw except("Missing data: `" + key + "`: `" + value + "`");

            FileUtils.ensureParent(target);

            try (var os = new FileOutputStream(target)) {
                zip.getInputStream(entry).transferTo(os);
            }

            target.setLastModified(entry.getLastModifiedTime().toMillis());

            cache.save();
            return target;
        } catch (IOException e) {
            throw except("Failed to extract `" + key + "`: `" + value + "`");
        }
    }

    private File extractFolder(String key, String value) {
        var base = new File(this.build, "data/" + key);

        var cache = new HashStore(base).load(new File(this.build, "data/" + key + ".cache"));
        cache.add("mcp", getData());
        boolean same = cache.isSame();

        var existing = new HashSet<>(FileUtils.listFiles(base));

        try (var zip = new ZipFile(getData())) {
            var count = 0;

            for (var itr = zip.entries(); itr.hasMoreElements(); ) {
                var e = itr.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(value))
                    continue;

                count++;

                var relative = e.getName().substring(value.length());
                var target = new File(base, relative);
                existing.remove(target);
                FileUtils.ensureParent(target);

                if (!target.exists() || !same) {
                    Mavenizer.assertNotCacheOnly();
                    try (var os = new FileOutputStream(target)) {
                        zip.getInputStream(e).transferTo(os);
                    }
                    target.setLastModified(e.getLastModifiedTime().toMillis());
                }
            }

            // Delete files that were already in the target directory which we didn't extract
            var prefix = base.getAbsolutePath() + File.separator;
            for (var f : existing) {
                if (f.exists())
                    f.delete();

                if (f.getParentFile().listFiles().length == 0 &&
                    f.getAbsolutePath().startsWith(prefix)) {
                    f.getParentFile().delete();
                }
            }

            if (count == 0)
                throw except("Missing data: `" + key + "`: `" + value + "`");

            cache.save();
            return base;
        } catch (IOException e) {
            throw except("Failed to extract `" + key + "`: `" + value + "`");
        }
    }

    private Task createTask(Map<String, String> step) {
        var type = step.get("type");
        var name = step.getOrDefault("name", type);
        var mc = this.side.getMCP().getMinecraftTasks();
        var spec = cfg.spec;

        switch (type) {
            case "downloadManifest": return mc.launcherManifest;
            case "downloadJson":     return mc.versionJson;
            case "downloadClient":   return mc.versionFile(MinecraftTasks.Files.CLIENT_JAR);
            case "downloadServer":   return mc.versionFile(MinecraftTasks.Files.SERVER_JAR);
            case "strip":            return strip(name, step);
            case "inject":           return inject(name, step);
            case "patch":            return patch(name, step);
            case "listLibraries":
                if (spec >= 3 && step.containsKey("bundle"))
                    return listLibrariesBundle(name, step);
                return listLibraries(name, step);
        }

        if (spec >= 2) {
            switch (type) {
                case "downloadClientMappings": return downloadClientMappings();
                case "downloadServerMappings": return downloadServerMappings();
            }
        }

        var custom = cfg.getFunction(type);

        if (custom == null)
            throw except("Unknown step type: " + type);

        return execute(name, step, custom);
    }

    public Task downloadClientMappings() {
        return this.side.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.Files.CLIENT_MAPPINGS);
    }

    public Task downloadServerMappings() {
        return this.side.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.Files.SERVER_MAPPINGS);
    }

    private Task strip(String name, Map<String, String> step) {
        var whitelist = "whitelist".equalsIgnoreCase(step.getOrDefault("mode", "whitelist"));
        var output = new File(this.build, name + ".jar").getAbsoluteFile();
        var input = findStep(step.get("input"));
        return Task.named(name,
            Task.deps(() -> input, () -> this.mappings),
            () -> strip(input, whitelist, output)
        );
    }

    private File strip(Task inputTask, boolean whitelist, File output) {
        var input = inputTask.execute();
        var mappings = this.mappings.execute();

        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("mappings", mappings);

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        if (output.exists())
            output.delete();

        FileUtils.ensureParent(output);

        try {
            var map = IMappingFile.load(mappings);
            var classes = new HashSet<>();
            for (var cls : map.getClasses())
                classes.add(cls.getOriginal() + ".class");

            try (var is = new JarInputStream(new FileInputStream(input));
                var os = new JarOutputStream(new FileOutputStream(output))) {
               JarEntry entry;
               while ((entry = is.getNextJarEntry()) != null) {
                   if (entry.isDirectory() || classes.contains(entry.getName()) != whitelist)
                       continue;
                   os.putNextEntry(FileUtils.getStableEntry(entry));
                   is.transferTo(os);
                   os.closeEntry();
               }
            }

            cache.save();
            return output;
       } catch (IOException e) {
           return Util.sneak(new IOException("Failed to split " + input + " into output " + output, e));
       }
    }

    private Task inject(String name, Map<String, String> step) {
        var input = findStep(step.get("input"));
        var inject = findData("inject");
        var packages = new File(this.build, name + "/packages.jar").getAbsoluteFile();
        var output = new File(this.build, name + "/output.jar").getAbsoluteFile();
        return Task.named(name,
            Task.deps(input, inject),
            () -> inject(input, inject, packages, output)
        );
    }

    private File inject(Task inputTask, Task injectTask, File packages, File output) {
        var input = inputTask.execute();
        var inject = injectTask.execute();
        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("inject", inject);
        cache.addKnown("codever", "1");

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        if (output.exists())
            output.delete();

        FileUtils.ensureParent(output);

        var templateF = new File(inject, "package-info-template.java");
        String template = null;
        try {
            if (templateF.exists()) {
                var modified = templateF.lastModified();
                template = Files.readString(templateF.toPath(), StandardCharsets.UTF_8);

                var pkgs = new TreeSet<String>();
                try (var zip = new ZipInputStream(new FileInputStream(input))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        var name = entry.getName();
                        if (entry.isDirectory() || !name.endsWith(".java"))
                            continue;

                        var idx = name.indexOf('/');
                        var pkg = idx == -1 ? "" : name.substring(0, idx);

                        // com.mojang was only added in 1.14.4, but I don't feel like making that check and things should be fine.
                        if (pkg.startsWith("net/minecraft/") || pkg.startsWith("com/mojang/"))
                            pkgs.add(pkg);
                    }
                }

                if (!packages.exists())
                    packages.delete();

                try (var zip = new ZipOutputStream(new FileOutputStream(packages))) {
                    for (var pkg : pkgs) {
                        zip.putNextEntry(FileUtils.getStableEntry(pkg + "/package-info.java", modified));
                        zip.write(template.replace("{PACKAGE}", pkg.replace('/', '.')).getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                }

                var filter = this.injectFileFilter.and((_, name) -> !name.equals("package-info-template.java"));
                FileUtils.mergeJars(output, false, filter, input, inject, packages);
            } else {
                FileUtils.mergeJars(output, false, this.injectFileFilter, input, inject);
            }

            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    private Task patch(String name, Map<String, String> step) {
        var input = this.findStep(step.get("input"));
        var output = new File(this.build, name + "/output.jar");
        var rejects = new File(this.build, name + "/rejects.jar");
        return Task.named(name,
            Task.deps(input),
            () -> patch(input, output, rejects)
        );
    }

    private File patch(Task inputTask, File output, File rejects) {
        var input = inputTask.execute();
        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("data", this.getData());

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        var patches = this.cfg.getData(this.side.getName()).get("patches");

        var builder = PatchOperation.builder()
            .logTo(LOGGER::error)
            .baseInput(MultiInput.archive(ArchiveFormat.ZIP, input.toPath()))
            .patchesInput(MultiInput.archive(ArchiveFormat.ZIP, this.getData().toPath()))
            .patchedOutput(MultiOutput.archive(ArchiveFormat.ZIP, output.toPath()))
            .rejectsOutput(MultiOutput.archive(ArchiveFormat.ZIP, rejects.toPath()))
            .patchesPrefix(patches)
            .level(LogLevel.ERROR)
            // Some MCPConfig versions have bad offsets.
            // This is also needed for versions with SAS because it removes the imports/annotations.
            // So allow a little bit of shifting
            .mode(PatchMode.OFFSET)
            //.aPrefix("a")
            //.bPrefix("b")
        ;

        try {
            FileUtils.ensureParent(output);
            FileUtils.ensureParent(rejects);

            var result = builder.build().operate();

            boolean success = result.exit == 0;
            if (!success) {
                LOGGER.error("Fialed to apply patches");
                LOGGER.error("  Input:   " + input.getAbsolutePath());
                LOGGER.error("  Patches: " + this.getData().getAbsolutePath());
                LOGGER.error("  Output:  " + output.getAbsolutePath());
                LOGGER.error("  Rejects: " + rejects.getAbsolutePath());
                if (result.summary != null)
                    result.summary.print(LOGGER.getError(), true);
                else
                    LOGGER.error("Failed to apply patches, no summary available");

                throw except("Failed to apply patches, Rejects saved to: " + rejects.getAbsolutePath());
            }

            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    private Task listLibraries(String name, Map<String, String> step) {
        var output = new File(this.build, name + ".txt");
        var json = this.findStep("downloadJson");
        return Task.named(name,
            Task.deps(json),
            () -> listLibraries(json, output)
        );
    }

    private File listLibraries(Task jsonTask, File output) {
        var jsonF = jsonTask.execute();
        var json = JsonData.minecraftVersion(jsonF);

        var libsRaw = json.getLibs();
        // Deduplicate libs, Some versions have conditions that cause libraries to be listed multiple times
        var seen = new HashSet<String>();
        var libs = new LinkedHashSet<MinecraftVersion.Lib>(libsRaw.size());
        for (var lib : libsRaw) {
            if (seen.add(lib.coord))
                libs.add(lib);
        }

        var libsVarCache = new File(output.getAbsoluteFile().getParentFile(), "libraries.txt");

        var cache = HashStore.fromFile(output).add(jsonF).add(libsVarCache);
        for (var lib : libs) {
            if (lib.dl != null) // Sometimes natives don't have a main download
                cache.addKnown(lib.coord, lib.dl.sha1);
        }

        if (output.exists() && libsVarCache.exists() && cache.isSame()) {
            try {
                this.libraries = JsonData.<List<Lib.Cached>>fromJson(libsVarCache, new TypeToken<>() { }).stream().map(Lib.Cached::resolve).toList();
                return output;
            } catch (Exception e) {
                LOGGER.error("Failed to load cached libraries, regenerating...");
                LOGGER.error("Cached file: " + libsVarCache.getAbsolutePath());
                e.printStackTrace(LOGGER.getError());
            }
        }

        Mavenizer.assertNotCacheOnly();
        cache.clear().add(jsonF);

        var buf = new StringBuilder(20_000);
        var minecraft = this.side.getMCP().getCache().minecraft();
        var downloadedLibs = new ArrayList<Lib>();
        for (var lib : libs) {
            // Sometimes natives don't have a main download
            if (lib.dl == null)
                continue;

            if (!lib.dl.url.toString().startsWith(Constants.MOJANG_MAVEN))
                throw new IllegalStateException("Unable to download library " + lib.dl.path + " as it is not on Mojang's repo and I was lazy. " + lib.dl.url);

            var target = minecraft.download(lib.dl);

            buf.append("-e=").append(target.getAbsolutePath()).append('\n');

            var artifact = Artifact.from(lib.coord).withOS(lib.os);

            downloadedLibs.add(new Lib(artifact, target));
            cache.add(lib.coord, target);
        }

        try {
            FileUtils.ensureParent(libsVarCache);
            JsonData.toJson(downloadedLibs.stream().map(Lib::cacheable).toList(), libsVarCache);
            cache.add(libsVarCache);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.libraries = downloadedLibs;

        FileUtils.ensureParent(output);
        try (var os = new FileOutputStream(output)) {
            os.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    public record Lib(Artifact name, File file) {
        public Cached cacheable() {
            return new Cached(name, file.getAbsolutePath());
        }

        public record Cached(Artifact name, String file) implements Serializable {
            public Lib resolve() {
                return new Lib(name, new File(file));
            }
        }
    }

    public List<Lib> getLibraries() {
        // no libraries? run the task to populate the field.
        if (this.libraries == null)
            this.findStep("listLibraries").execute();

        return this.libraries;
    }

    private Task listLibrariesBundle(String name, Map<String, String> step) {
        var bundle = findStep(step.get("bundle"));
        var libraries = new File(this.build, name);
        var output = new File(this.build, name + "/libraries.txt");
        return Task.named(name,
            Task.deps(bundle),
            () -> listLibrariesBundle(bundle, libraries, output)
        );
    }

    private File listLibrariesBundle(Task bundleTask, File libraries, File output) {
        var bundle = bundleTask.execute();

        try (var jar = new JarFile(bundle)) {
            var format = jar.getManifest().getMainAttributes().getValue("Bundler-Format");
            if (format == null)
                throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing format entry from manifest");

            if (!"1.0".equals(format))
                throw new RuntimeException("Invalid bundle: `" + bundle + "` - Unsupported format " + format);

            var entry = jar.getEntry("META-INF/libraries.list");
            if (entry == null)
                throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing META-INF/libraries.list");

            record LibLine(String hash, Artifact artifact, String path) implements Comparable<LibLine> {
                @Override
                public int compareTo(LibLine o) {
                    return Util.compare(this.artifact, o.artifact);
                }
            }
            var libs = new TreeSet<LibLine>();

            var reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry)));
            String line;
            while ((line = reader.readLine()) != null) {
                var pts = line.split("\t");
                if (pts.length < 3)
                    throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Invalid line: " + line);
                libs.add(new LibLine(pts[0], Artifact.from(pts[1]),  pts[2]));
            }

            var cache = HashStore.fromFile(output).add(bundle);
            for (var lib : libs)
                cache.add(lib.artifact().toString(), new File(libraries, lib.path()));

            if (output.exists() && cache.isSame())
                return output;

            Mavenizer.assertNotCacheOnly();
            cache.clear().add(bundle);

            var buf = new StringBuilder();

            var downloadedLibs = new ArrayList<Lib>();

            for (var lib : libs) {
                var target = new File(libraries, lib.path());
                var artifact = lib.artifact();
                buf.append("-e=").append(target.getAbsolutePath()).append('\n');

                downloadedLibs.add(new Lib(artifact, target));
                if (!target.exists()) {
                    entry = jar.getEntry("META-INF/libraries/" + lib.path());
                    if (entry == null)
                        throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing META-INF/libraries/" + lib);

                    FileUtils.ensureParent(target);

                    try (var os = new FileOutputStream(target);
                         var is = jar.getInputStream(entry)) {
                        is.transferTo(os);
                    }
                }

                cache.add(target);
            }

            this.libraries = Collections.unmodifiableList(downloadedLibs);

            FileUtils.ensureParent(output);
            try (var os = new FileOutputStream(output)) {
                os.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            }
            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    public Task getExtra() {
        return Task.named("extra[" + this.side.getName() + ']',
            Task.deps(this.preStrip, this.mappings),
            () -> getExtra(this.preStrip, mappings)
        );
    }

    private File getExtra(Task prestripTask, Task mappingsTask) {
        var prestrip = prestripTask.execute();
        var mappings = mappingsTask.execute();

        var output = new File(this.build, "extra.jar");

        var cache = HashStore.fromFile(output);
        cache.add("prestrip", prestrip);
        cache.add("mappings", mappings);

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        try {
            var whitelist = IMappingFile
                .load(mappings).getClasses().stream()
                .map(IMappingFile.IClass::getOriginal)
                .collect(Collectors.toSet());
            FileUtils.splitJar(prestrip, whitelist, output, false, false);
        } catch (IOException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }

    private Task execute(String name, Map<String, String> step, MCPConfig.Function func) {
        var args = new HashMap<String, TaskOrArg>();
        var deps = new HashSet<Task>();

        // Find any inputs from previous tasks
        for (var key : step.keySet()) {
            var value = step.get(key);
            if (isVariable(value)) {
                var task = value.endsWith("Output}") ? findStep(value) : findData(value);
                deps.add(task);
                args.put(key, new TaskOrArg(value, task, null));
            } else {
                args.put(key, new TaskOrArg(value, null, value));
            }
        }

        // Add the outputs to the possible arg lists
        var ext = step.getOrDefault("outputExtension", "jar");
        var output = new File(this.build, name + '/' + name + '.' + ext);
        var log = new File(this.build, name + "/log.txt");
        args.put("output", new TaskOrArg("output", null, output.getAbsolutePath()));
        args.put("log", new TaskOrArg("log", null, log.getAbsolutePath()));

        // Fill substitutions in arguments, also builds dependencies on extract tasks
        var jvmArgs = fillArgs(func.jvmargs, args, deps);
        var runArgs = fillArgs(func.args, args, deps);

        return Task.named(name, Task.deps(deps),
            () -> execute(name, jvmArgs, runArgs, func, log, output)
        );
    }

    private static final boolean isDecompiler(String name, Artifact artifact) {
        if ("decompile".equals(name))
            return true;

        switch (artifact.getName()) {
            case "forgeflower":
            case "fernflower":
            case "vineflower":
                return true;
        }

        return false;
    }

    private File execute(String name, List<TaskOrArg> jvmArgs, List<TaskOrArg> runArgs, MCPConfig.Function func, File log, File output) {
        // First download the tool
        var maven = new MavenCache("mcp-tools", func.repo, this.side.getMCP().getCache().root());
        var toolA = Artifact.from(func.version);
        var tool = maven.download(toolA);

        var isDecompile = isDecompiler(name, toolA);

        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("jvm-args", jvmArgs.stream().map(TaskOrArg::name).collect(Collectors.joining(" ")));
        cache.add("run-args", runArgs.stream().map(TaskOrArg::name).collect(Collectors.joining(" ")));
        var tasks = new HashMap<Task, String>();
        var jvm = resolveArgs(cache, tasks, jvmArgs);
        var run = resolveArgs(cache, tasks, runArgs);

        if (output.exists() && cache.isSame())
            return output;

        Mavenizer.assertNotCacheOnly();

        // Make sure the output directory exists, some old tools don't do it themselves.
        var parent = output.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();

        int java_version = func.getJavaVersion(this.side.getMCP().getConfig());
        var jdks = this.side.getMCP().getCache().jdks();
        File jdk;
        try {
            jdk = jdks.get(java_version);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + java_version, e);
        }

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, jvm, run, isDecompile ? MCPTaskFactory::parseDecompileLog : null);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private boolean isVariable(String value) {
        return value.startsWith("{") && value.endsWith("}");
    }

    private record TaskOrArg(String name, Task task, String value) {}

    private List<TaskOrArg> fillArgs(List<String> lst, Map<String, TaskOrArg> args, Set<Task> deps) {
        if (lst == null)
            return List.of();

        var ret = new ArrayList<TaskOrArg>(lst.size());
        for (var value : lst) {
            if (isVariable(value)) {
                var data_name = value.substring(1, value.length() - 1);
                var arg = args.get(data_name);
                if (arg != null)
                    ret.add(arg);
                else {
                    var task = this.findData(data_name);
                    deps.add(task);
                    ret.add(new TaskOrArg(data_name, task, null));
                }
            } else
                ret.add(new TaskOrArg(value, null, value));
        }
        return ret;
    }

    private List<String> resolveArgs(HashStore cache, Map<Task, String> tasks, List<TaskOrArg> args) {
        var ret = new ArrayList<String>();
        for (var toa : args) {
            if (toa.task() == null)
                ret.add(toa.value());
            else {
                var path = tasks.get(toa.task());
                if (path == null) {
                    var file = toa.task().execute();
                    cache.add(toa.name(), file);
                    path = file.getAbsolutePath();
                }
                ret.add(path);
            }
        }
        return ret;
    }

    private static final int OUT_OF_MEMORY = -1001;
    private static final int FAILED_DECOMPILE = -1002;
    // Yes this is slow as fuck, but this is only run during a decompile run which is already slow,
    // This is to check if Fernflower is broken and returning success when it really failed.
    // It also eagerly exits the process when something fails so as to not waste time.
    private static int parseDecompileLog(String line) {
        if (line.startsWith("java.lang.OutOfMemoryError:"))
            return OUT_OF_MEMORY;
        if (line.contains("ERROR:")) {
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + cl.qualifiedName + " couldn't be written.";
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + classWrapper.getClassStruct().qualifiedName + " couldn't be written.";
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + node.classStruct.qualifiedName + " couldn't be written.";
            if (line.endsWith(" couldn't be written."))
                return FAILED_DECOMPILE;
            // DecompilerContext.getLogger().logError("Class " + cl.qualifiedName + " couldn't be processed.", t);
            if (line.endsWith(" couldn't be processed."))
                return FAILED_DECOMPILE;
            // DecompilerContext.getLogger().logError("Class " + cl.qualifiedName + " couldn't be fully decompiled.", t);
            if (line.endsWith(" couldn't be fully decompiled."))
                return FAILED_DECOMPILE;
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + classStruct.qualifiedName + " couldn't be decompiled.";
            if (line.endsWith(" couldn't be decompiled."))
                return FAILED_DECOMPILE;
        }
        return 0;
    }
}
