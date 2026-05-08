/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import io.codechicken.diffpatch.cli.PatchOperation;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.PatchMode;
import io.codechicken.diffpatch.util.Input.MultiInput;
import io.codechicken.diffpatch.util.Output.MultiOutput;
import io.codechicken.diffpatch.util.archiver.ArchiveFormat;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.ContextualPatch;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.data.json.RunConfig;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

public class FG2Userdev implements ForgeVersionCommon {
    private final File build;
    private final ForgeRepo forge;
    private final Artifact name;
    private final FGVersion fgVersion;
    private final File data;
    private final String dataHash;
    private final MinecraftVersion config;
    private final String mcVersion;
    private final String forgeVersion;
    private final @Nullable byte[] extraMappings;
    private final MCPLegacy mcp;
    private final MCPLegacy.Child mcpChild;
    private final Task sourcesTask;
    private final Map<String, Task> extracts = new HashMap<>();
    private final Task patches;

    FG2Userdev(File build, ForgeRepo forge, Artifact name, FGVersion fgVersion) {
        this.build = build;
        this.forge = forge;
        this.name = name;
        this.fgVersion = fgVersion;

        this.data = this.forge.getCache().maven().download(name);
        if (!this.data.exists())
            throw new IllegalStateException("Failed to download " + name);

        this.dataHash = Util.sneak(() -> HashFunction.sha1().hash(this.data));

        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry("dev.json");
            if (entry == null)
                throw except("Missing dev.json");

            var cfg_data = zip.getInputStream(entry).readAllBytes();
            this.config = JsonData.minecraftVersion(cfg_data);

            entry = zip.getEntry("merged.srg");
            this.extraMappings = entry == null ? null : zip.getInputStream(entry).readAllBytes();
        } catch (IOException e) {
            throw except("Error reading config", e);
        }

        this.mcVersion = Util.forgeToMcVersion(this.name.getVersion());

        var legacyMCP = StupidHacks.legacyMcp(new ComparableVersion(this.name.getVersion()));
        this.mcp = this.forge.mcpconfig.legacy(this.mcVersion, legacyMCP);
        this.mcpChild = this.mcp.getChild(this);

        this.forgeVersion = this.name.getVersion().substring(this.name.getVersion().indexOf('-') + 1);

        this.patches = extract("patches.zip");
        this.sourcesTask = patch();
    }

    public File getBuildFolder() {
        return this.build;
    }

    public Cache getCache() {
        return this.forge.getCache();
    }

    public Artifact getName() {
        return this.name;
    }

    @Override
    public String getMinecraftVersion() {
        return this.mcVersion;
    }

    public String getForgeVersion() {
        return this.forgeVersion;
    }

    public FGVersion getGradleVersion() {
        return this.fgVersion;
    }

    public MCPLegacy getMCP() {
        return this.mcp;
    }

    public @Nullable byte[] getExtraMappings() {
        return this.extraMappings;
    }

    public Task getSources() {
        return this.sourcesTask;
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid FG2 Dependency: " + this.name + " - " + message);
    }

    private RuntimeException except(String message, Throwable e) {
        return new IllegalArgumentException("Invalid FG2 Dependency: " + this.name + " - " + message, e);
    }

    @Override
    public String getDataHash() {
        return this.dataHash;
    }

    @Override
    public int getJavaTarget() {
        // Older minecraft supported java 6, but it's not really possible to get a version of that these days, so we've been using 8 for over a decade
        return 8;
    }

    @Override
    public Artifact getMCPArtifact() {
        return this.getMCP().getName();
    }

    @Override
    public @Nullable List<String> getModules() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getCompileOnly() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRuntimeOnly() {
        return Collections.emptyList();
    }

    @Override
    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        for (var library : this.config.getLibs()) {
            var artifact = Artifact.from(library.coord).withOS(library.os);
            if (filter == null || filter.test(artifact))
                consumer.accept(artifact);
        }
        for (var library : this.getMCP().getMinecraftTasks().getClientLibraries()) {
            if (filter == null || filter.test(library.artifact()))
                consumer.accept(library.artifact());
        }
    }

    @Override
    public List<Artifact> getLibraries() {
        var ret = new ArrayList<Artifact>();
        // We don't support OS specific/classified libraries on these old versions because they were made before
        // Mojang supported them. So just get them all via the name like FG2 does
        for (var lib : this.config.libraries)
            ret.add(Artifact.from(lib.name));
        return ret;
    }

    @Override
    public List<File> getClasspath() {
        var classpath = new ArrayList<File>();
        var seen = new HashSet<String>();

        // minecraft version.json libs + userdev libs
        for (var lib : this.getMCP().getMinecraftTasks().getClientLibraries()) {
            classpath.add(lib.file());
            // We just want the group:name and clssifier, incase they have upated the version since we were built
            seen.add(lib.artifact().withVersion(null).toString());
        }

        var cache = this.forge.getCache();
        if (this.config.inheritsFrom == null) {
            // If there is no inherits, this is before Mojang added that feature to the launcher
            // So we need to try and strip out any libraries that come from the vanilla launcher.
            for (var lib : this.config.libraries) {
                var artifact = StupidHacks.fixLegacyForgeDeps(Artifact.from(lib.name));
                var unversioned = artifact.withVersion(null).toString();
                if (!seen.contains(unversioned)) {
                    if (artifact != null)
                        classpath.add(Util.getArtifact(cache, artifact));
                }
            }
        } else {
            for (var lib : this.config.libraries) {
                var artifact = StupidHacks.fixLegacyForgeDeps(Artifact.from(lib.name));
                if (artifact != null)
                    classpath.add(Util.getArtifact(cache, artifact));
            }
        }

        return classpath;
    }

    @Override
    public MinecraftTasks getMinecraftTasks() {
        return this.getMCP().getMinecraftTasks();
    }

    public Task extract(String name) {
        var ret = this.extracts.get(name);
        if (ret == null) {
            ret = Task.named("extract[" + name + ']', () -> extractImpl(name));
            this.extracts.put(name, ret);
        }
        return ret;
    }

    private File extractImpl(String name) {
        var target = new File(new File(this.build, "data"), name);
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

    private Task patch() {
        var input = inject();
        var patches = StupidHacks.needsPatchFixes(this.name)
            ? fixPatches(this.patches)
            : this.patches;

        return Task.named("patch",
            Task.deps(input, patches),
            () -> this.patch(input.execute(), patches.execute())
        );
    }

    private Task fixPatches(Task base) {
        var output = new File(this.build, "fixed-patches.zip");
        return Task.named("fix-patches", Task.deps(base), () ->
            StupidHacks.fixPatches(this.name, base.execute(), output)
        );
    }

    private File patch(File input, File patches) {
        var output = new File(this.build, "patched.jar");
        var rejects = new File(this.build, "patches-rejects.jar");

        var cache = Util.cache(output)
            .add("input", input)
            .add("patches", patches)
            .addKnown("fg", this.fgVersion.name())
            ;

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        if (output.exists())
            output.delete();
        if (rejects.exists())
            rejects.delete();

        if (this.fgVersion == FGVersion.v2)
            patchUnstable(input, patches, output, rejects);
        else
            patchStable(input, patches, output, rejects);

        cache.save();
        return output;
    }

    private void patchUnstable(File input, File patchesArchive, File output, File rejectOutput) {
        final var patches = new HashMap<String, ContextualPatch>();

        // Gather all of our patches
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(patchesArchive))) {
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!name.endsWith(".patch"))
                    continue;

                var filename = name.substring(0, name.length() - 6);
                var patch = ContextualPatch.create(zin)
                    .setAccessC14N(true)
                    .setMaxFuzz(2);

                patches.put(filename, patch);
            }
        } catch (IOException e) {
            Util.sneak(e);
        }


        // Apply them
        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))
         ) {
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();
                var patch = patches.get(name);
                var newEntry = new ZipEntry(name);
                newEntry.setTime(entry.getTime());
                zout.putNextEntry(newEntry);

                if (patch == null) {
                    zin.transferTo(zout);
                } else {
                    var ctx = ContextualPatch.context(zin);
                    var result = patch.patchSingle(false, ctx);

                    if (!result.getStatus().isSuccess()) {
                        LOGGER.error("Fialed to patch " + name);
                        LOGGER.error("  Input:   " + input.getAbsolutePath());
                        LOGGER.error("  Patches: " + patchesArchive.getAbsolutePath());
                        LOGGER.error("  Output:  " + output.getAbsolutePath());

                        for (var hunk : result.getHunks())
                            LOGGER.error("  Hunk #" + hunk.getHunkID() + ": " + hunk.getStatus().name());

                        Util.sneak(result.getFailure());
                    }
                    for (var itr = ctx.getData().iterator(); itr.hasNext();) {
                        var line = itr.next();
                        zout.write(line.getBytes(StandardCharsets.UTF_8));
                        if (itr.hasNext())
                            zout.write('\n');
                    }
                }
                zout.closeEntry();
            }
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    // Modern versions that use out Fernflower fork produce stabelized output so we can use
    // DiffPatch. But on older verions we need both ACCESS and OFFSET changes at the same time
    // And if we tried to do that with DiffPatch we would need to use FUZZY which is TOO Fuzzy.
    // it causes patches to be injected half-way and in weird places.
    private void patchStable(File input, File patches, File output, File rejects) {
        var builder = PatchOperation.builder()
            .logTo(LOGGER::error)
            .baseInput(MultiInput.archive(ArchiveFormat.ZIP, input.toPath()))
            .patchesInput(MultiInput.archive(ArchiveFormat.ZIP, patches.toPath()))
            .patchedOutput(MultiOutput.archive(ArchiveFormat.ZIP, output.toPath()))
            .rejectsOutput(MultiOutput.archive(ArchiveFormat.ZIP, rejects.toPath()))
            .level(LogLevel.ERROR)
            .mode(PatchMode.ACCESS)
            .aPrefix("/")
            .bPrefix("/")
        ;

        try {
            var result = builder.build().operate();

            boolean success = result.exit == 0;
            if (!success) {
                LOGGER.error("Fialed to apply patches");
                LOGGER.error("  Input:   " + input.getAbsolutePath());
                LOGGER.error("  Patches: " + patches.getAbsolutePath());
                LOGGER.error("  Output:  " + output.getAbsolutePath());
                LOGGER.error("  Rejects: " + rejects.getAbsolutePath());
                if (result.summary != null)
                    result.summary.print(LOGGER.getError(), true);
                else
                    LOGGER.error("Failed to apply patches, no summary available");

                throw except("Failed to apply patches, rejects saved to: " + rejects.getAbsolutePath());
            }
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    private Task inject() {
        var input = this.mcpChild.getFinalStep();
        var sources = extract("sources.zip");
        var resources = extract("resources.zip");
        return Task.named("inject",
            Task.deps(input, sources, resources),
            () -> this.inject(input.execute(), sources.execute(), resources.execute())
        );
    }

    private File inject(File input, File sources, File resources) {
        var output = new File(this.build, "injected.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .add("sources", sources)
            .add("resources", resources);

        if (Mavenizer.checkCache(output, cache))
            return output;

        try {
            FileUtils.mergeJars(output, false, resources, sources, input);
            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    /*
    private Task mcpPatch() {
        var input = decompileCleanup();
        return Task.named("mcp-patch",
            Task.deps(input), () -> mcpPatch(input)
        );
    }

    private File mcpPatch(Task inputTask) {
        var input = inputTask.execute();
        var output = new File(this.build, "mcp-patched.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .addKnown("mcp", this.getMCP().getDataHash());

        //if (Mavenizer.checkCache(output, cache))
        //    return output;

        var patchesMap = new HashMap<String, List<PatchFile>>();
        try (var zin = new ZipInputStream(new FileInputStream(this.getMCP().getData()))) {
            final var prefix = "patches/minecraft_merged_ff/";
            for (ZipEntry entry; (entry = zin.getNextEntry()) != null;) {
                var name = entry.getName();
                if (!name.startsWith(prefix))
                    continue;

                int patchIndex = name.indexOf(".patch");
                // 6 is the length of ".patch" + 3 to account for .## at the end of the file.
                if (patchIndex < 0 || patchIndex < name.length() - 9)
                    continue;

                var filename = name.substring(prefix.length(), patchIndex);
                var lines = new ArrayList<String>();
                boolean first = true;
                @SuppressWarnings("resource")
                var reader = new NewLineDetector(new InputStreamReader(zin, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) {
                    // Old patches are generated with the 'diff' comand as the first line
                    // And DiffPatch doesn't support that so we need to skip it
                    if (!(first && line.startsWith("diff ")))
                        lines.add(line);
                    first = false;
                }

                var patch = PatchFile.fromLines(name, lines, true);

                // Some old patches were trimmed, so the last empty line of context was removed. So lets try and detect that case
                var last = patch.patches.getLast();
                if (last.getContextLines().size() != last.length1)
                    last.recalculateLength();

                System.out.println("Patch: " + name);
                patchesMap.computeIfAbsent(filename, _ -> new ArrayList<>()).add(patch);
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        FileUtils.ensureParent(output);
        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();
                var newEntry = new ZipEntry(name);
                newEntry.setTime(entry.getTime());
                zout.putNextEntry(newEntry);

                if (!name.endsWith(".java")) {
                    zin.transferTo(zout);
                    zout.closeEntry();
                    continue;
                }

                List<String> lines = NewLineDetector.readLines(zin);
                var patches = patchesMap.get(name.replace('/', '.'));
                if (patches != null && !patches.isEmpty()) {
                    List<io.codechicken.diffpatch.patch.Patcher.Result> results = null;
                    boolean success = false;
                    for (var patch : patches) {
                        var patcher = new io.codechicken.diffpatch.patch.Patcher(patch, lines, 1.0F, FuzzyLineMatcher.MatchMatrix.DEFAULT_MAX_OFFSET);
                        results = patcher.patch(PatchMode.FUZZY); // We need to use FUZZ because we inject annotations which causes an OFFSET, and apply ATs which requires ACCESS
                        success = results.stream().allMatch(r -> r.success);
                        if (success) {
                            lines = patcher.lines;
                            break;
                        }
                    }

                    if (!success) {
                        LOGGER.push();
                        LOGGER.error("Input:  " + input.getAbsolutePath());
                        LOGGER.error("Output: " + output.getAbsolutePath());
                        LOGGER.error("MCP:    " + this.getMCP().getData().getAbsolutePath());
                        LOGGER.error("Failed to apply patch to " + name);
                        for (int x = 0; x < results.size(); x++)
                            LOGGER.error("  #" + x + ": " + results.get(x).summary());
                        LOGGER.pop();
                        throw new IllegalStateException("Failed to apply patch to " + name);
                    }
                }

                for (var line : lines) {
                    zout.write(line.getBytes(StandardCharsets.UTF_8));
                    zout.write('\n');
                }

                zout.closeEntry();
            }
        } catch (IOException e) {
            return Util.sneak(e);
        }

        cache.save();
        return output;
    }

    private Task decompileCleanup() {
        var input = decompile();
        return Task.named("decompile-cleanup",
            Task.deps(input), () -> decompileCleanup(input.execute())
        );
    }

    private File decompileCleanup(File input) {
        var tool = this.forge.getCache().maven().download(Constants.MCPCLEANUP);
        var output = new File(this.build, "decompile-cleaned.jar");
        var log = new File(this.build, "decompile-cleaned.log");
        var cache = Util.cache(output)
            .add("tool", tool)
            .add("input", input)
            .addKnown("fg", this.fgVersion.name());

        if (Mavenizer.checkCache(output, cache))
            return output;

        var args = new ArrayList<String>(5);
        args.addAll(List.of(
            "--input", input.getAbsolutePath(),
            "--output", output.getAbsolutePath(),
            "--pre-patch"
        ));
        if (this.fgVersion == FGVersion.v2)
            args.add("--fernflower"); // Version 2 is before we used our own fernflower fork, so we did a lot of pre-mcp cleanup

        var jdk = jdk(Constants.MCPCLEANUP_JAVA_VERSION);
        var ret = ProcessUtils.runJar(jdk, this.globalBase, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to run MCPCleanup (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }
    */

    public Map<String, RunConfig> getRuns() {
        // Use LinkedHashMap to keep things stable
        var serverEnv = new LinkedHashMap<String, String>();
        serverEnv.put("MCP_TO_SRG",    "{mcp_to_srg}");
        serverEnv.put("mainClass",     "net.minecraft.launchwrapper.Launch");
        serverEnv.put("MCP_MAPPINGS",  "{mcp_mappings}");
        serverEnv.put("FORGE_VERSION", this.getForgeVersion());
        serverEnv.put("FORGE_GROUP",   "net.minecraftforge");
        serverEnv.put("MC_VERSION",    this.getMinecraftVersion());
        serverEnv.put("tweakClass",    "net.minecraftforge.fml.common.launcher.FMLServerTweaker");

        var clientEnv = new LinkedHashMap<>(serverEnv);
        clientEnv.put("tweakClass",       "net.minecraftforge.fml.common.launcher.FMLTweaker");
        clientEnv.put("assetIndex",       "{asset_index}");
        clientEnv.put("assetDirectory",   "{assets_root}");
        clientEnv.put("nativesDirectory", "{natives}");

        var ret = new LinkedHashMap<String, RunConfig>();
        ret.put("client", run(true, clientEnv));
        ret.put("server", run(false, serverEnv));
        return ret;
    }

    private static RunConfig run(boolean client, Map<String, String> env) {
        var ret = new RunConfig();
        if (client) {
            ret.name = "client";
            ret.main = "net.minecraftforge.legacydev.MainClient";
        } else {
            ret.name = "server";
            ret.main = "net.minecraftforge.legacydev.MainServer";
        }
        ret.client = client;
        ret.env = env;
        return ret;
    }
}
