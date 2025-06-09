/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import io.codechicken.diffpatch.cli.PatchOperation;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.PatchMode;
import io.codechicken.diffpatch.util.Input.MultiInput;
import io.codechicken.diffpatch.util.Output.MultiOutput;
import io.codechicken.diffpatch.util.archiver.ArchiveFormat;
import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.PatcherConfig;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.logging.Log;
import org.jetbrains.annotations.Nullable;

// TODO: [MCMavenizer] This class needs to be split off into some sort of abstract class so that other patching processes can be implemented.
// The current way this is implemented by trying to parse a specific config is not that great. And if we want to support other versions, this HAS to be abstracted.
/**
 * This class is responsible for the <strong>entire</strong> patching process.
 * It continues work after MCP has decompiled the game.
 *
 * After construction the 'last' task will be the final task that produces source files ready to be recompiled.
 * These files may or may not be in SRG names, depending on the patcher configuration.
 * But the point is this creates source code.
 */
public class Patcher implements Supplier<Task> {
    private final File build;
    private final ForgeRepo forge;
    private final Artifact name;
    private final File data;
    private final String dataHash;
    public final PatcherConfig.V2 config;
    private final Patcher parent;
    private final @Nullable MCP mcp;
    private final @Nullable MCPSide mcpSide;

    private final Map<String, Task> extracts = new HashMap<>();
    private final Task downloadSources;
    private final Task predecomp;
    private final Task last;

    /**
     * Creates a new Patcher for the given Forge repo.
     *
     * @param forge The forge repo
     * @param name  The development artifact (usually userdev)
     */
    Patcher(File build, ForgeRepo forge, Artifact name) {
        this.build = build;
        this.forge = forge;
        this.name = name;

        this.data = this.forge.getCache().maven().download(name);
        if (!this.data.exists())
            throw new IllegalStateException("Failed to download " + name);

        this.dataHash = HashFunction.SHA1.sneakyHash(this.data);
        this.config = loadConfig(this.data);
        validateConfig();

        if (this.config.sources == null) {
            this.downloadSources = null;
        } else {
            this.downloadSources = Task.named("downloadSources", () -> {
                var art = Artifact.from(this.config.sources);
                var ret = this.forge.getCache().maven().download(art);
                if (ret == null)
                    throw except("Failed to download sources " + art);
                return ret;
            });
        }

        Task predecomp, last;
        if (this.config.hasParent()) {
            this.parent = new Patcher(build, this.forge, Artifact.from(this.config.getParent()));
            this.mcp = null;
            this.mcpSide = null;
            predecomp = parent.predecomp;
            last = parent.last;
        } else {
            this.parent = null;
            this.mcp = this.forge.mcpconfig.get(Artifact.from(this.config.getParent()));
            this.mcpSide = this.mcp.getSide(MCPSide.JOINED);
            predecomp = this.mcpSide.getTasks().getPreDecompile();
            last = this.mcpSide.getTasks().getLastTask();
        }

        var stack = new Stack<Patcher>();
        stack.add(this);

        // Check if we need to do anything pre-decompile
        var ats = extractATs();
        var sass = extractSASs();
        if (ats != null || sass != null) {
            var mcpver = this.getMCP().getName().getVersion();

            var hash = Util.hash(HashFunction.SHA1, ats, sass);
            var dir = new File(this.forge.globalBuild, "mcp/" + mcpver + '/' + this.name.getName() + '/' + hash);
            var cache = this.forge.getCache();

            if (ats != null) {
                var tmp = predecomp;
                predecomp = Task.named("modifyAccess", Set.of(tmp), () -> modifyAccess(dir, tmp, ats, cache));
            }

            if (sass != null) {
                var tmp = predecomp;
                predecomp = Task.named("stripSides", Set.of(tmp), () -> stripSides(dir, tmp, sass, cache));
            }

            // If we changed the decompile input, rebuild decompile and subsequent tasks
            last = completeMcp(dir, predecomp);
            stack = this.getStack();
        }

        this.predecomp = predecomp;

        while (!stack.isEmpty()) {
            var patcher = stack.pop();
            var root = patcher == this ? this.build : new File(this.build, "parent-" + patcher.name.getName());

            if (patcher.config.processor != null)
                last = patcher.postProcess(last, root);

            if (patcher.config.patches != null)
                last = patcher.patch(last, root);

            if (patcher.config.sources != null)
                last = patcher.injectSources(last, root);
        }

        this.last = last;
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message);
    }

    private RuntimeException except(String message, Throwable e) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message, e);
    }

    private PatcherConfig.V2 loadConfig(File data) {
        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry("config.json");
            if (entry == null)
                throw except("Missing config.json");
            var cfg_data = zip.getInputStream(entry).readAllBytes();

            int spec = JsonData.configSpec(cfg_data);

            if (spec == 1)
                return new PatcherConfig.V2(JsonData.patcherConfig(cfg_data));
            else if (spec == 2)
                return JsonData.patcherConfigV2(cfg_data);
            else
                throw except("Unknown Spec: " + spec);

        } catch (IOException e) {
            throw except("Error reading config", e);
        }
    }

    private void validateConfig() {
        if (this.config.parent == null && this.config.mcp == null)
            throw except("Missing parent or mcp entry");
    }

    /** @return The instance of MCP used to decompile the game */
    public MCP getMCP() {
        return this.mcp == null ? this.parent.getMCP() : this.mcp;
    }

    public MCPSide getMCPSide() {
        return this.mcpSide == null ? this.parent.getMCPSide() : this.mcpSide;
    }

    public String getDataHash() {
        return this.dataHash;
    }

    public void forAllLibraries(Consumer<Artifact> consumer) {
        this.forAllLibraries(consumer, null);
    }

    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        this.forAllLibrariesInternal(consumer, filter, this.getMCPSide().getMCLibraries());
        this.forAllLibrariesInternal(consumer, filter, this.getMCPSide().getMCPConfigLibraries());
        this.forAllLibrariesInternal(consumer, filter, this.getArtifacts());
    }

    // to avoid duplicate code
    private void forAllLibrariesInternal(Consumer<? super Artifact> consumer, @Nullable Predicate<? super Artifact> filter, Iterable<? extends Artifact> libraries) {
        for (var library : libraries) {
            if (filter == null || filter.test(library))
                consumer.accept(library);
        }
    }

    public List<Artifact> getArtifacts() {
        // TODO MOVE ALL THIS LOGIC TO SOME SORT OF "ARTIFACT LIST GENERATOR" IN ARTIFACT.JAVA
        var artifacts = new ArrayList<Artifact>() /*{
            private final Set<NonVersionedArtifact> distincts = new HashSet<>();

            @Override
            public boolean add(Artifact artifact) {
                var nonVersioned = NonVersionedArtifact.of(artifact);
                if (distincts.contains(nonVersioned)) {
                    this.removeIf(a -> {
                        var result = nonVersioned.is(a);
                        if (result)
                            log("Replacing artifact " + a + " with new version " + a.getVersion());

                        return result;
                    });
                    distincts.remove(nonVersioned);
                }

                distincts.add(nonVersioned);
                return super.add(artifact);
            }

            record NonVersionedArtifact(String group, String name, @Nullable String classifier) {
                static NonVersionedArtifact of(Artifact artifact) {
                    return new NonVersionedArtifact(artifact.getGroup(), artifact.getName(), artifact.getClassifier());
                }

                boolean is(Artifact artifact) {
                    return this.group.equals(artifact.getGroup())
                        && this.name.equals(artifact.getName())
                        && (this.classifier == null || this.classifier.equals(artifact.getClassifier()));
                }
            }
        }*/;

        for (var lib : this.config.libraries) {
            var artifact = Artifact.from(lib);
            artifacts.add(artifact);
        }

        return artifacts;
    }

    public List<File> getClasspath() {
        var classpath = new ArrayList<File>();

        // minecraft version.json libs + mcpconfig libs + userdev libs
        // also for module metadata (same order)
        for (var lib : this.getMCP().getSide(MCPSide.JOINED).getTasks().getLibraries()) {
            classpath.add(lib.file());
        }

        for (var lib : this.getMCP().getConfig().getLibraries(MCPSide.JOINED)) {
            classpath.add(this.forge.getCache().maven().download(Artifact.from(lib)));
        }

        for (var lib : this.config.libraries) {
            classpath.add(this.forge.getCache().maven().download(Artifact.from(lib)));
        }

        return classpath;
    }

    /** @return The final unnamed sources */
    public Task get() {
        return this.last;
    }

    public Stack<Patcher> getStack() {
        return getStack(true);
    }

    public Stack<Patcher> getParents() {
        return getStack(false);
    }

    private Stack<Patcher> getStack(boolean includeSelf) {
        var stack = new Stack<Patcher>();
        if (includeSelf)
            stack.add(this);

        for (var parent = this.parent; parent != null; parent = parent.parent) {
            stack.add(parent);
        }
        return stack;
    }

    private File extractATs() {
        return extractJoinedFiles("access_transformer.cfg", this.config.getAts());
    }

    private File extractSASs() {
        return extractJoinedFiles("side_annotation_stripper.cfg", this.config.getSASs());
    }

    private File extractJoinedFiles(String filename, List<String> files) {
        if (files.isEmpty())
            return null;

        var output = new File(this.build, filename);
        var cache = HashStore.fromFile(output);
        cache.add("data", this.data);

        if (output.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        if (output.exists())
            output.delete();

        FileUtils.ensureParent(output);
        boolean first = true;
        try (var zip = new ZipFile(this.data);
             var out = new FileOutputStream(output)) {
            for (var file : files) {
                var entry = zip.getEntry(file);
                if (entry == null)
                    throw new IllegalStateException("Invalid Patcher configuation, Missing Data: " + file);

                if (!first)
                    out.write(new byte[] { '\r', '\n' });
                else
                    first = false;

                try (var is = zip.getInputStream(entry)) {
                    is.transferTo(out);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Invalid patcher config, failed to extract data", e);
        }

        cache.save();
        return output;
    }

    private Task extractSingle(String key, String value) {
        return this.extracts.computeIfAbsent(value, k ->
            Task.named("extract[" + key + ']', () -> extractSingleTask(key, value))
        );
    }

    private File extractSingleTask(String key, String value) {
        var idx = value.lastIndexOf('/');
        var filename = idx == -1 ? value : value.substring(idx);
        var target = new File(this.build, "data/" + key + '/' + filename);

        var cache = HashStore.fromFile(target);
        cache.add("data", this.data);

        if (target.exists() && cache.isSame())
            return target;

        GlobalOptions.assertNotCacheOnly();

        try (var zip = new ZipFile(this.data)) {
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
            throw except("Failed to extract `" + key + "`: `" + value + "`", e);
        }
    }

    public static File modifyAccess(File globalBase, Task inputTask, File cfg, Cache dlCache) {
        var input = inputTask.execute();
        var tool = dlCache.maven().download(Constants.ACCESS_TRANSFORMER);

        var output = new File(globalBase, "modifyAccess.jar");
        var log    = new File(globalBase, "modifyAccess.log");

        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("input", input);
        cache.add("cfg", cfg);

        if (output.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        var args = List.of(
            "--inJar", input.getAbsolutePath(),
            "--atfile", cfg.getAbsolutePath(),
            "--outJar", output.getAbsolutePath()
        );

        var jdk = dlCache.jdks().get(Constants.ACCESS_TRANSFORMER_JAVA_VERSION);
        if (jdk == null)
            throw new IllegalStateException("Failed to find JDK for version " + Constants.ACCESS_TRANSFORMER_JAVA_VERSION);

        var ret = ProcessUtils.runJar(jdk, globalBase, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run Access Transformer, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    public static File stripSides(File globalBase, Task inputTask, File cfg, Cache dlCache) {
        var input = inputTask.execute();
        var tool = dlCache.maven().download(Constants.SIDE_STRIPPER);
        var output = new File(globalBase, "stripSides.jar");
        var log    = new File(globalBase, "stripSides.log");
        var cache = HashStore.fromFile(output);
        cache.add("tool", tool);
        cache.add("input", input);
        cache.add("cfg", cfg);

        if (output.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        var args = new ArrayList<String>();
        args.add("--strip");
        args.add("--input");
        args.add(input.getAbsolutePath());
        args.add("--data");
        args.add(cfg.getAbsolutePath());
        args.add("--output");
        args.add(output.getAbsolutePath());

        var jdk = dlCache.jdks().get(Constants.SIDE_STRIPPER_JAVA_VERSION);
        if (jdk == null)
            throw new IllegalStateException("Failed to find JDK for version " + Constants.SIDE_STRIPPER_JAVA_VERSION);

        var ret = ProcessUtils.runJar(jdk, globalBase, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run Side Stripper, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private Task completeMcp(File globalBase, Task inputTask) {
        var mcp = getMCP().getSide(MCPSide.JOINED);
        var taskFactory = mcp.getTasks().child(globalBase, inputTask);
        return taskFactory.getLastTask();
    }

    private Task postProcess(Task input, File outputDir) {
        var data = this.config.processor;
        var output = new File(outputDir, "post-processed.jar");
        var log = new File(outputDir, "post-processed.log");
        var deps = new HashSet<Task>();
        deps.add(input);

        for (var entry : data.data.entrySet())
            deps.add(extractSingle(entry.getKey(), entry.getValue()));

        return Task.named("postProcess[" + this.name.getName() + ']',
            deps,
            () -> postProcess(input, data, output, log)
        );
    }

    private File postProcess(Task inputTask, PatcherConfig.V2.DataFunction data, File output, File log) {
        var input = inputTask.execute();

        // First download the tool
        var maven = new MavenCache("mcp-tools", data.repo, this.forge.getCache().root());
        var toolA = Artifact.from(data.version);
        var tool = maven.download(toolA);

        var cache = HashStore.fromFile(output);
        cache.add("data", this.data);
        cache.add("tool", tool);
        cache.add("input", input);
        cache.add("jvm-args", data.getJvmArgs().stream().collect(Collectors.joining(" ")));
        cache.add("run-args", data.getArgs().stream().collect(Collectors.joining(" ")));

        // Extract any needed data
        var files = new HashMap<String, String>();
        files.put("{input}", input.getAbsolutePath());
        files.put("{output}", output.getAbsolutePath());
        for (var entry : data.data.entrySet()) {
            var extract = extractSingle(entry.getKey(), entry.getValue());
            var file = extract.execute();
            files.put('{' + entry.getKey() + '}', file.getAbsolutePath());
            cache.add(entry.getKey(), file);
        }

        if (output.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        var args = new ArrayList<String>();
        for (var arg : data.getArgs())
            args.add(files.getOrDefault(arg, arg));

        int java_version = data.getJavaVersion(this.getMCP().getConfig());
        var jdks = this.getMCP().getCache().jdks();
        var jdk = jdks.get(java_version);
        if (jdk == null)
            throw new IllegalStateException("Failed to find JDK for version " + java_version);


        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, data.getJvmArgs(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCP Step, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    private Task patch(Task input, File outputDir) {
        var output = new File(outputDir, "patched.jar");
        var rejects = new File(outputDir, "patched-rejects.jar");
        return Task.named("patch[" + this.name.getName() + ']',
            Set.of(input),
            () -> patch(input, output, rejects)
        );
    }

    private File patch(Task inputTask, File output, File rejects) {
        var input = inputTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("data", this.data);

        if (output.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        var builder = PatchOperation.builder()
            .logTo(Log::error)
            .baseInput(MultiInput.archive(ArchiveFormat.ZIP, input.toPath()))
            .patchesInput(MultiInput.archive(ArchiveFormat.ZIP, this.data.toPath()))
            .patchedOutput(MultiOutput.archive(ArchiveFormat.ZIP, output.toPath()))
            .rejectsOutput(MultiOutput.archive(ArchiveFormat.ZIP, rejects.toPath()))
            .level(LogLevel.ERROR)
            .mode(PatchMode.ACCESS)
            .patchesPrefix(this.config.patches)
        ;

        if (this.config.patchesOriginalPrefix != null)
            builder = builder.aPrefix(this.config.patchesOriginalPrefix);
        if (this.config.patchesModifiedPrefix != null)
            builder = builder.bPrefix(this.config.patchesModifiedPrefix);

        try {
            var result = builder.build().operate();

            boolean success = result.exit == 0;
            if (!success) {
                if (result.summary != null)
                    result.summary.print(Log.ERROR, true);
                else
                    Log.error("Failed to apply patches, no summary available");

                throw except("Failed to apply patches, rejects saved to: " + rejects.getAbsolutePath());
            }

            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    private Task injectSources(Task input, File outputDir) {
        if (this.downloadSources == null)
            return input;

        var output = new File(outputDir, "injected-sources.jar");
        return Task.named("injectSources[" + this.name.getName() + ']',
            Set.of(input, this.downloadSources),
            () -> injectSourcesImpl(input, output)
        );
    }

    private File injectSourcesImpl(Task inputTask, File output) {
        var input = inputTask.execute();
        var sources = this.downloadSources.execute();

        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("sources", sources);

        if (output.exists() && cache.exists())
            return output;

        GlobalOptions.assertNotCacheOnly();

        try {
            FileUtils.mergeJars(output, false,
                (file, path) -> file != sources || !path.startsWith("patches/"),
                sources, input
            );
        } catch (IOException e) {
            return Util.sneak(e);
        }

        cache.save();
        return output;
    }
}