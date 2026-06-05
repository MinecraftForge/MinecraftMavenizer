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
import java.nio.file.Files;
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

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks.ArtifactFile;
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

        // FG 1.x we need to merge the access transformers, FML and Forge patches are seperate, and source/resources are not in a zip
        var fg1 = this.fgVersion.ordinal() <= FGVersion.v1_2.ordinal();

        var legacyMCP = StupidHacks.legacyMcp(new ComparableVersion(this.name.getVersion()));
        this.mcp = this.forge.mcpconfig.legacy(this.mcVersion, legacyMCP);
        var atFile = fg1 ? mergeAts() : extract("merged_at.cfg");
        // 1.7.2 Forge switched to FG1.2 in 1.7.2-10.12.0.1048, this changed our decompiler to
        // a version that bulk sorts all classes. So produces different decomp.
        var legacyFG = "1.7.2".equals(this.mcVersion) && this.fgVersion != FGVersion.v1_2 ? this.fgVersion : null;
        this.mcpChild = this.mcp.getChild(this, atFile, legacyFG);

        this.forgeVersion = this.name.getVersion().substring(this.name.getVersion().indexOf('-') + 1);

        // FG 1.1 needs to remap between FML and Forge patches
        if (this.fgVersion == FGVersion.v1 || this.fgVersion == FGVersion.v1_1)
            this.sourcesTask = patchForgeRenamed();
        else if (this.fgVersion == FGVersion.v1_2)
            this.sourcesTask = patchForge();
        else
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
        return this.mcp.getJavaTarget();
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

        /*
        for (var library : this.config.getLibs()) {
            var artifact = StupidHacks.fixLegacyForgeDeps(Artifact.from(library.coord));
            if (artifact == null || library.dl == null)
                continue;
            artifact = artifact.withOS(library.os);
            if (filter == null || filter.test(artifact)) {
                System.out.println("Dev: " + artifact + " " + artifact.getOs());
                consumer.accept(artifact);
            }
        }
        for (var library : this.getMCP().getMinecraftTasks().getClientLibraries()) {
            if (filter == null || filter.test(library.artifact())) {
                System.out.println("MC:  " + library.artifact() + " " + library.artifact().getOs());
                consumer.accept(library.artifact());
            }
        }
        */
        var libs = getLibraryFiles();
        for (var lib : libs) {
            if (filter == null || filter.test(lib.artifact())) {
                consumer.accept(lib.artifact());
            }
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
        var ret = new ArrayList<File>();
        for (var lib : getLibraryFiles())
            ret.add(lib.file());
        return ret;

    }
    public List<ArtifactFile> getLibraryFiles() {
        var classpath = new ArrayList<ArtifactFile>();
        var seen = new HashMap<String, Artifact>();
        var cache = this.forge.getCache();

        // minecraft version.json libs + userdev libs
        var mc = this.getMCP().getMinecraftTasks();
        var libs = mc.getClientLibraries();
        for (var lib : libs) {

            // We might have to upgrade a vanilla dependency
            var artifact = StupidHacks.fixLegacyForgeDeps(lib.artifact());
            if (artifact == null)
                continue;

            if (artifact != lib.artifact())
                classpath.add(new ArtifactFile(artifact, Util.getArtifact(cache, artifact, true)));
            else
                classpath.add(lib);

            // We just want the group:name and clssifier, in case they have updated the version since we were built
            seen.put(artifact.withVersion(null).toString(), artifact);
        }

        if (this.config.inheritsFrom == null) {
            // If there is no inherits, this is before Mojang added that feature to the launcher
            // So we need to try and strip out any libraries that come from the vanilla launcher.
            for (var lib : this.config.libraries) {
                var artifact = StupidHacks.fixLegacyForgeDeps(Artifact.from(lib.name));
                var unversioned = artifact.withVersion(null).toString();
                if (!seen.containsKey(unversioned)) {
                    if (artifact != null)
                        classpath.add(new ArtifactFile(artifact, Util.getArtifact(cache, artifact, true)));
                }
            }
        } else {
            for (var lib : this.config.libraries) {
                var artifact = StupidHacks.fixLegacyForgeDeps(Artifact.from(lib.name));
                if (artifact != null)
                    classpath.addFirst(new ArtifactFile(artifact, Util.getArtifact(cache, artifact, true))); // Add our versions before vanilla's in case we upgrade
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
            ? fixPatches(extract("patches.zip"))
            :            extract("patches.zip");

        return Task.named("patch",
            Task.deps(input, patches),
            () -> this.patch(input.execute(), patches.execute(), "patched")
        );
    }

    private Task fixPatches(Task base) {
        var output = new File(this.build, "fixed-patches.zip");
        return Task.named("fix-patches", Task.deps(base), () ->
            StupidHacks.fixPatches(this.name, base.execute(), output)
        );
    }

    private File patch(File input, File patches, String name) {
        var output = new File(this.build, name + ".jar");
        var rejects = new File(this.build, name + "-rejects.jar");

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

        patchUnstable(input, patches, output, rejects);

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

        Throwable failure = null;
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
                        if (failure == null) {
                            LOGGER.error("Input:   " + input.getAbsolutePath());
                            LOGGER.error("Patches: " + patchesArchive.getAbsolutePath());
                            LOGGER.error("Output:  " + output.getAbsolutePath());
                        }

                        LOGGER.error("Fialed to patch " + name);
                        for (var hunk : result.getHunks())
                            LOGGER.error("  Hunk #" + hunk.getHunkID() + ": " + hunk.getStatus().name());

                        failure = result.getFailure();
                    } else {
                        for (var itr = ctx.getData().iterator(); itr.hasNext();) {
                            var line = itr.next();
                            zout.write(line.getBytes(StandardCharsets.UTF_8));
                            if (itr.hasNext())
                                zout.write('\n');
                        }
                    }
                }
                zout.closeEntry();
            }
        } catch (IOException e) {
            Util.sneak(e);
        }

        if (failure != null)
            Util.sneak(failure);
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

    private Task mergeAts() {
        var fml = extract("src/main/resources/fml_at.cfg");
        var forge = extract("src/main/resources/forge_at.cfg");
        return Task.named("merge-ats",
            Task.deps(fml, forge), () -> this.mergeAts(fml.execute(), forge.execute())
        );
    }

    private File mergeAts(File fml, File forge) {
        var output = new File(this.build, "merged-at.cfg");
        var cache = Util.cache(output)
            .add("fml", fml)
            .add("forge", forge);

        if (Mavenizer.checkCache(output, cache))
            return output;

        try (var out = new FileOutputStream(output)) {
            out.write(Files.readAllBytes(fml.toPath()));
            out.write("\n# Forge\n".getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(forge.toPath()));
            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    private Task injectLegacy() {
        var input = this.mcpChild.getFinalStep();
        return Task.named("inject",
            Task.deps(input),
            () -> this.injectLegacy(input.execute())
        );
    }

    private File injectLegacy(File input) {
        var output = new File(this.build, "injected.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .addKnown("data", this.dataHash);

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try(var out = new ZipOutputStream(new FileOutputStream(output))) {
            var seen = new HashSet<String>();
            try (var zin = new ZipInputStream(new FileInputStream(input))) {
                for (ZipEntry entry = null; (entry = zin.getNextEntry()) != null; ) {
                    seen.add(entry.getName());
                    out.putNextEntry(FileUtils.getStableEntry(entry.getName()));
                    zin.transferTo(out);
                    out.closeEntry();
                }
            }
            try (var zin = new ZipInputStream(new FileInputStream(this.data))) {
                for (ZipEntry entry = null; (entry = zin.getNextEntry()) != null; ) {
                    var name = entry.getName();
                    if (entry.isDirectory())
                        continue;
                    else if (name.startsWith("src/main/resources/"))
                        name = name.substring(19);
                    else if (name.startsWith("src/main/java/"))
                        name = name.substring(14);
                    else
                        continue;

                    if (!seen.add(name))
                        continue;

                    out.putNextEntry(FileUtils.getStableEntry(name));
                    zin.transferTo(out);
                    out.closeEntry();
                }
            }
            cache.save();
            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    private Task patchFml() {
        var base = injectLegacy();
        var patches = extract("fmlpatches.zip");
        return Task.named("patch-fml",
            Task.deps(base, patches), () -> this.patch(base.execute(), patches.execute(), "patched-fml")
        );
    }

    private Task patchForge() {
        var base = patchFml();
        var patches = extract("forgepatches.zip");
        return Task.named("patch",
            Task.deps(base, patches), () -> this.patch(base.execute(), patches.execute(), "patched")
        );
    }

    private Task rename() {
        var input = patchFml();
        return Task.named("rename",
            Task.deps(input), () -> this.rename(input.execute())
        );
    }

    private File rename(File input) {
        var output = new File(this.build, "renamed.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .addKnown("mappings", this.dataHash);

        if (Mavenizer.checkCache(output, cache))
            return output;

        if (this.fgVersion == FGVersion.v1)
            LegacyRenamer.renameFG_1_0(input, this.data, output);
        else
            LegacyRenamer.rename(input, this.data, output, true);

        cache.save();
        return output;
    }

    private Task patchForgeRenamed() {
        var base = rename();
        var patches = extract("forgepatches.zip");
        return Task.named("patch",
            Task.deps(base, patches), () -> this.patch(base.execute(), patches.execute(), "patched")
        );
    }

    public Task getSourcesWithJavadocs() {
        var input = this.getSources();

        // FG v1 applied javadocs before Forge patches, so just return the source
        if (this.fgVersion == FGVersion.v1)
            return input;

        return Task.named("javadocs",
            Task.deps(input), () -> this.javadocs(input.execute())
        );
    }

    private File javadocs(File input) {
        var output = new File(this.build, "patched-javadocs.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .addKnown("mappings", this.dataHash);

        if (Mavenizer.checkCache(output, cache))
            return output;

        LegacyRenamer.rename(input, this.data, output, false);

        cache.save();
        return output;
    }

    public Map<String, RunConfig> getRuns() {
        var mc = new ComparableVersion(this.mcp.getMinecraftTasks().getVersion());
        var prefix = "cpw.mods.";
        if (mc.compareTo(new ComparableVersion("1.8")) >= 0)
            prefix = "net.minecraftforge.";

        // Use LinkedHashMap to keep things stable
        var serverEnv = new LinkedHashMap<String, String>();
        serverEnv.put("MCP_TO_SRG",    "{mcp_to_srg}");
        serverEnv.put("mainClass",     "net.minecraft.launchwrapper.Launch");
        serverEnv.put("MCP_MAPPINGS",  "{mcp_mappings}");
        serverEnv.put("FORGE_VERSION", this.getForgeVersion());
        serverEnv.put("FORGE_GROUP",   "net.minecraftforge");
        serverEnv.put("MC_VERSION",    this.getMinecraftVersion());
        serverEnv.put("tweakClass",    prefix + "fml.common.launcher.FMLServerTweaker");

        var clientEnv = new LinkedHashMap<>(serverEnv);
        clientEnv.put("tweakClass",       prefix + "fml.common.launcher.FMLTweaker");
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
