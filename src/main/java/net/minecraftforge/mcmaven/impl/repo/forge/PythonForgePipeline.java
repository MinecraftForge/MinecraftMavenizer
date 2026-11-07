/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ContextualPatch;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.json.RunConfig;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

final class PythonForgePipeline implements ForgeVersionCommon {
    private static final Artifact FML_ARGO = Artifact.from("net.sourceforge.argo:argo:3.2");
    private static final Artifact RUNTIME_ARGO = Artifact.from("net.sourceforge.argo:argo:2.25");
    private static final List<Artifact> FML_LIBRARIES = List.of(
        Artifact.from("com.google.guava:guava:14.0-rc3"),
        Artifact.from("org.bouncycastle:bcprov-jdk15on:1.48")
    );
    private static final List<String> EMBEDDED_LIBRARY_PACKAGES = List.of(
        "com/jcraft/jogg/",
        "com/jcraft/jorbis/",
        "paulscode/sound/"
    );
    private static final List<String> EXTERNAL_LIBRARY_SOURCE_PACKAGES = List.of("org/bouncycastle/");

    private final File build;
    private final File sourceZip;
    private final File assets;
    private final Artifact name;
    private final MCPLegacy mcp;
    private final String dataHash;
    private final Map<String, Task> extracts = new HashMap<>();
    private final Task sources;

    PythonForgePipeline(File build, Artifact name, File sourceZip, File assets, MCPLegacy mcp) {
        this.build = build;
        this.name = name;
        this.sourceZip = sourceZip;
        this.assets = assets;
        this.mcp = mcp;
        this.dataHash = Util.sneak(() -> HashFunction.sha1().hash(sourceZip));

        var mergedAt = Task.named("merge-python-access-transformers", () -> mergeAccessTransformers());
        var childBuild = new File(this.mcp.getBuildFolder(), name.getVersion() + "-python");
        var child = this.mcp.new Child(childBuild, mergedAt.execute(), null);
        var clientJar = this.mcp.getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR);
        var preparedSources = Task.named("prepare-python-mcp-sources",
            Task.deps(child.getFinalStep(), clientJar),
            () -> preparePythonSources(child.getFinalStep().execute(), clientJar.execute())
        );
        var fml = Task.named("apply-python-fml-patches",
            Task.deps(preparedSources),
            () -> applyStage(preparedSources.execute(), "fmlpatches.zip", true, "overlays/fml/")
        );
        var renamedFml = Task.named("rename-python-fml-sources",
            Task.deps(fml), () -> renameForForgePatches(fml.execute())
        );
        this.sources = Task.named("apply-python-forge-patches",
            Task.deps(renamedFml),
            () -> applyStage(renamedFml.execute(), "forgepatches.zip", false, "overlays/forge/")
        );
    }

    private File renameForForgePatches(File input) {
        var output = new File(this.build, "renamed-fml-sources.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .add("mappings", this.mcp.getData())
            .addKnown("renamer", "legacy-python-between-patches-v4");
        if (Mavenizer.checkCache(output, cache))
            return output;

        LegacyRenamer.renameFG_1_0(input, this.mcp.getData(), output);
        cache.save();
        return output;
    }

    private File mergeAccessTransformers() {
        var output = new File(this.build, "merged-at.cfg");
        var fmlAt = extract("fml_at.cfg").execute();
        var forgeAt = extract("forge_at.cfg").execute();
        var cache = Util.cache(output)
            .add("fml", fmlAt)
            .add("forge", forgeAt)
            .addKnown("pipeline", "python-at-v1");
        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var out = new FileOutputStream(output)) {
            out.write(java.nio.file.Files.readAllBytes(fmlAt.toPath()));
            out.write("\n# Forge\n".getBytes(StandardCharsets.UTF_8));
            out.write(java.nio.file.Files.readAllBytes(forgeAt.toPath()));
            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to merge Python-era access transformers", e);
        }
    }

    private File preparePythonSources(File input, File clientJar) {
        var output = new File(this.build, "prepared-mcp-sources.jar");
        var cache = Util.cache(output)
            .add("input", input)
            .add("client", clientJar)
            .addKnown("library-filter", "python-pre16-embedded-libraries-v2");
        if (Mavenizer.checkCache(output, cache))
            return output;

        var entries = readArchive(input);
        restoreEmbeddedLibraries(entries, clientJar);
        writeArchive(output, entries);
        cache.save();
        return output;
    }

    static void restoreEmbeddedLibraries(Map<String, byte[]> entries, File clientJar) {
        entries.keySet().removeIf(path -> path.endsWith(".java")
            && (isEmbeddedLibrary(path) || isExternalLibrarySource(path)));
        var restoredClasses = 0;
        try (var zip = new ZipFile(clientJar)) {
            for (var archiveEntries = zip.entries(); archiveEntries.hasMoreElements(); ) {
                var entry = archiveEntries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || !isEmbeddedLibrary(entry.getName()))
                    continue;
                try (var stream = zip.getInputStream(entry)) {
                    if (entries.putIfAbsent(entry.getName(), stream.readAllBytes()) != null)
                        throw new IllegalStateException("Duplicate embedded library class in Python-era source archive: " + entry.getName());
                }
                restoredClasses++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore embedded libraries from Minecraft client JAR " + clientJar.getAbsolutePath(), e);
        }
        if (restoredClasses == 0)
            throw new IllegalStateException("Minecraft client JAR contains no embedded audio library classes: " + clientJar.getAbsolutePath());
    }

    private static boolean isEmbeddedLibrary(String path) {
        return EMBEDDED_LIBRARY_PACKAGES.stream().anyMatch(path::startsWith);
    }

    private static boolean isExternalLibrarySource(String path) {
        return EXTERNAL_LIBRARY_SOURCE_PACKAGES.stream().anyMatch(path::startsWith);
    }

    private Task extract(String entryName) {
        return this.extracts.computeIfAbsent(entryName, name -> Task.named("extract-python[" + name + ']', () -> {
            var output = new File(this.build, "data/" + name);
            var cache = Util.cache(output)
                .add("assets", this.assets)
                .addKnown("entry", name);
            if (Mavenizer.checkCache(output, cache))
                return output;

            try (var zip = new ZipFile(this.assets)) {
                var entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory())
                    throw new IllegalStateException("Python-era Forge assets are missing " + name);
                FileUtils.ensureParent(output);
                try (var input = zip.getInputStream(entry);
                     var out = new FileOutputStream(output)) {
                    input.transferTo(out);
                }
                cache.save();
                return output;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to extract Python-era Forge asset " + name, e);
            }
        }));
    }

    private File applyStage(File input, String patchArchive, boolean removeGeneratedSources, String overlayPrefix) {
        var output = new File(this.build, patchArchive.replace(".zip", "") + "-sources.jar");
        var patchFile = extract(patchArchive).execute();
        var overlayFiles = listOverlayFiles(overlayPrefix);
        var cache = Util.cache(output)
            .add("input", input)
            .add("patches", patchFile)
            .add("assets", this.assets)
            .addKnown("overlay", overlayPrefix)
            .addKnown("remove-generated", Boolean.toString(removeGeneratedSources))
            .addKnown("patcher", "python-contextual-patch-v1");
        if (!removeGeneratedSources)
            cache.addKnown("compile-fixes", "python-pre16-source-fixes-v4");
        if (Mavenizer.checkCache(output, cache))
            return output;

        var entries = readArchive(input);
        if (removeGeneratedSources)
            entries.keySet().removeIf(path -> path.startsWith("cpw/") || path.startsWith("argo/"));

        applyPatches(entries, patchFile);
        entries.putAll(overlayFiles);
        if (!removeGeneratedSources)
            fixPythonCompileErrors(entries);
        writeArchive(output, entries);
        cache.save();
        return output;
    }

    private Map<String, byte[]> listOverlayFiles(String prefix) {
        var ret = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipFile(this.assets)) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix))
                    continue;
                var path = safeRelative(entry.getName().substring(prefix.length()));
                try (var input = zip.getInputStream(entry)) {
                    ret.put(path, input.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Python-era source overlays " + prefix, e);
        }
        return ret;
    }

    static void fixPythonCompileErrors(Map<String, byte[]> sources) {
        replaceSource(sources, "net/minecraft/client/renderer/RenderGlobal.java",
            "Object object = null;", "EntityFX object = null;");
        replaceSource(sources, "net/minecraft/server/integrated/IntegratedServer.java",
            "protected void tick()", "public void tick()");
        replaceSource(sources, "net/minecraft/server/dedicated/DedicatedServer.java",
            "protected void updateTimeLightAndEntities()", "public void updateTimeLightAndEntities()");
        replaceSource(sources, "net/minecraft/world/gen/structure/ComponentStrongholdRightTurn.java",
            "public class ComponentStrongholdRightTurn extends ComponentStrongholdLeftTurn\n{\n",
            "public class ComponentStrongholdRightTurn extends ComponentStrongholdLeftTurn\n{\n"
                + "    public ComponentStrongholdRightTurn(int par1, Random par2Random, StructureBoundingBox par3StructureBoundingBox, int par4)\n"
                + "    {\n"
                + "        super(par1, par2Random, par3StructureBoundingBox, par4);\n"
                + "    }\n\n");
        replaceSource(sources, "net/minecraft/client/audio/CodecMus.java",
            "import java.io.InputStream;\n", "import java.io.IOException;\nimport java.io.InputStream;\n");
        replaceSource(sources, "net/minecraft/client/audio/CodecMus.java",
            "    protected InputStream openInputStream()\n"
                + "    {\n"
                + "        return new MusInputStream(this, this.url, this.urlConnection.getInputStream());\n"
                + "    }\n",
            "    protected InputStream openInputStream()\n"
                + "    {\n"
                + "        try\n"
                + "        {\n"
                + "            return new MusInputStream(this, this.url, this.urlConnection.getInputStream());\n"
                + "        }\n"
                + "        catch (IOException e)\n"
                + "        {\n"
                + "            return null;\n"
                + "        }\n"
                + "    }\n");
        replaceSource(sources, "net/minecraft/crash/CallableSuspiciousClasses.java",
            "        Field field = ClassLoader.class.getDeclaredField(\"classes\");\n"
                + "        field.setAccessible(true);\n"
                + "        ArrayList arraylist = new ArrayList((Vector)field.get(CrashReport.class.getClassLoader()));\n",
            "        ArrayList arraylist;\n"
                + "        try\n"
                + "        {\n"
                + "            Field field = ClassLoader.class.getDeclaredField(\"classes\");\n"
                + "            field.setAccessible(true);\n"
                + "            arraylist = new ArrayList((Vector)field.get(CrashReport.class.getClassLoader()));\n"
                + "        }\n"
                + "        catch (Exception e)\n"
                + "        {\n"
                + "            return \"\";\n"
                + "        }\n");
    }

    private static void replaceSource(Map<String, byte[]> sources, String path, String before, String after) {
        var data = sources.get(path);
        if (data == null)
            throw new IllegalStateException("Python-era source cleanup is missing " + path);
        var source = new String(data, StandardCharsets.UTF_8);
        var start = source.indexOf(before);
        if (start < 0 || source.indexOf(before, start + before.length()) >= 0)
            throw new IllegalStateException("Python-era source cleanup expected one occurrence of `" + before + "` in " + path);
        sources.put(path, source.replace(before, after).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> readArchive(File archive) {
        var ret = new java.util.LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new FileInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory())
                    ret.put(safeRelative(entry.getName()), zip.readAllBytes());
            }
            return ret;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read source archive " + archive.getAbsolutePath(), e);
        }
    }

    static void applyPatches(Map<String, byte[]> sources, File patchArchive) {
        try (var zip = new ZipInputStream(new FileInputStream(patchArchive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory() || !entry.getName().endsWith(".patch"))
                    continue;

                var path = safeRelative(entry.getName().substring(0, entry.getName().length() - ".patch".length()));
                var patchBytes = zip.readAllBytes();
                var original = sources.getOrDefault(path, new byte[0]);
                var patch = ContextualPatch.create(new ByteArrayInputStream(patchBytes))
                    .setAccessC14N(true)
                    .setMaxFuzz(2);
                var context = ContextualPatch.context(new ByteArrayInputStream(original));
                var result = patch.patchSingle(false, context);
                if (!result.getStatus().isSuccess())
                    throw new IllegalStateException("Failed to apply " + entry.getName() + ": " + result.getStatus()
                        + (result.getFailure() == null ? "" : " (" + result.getFailure().getMessage() + ')'));

                var patched = String.join("\n", context.getData()).getBytes(StandardCharsets.UTF_8);
                sources.put(path, patched);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply Python-era Forge patches from " + patchArchive.getAbsolutePath(), e);
        }
    }

    private static String safeRelative(String name) {
        var normalized = name.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains(":"))
            throw new IllegalStateException("Invalid path in Python-era Forge archive: " + name);
        for (var segment : normalized.split("/"))
            if (segment.equals(".") || segment.equals(".."))
                throw new IllegalStateException("Invalid path in Python-era Forge archive: " + name);
        return normalized;
    }

    private static void writeArchive(File output, Map<String, byte[]> entries) {
        FileUtils.ensureParent(output);
        try (var zip = new ZipOutputStream(new FileOutputStream(output))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(FileUtils.getStableEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Python-era Forge sources " + output.getAbsolutePath(), e);
        }
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
    public List<File> getClasspath() {
        var classpath = new ArrayList<>(this.mcp.getClasspath());
        classpath.add(this.mcp.getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR).execute());
        var maven = this.mcp.getCache().maven();
        classpath.add(maven.download(FML_ARGO));
        for (var library : FML_LIBRARIES)
            classpath.add(maven.download(library));
        return classpath;
    }

    @Override
    public Artifact getMCPArtifact() {
        return this.mcp.getName();
    }

    @Override
    public String getMinecraftVersion() {
        return this.mcp.getMinecraftTasks().getVersion();
    }

    @Override
    public @Nullable List<String> getModules() {
        return Collections.emptyList();
    }

    @Override
    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        for (var library : getLibraries())
            if (filter == null || filter.test(library))
                consumer.accept(library);
    }

    @Override
    public List<Artifact> getLibraries() {
        var libraries = new ArrayList<>(this.mcp.getMinecraftTasks().getClientLibraries().stream()
            .map(MinecraftTasks.ArtifactFile::artifact)
            .toList());
        libraries.add(RUNTIME_ARGO);
        libraries.addAll(FML_LIBRARIES);
        return libraries;
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
    public MinecraftTasks getMinecraftTasks() {
        return this.mcp.getMinecraftTasks();
    }

    Task getSources() {
        return this.sources;
    }

    Map<String, RunConfig> getRuns() {
        var serverEnv = new java.util.LinkedHashMap<String, String>();
        serverEnv.put("MCP_TO_SRG", "{mcp_to_srg}");
        serverEnv.put("mainClass", "net.minecraft.launchwrapper.Launch");
        serverEnv.put("MCP_MAPPINGS", "{mcp_mappings}");
        serverEnv.put("FORGE_VERSION", this.name.getVersion().substring(this.name.getVersion().indexOf('-') + 1));
        serverEnv.put("FORGE_GROUP", "net.minecraftforge");
        serverEnv.put("MC_VERSION", getMinecraftVersion());
        serverEnv.put("tweakClass", "cpw.mods.fml.common.launcher.FMLServerTweaker");

        var clientEnv = new java.util.LinkedHashMap<>(serverEnv);
        clientEnv.put("tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker");
        clientEnv.put("assetIndex", "{asset_index}");
        clientEnv.put("assetDirectory", "{assets_root}");
        clientEnv.put("nativesDirectory", "{natives}");

        var client = new RunConfig();
        client.name = "client";
        client.main = "net.minecraftforge.legacydev.MainClient";
        client.client = true;
        client.env = clientEnv;

        var server = new RunConfig();
        server.name = "server";
        server.main = "net.minecraftforge.legacydev.MainServer";
        server.client = false;
        server.env = serverEnv;

        var ret = new LinkedHashMap<String, RunConfig>();
        ret.put("client", client);
        ret.put("server", server);
        return ret;
    }
}
