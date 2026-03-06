/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.Format;
import net.minecraftforge.srgutils.MinecraftVersion;

/** Handles Minecraft-specific tasks, unrelated to the MCPConfig toolchain. */
public class MinecraftTasks {
    public static final MinecraftVersion BUNDLE_START = MinecraftVersion.from("21w39a");
    private final Cache cache;
    private final File cacheRoot;
    private final String version;
    public final Task launcherManifest;
    public final Task versionJson;
    private final Map<String, Task> versionFiles = new HashMap<>();

    private Task mergeMappings;
    private Task renameClient;
    private Task renameServer;
    private Task clientPom;
    private Task serverPom;
    private Task extractServer;

    public enum MCFile {
        CLIENT_JAR("client", "jar"),
        CLIENT_MAPPINGS("client_mappings", "txt"),
        SERVER_JAR("server", "jar"),
        SERVER_MAPPINGS("server_mappings", "txt");

        public final String key;
        public final String ext;

        private MCFile(String key, String ext) {
            this.key = key;
            this.ext = ext;
        }
    }

    /**
     * Creates a new Minecraft task handler for the given version.
     *
     * @param cache   The caches folder
     * @param version The version to handle tasks for
     */
    MinecraftTasks(Cache cache, String version, Task launcherManifest) {
        this.cache = cache;
        this.cacheRoot = new File(new File(this.cache.root(), "minecraft_tasks"), version);
        this.version = version;
        this.launcherManifest = launcherManifest;
        this.versionJson = Task.named("downloadVersionJson[" + version + ']', Task.deps(this.launcherManifest), this::downloadVersionJson);
    }

    public String getVersion() {
        return this.version;
    }

    private File downloadVersionJson() {
        var target = new File(this.cacheRoot, "version.json");
        var manifestF = this.launcherManifest.execute();
        var manifest = JsonData.launcherManifest(manifestF).getInfo(version);

        var cache = Util.cache(target);
        if (manifest != null && manifest.sha1 != null)
            cache.addKnown("versionjson", manifest.sha1);
        else
            cache.add("manifest", manifestF);

        if (!Mavenizer.ignoreCache() && target.exists() && cache.isSame())
            return target;

        // The manifest v1 doesn't contain anything we can key off off, so this happens often
        // Do don't do the big warn if we get here.
        if (Mavenizer.isCacheOnly())
            Mavenizer.assertNotCacheOnly();

        // If we are offline, but the file exists, use it
        if (Mavenizer.isOffline() && target.exists())
            return target;

        Mavenizer.assertOnline();

        if (manifest == null)
            throw new IllegalStateException("Failed to find url for " + this.version + " version.json");

        try {
            DownloadUtils.downloadFile(target, manifest.url.toExternalForm());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download " + manifest.url, e);
        }

        cache.save();
        return target;
    }

    public Task versionFile(MCFile file) {
        return versionFile(file.key, file.ext);
    }

    public Task versionFile(String key, String ext) {
        return this.versionFiles.computeIfAbsent(key, _ ->
            Task.named("download[" + this.version + "][" + key + ']',
                Task.deps(this.versionJson),
                () -> downloadVersionFile(key, ext)
            )
        );
    }

    private File downloadVersionFile(String key, String ext) {
        var target = new File(this.cacheRoot, key  + '.' + ext);
        var manifestF = this.versionJson.execute();

        var cache = Util.cache(target);
        cache.add("manifest", manifestF);

        if (Mavenizer.checkCache(target, cache))
            return target;

        Mavenizer.assertOnline();

        var manifest = JsonData.minecraftVersion(manifestF);
        var dl = manifest.getDownload(key);
        if (dl == null || dl.url == null)
            throw new IllegalStateException("Missing '" + key  +"' from " + manifestF.getAbsolutePath());

        try {
            DownloadUtils.downloadFile(target, dl.url.toExternalForm());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download " + dl.url, e);
        }

        cache.save();
        return target;
    }

    public Task mergeMappings() {
        if (this.mergeMappings == null)
            this.mergeMappings = Task.named("merge_mappings[" + version + ']', Task.deps(versionFile(MCFile.CLIENT_MAPPINGS), versionFile(MCFile.SERVER_MAPPINGS)), this::mergeMappingsImpl);
        return this.mergeMappings;
    }

    private File mergeMappingsImpl() {
        var clientTask = versionFile(MCFile.CLIENT_MAPPINGS);
        var serverTask = versionFile(MCFile.SERVER_MAPPINGS);
        var output = new File(this.cacheRoot, "joined_mappings.tsrg.gz");
        var client = clientTask.execute();
        var server = serverTask.execute();
        var cache = Util.cache(output)
            .add(client, server);

        if (Mavenizer.checkCache(output, cache))
            return output;

        try {
            var off2obfClient = IMappingFile.load(client);
            var off2obfServer = IMappingFile.load(server);
            var off2obf = off2obfClient.merge(off2obfServer);
            off2obf.write(output.toPath(), Format.TSRG2, false);
        } catch (IOException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }

    public Task renameClient() {
        if (this.renameClient == null) {
            var jar = versionFile(MCFile.CLIENT_JAR);
            var map = versionFile(MCFile.CLIENT_MAPPINGS);
            this.renameClient = Task.named("rename[" + version + "][client]", Task.deps(jar, map), () -> renameJar("client", jar, map));
        }
        return this.renameClient;
    }

    public Task renameServer() {
        if (this.renameServer == null) {
            var jar = this.extractServer();
            var map = versionFile(MCFile.SERVER_MAPPINGS);
            this.renameServer = Task.named("rename[" + version + "][server]", Task.deps(jar, map), () -> renameJar("server", jar, map));
        }
        return this.renameServer;
    }

    private File renameJar(String name, Task jarTask, Task mapTask) {
        var tool = this.cache.maven().download(Constants.RENAMER);
        var output = new File(this.cacheRoot, name + "-official.jar");
        var log = new File(this.cacheRoot, name + "-official.log");

        var mappings = mapTask.execute();
        var jar = jarTask.execute();

        var cache = Util.cache(output);
        cache.add("tool", tool);
        cache.add("mappings", mappings);
        cache.add("input", jar);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var args = List.of(
            "--input", jar.getAbsolutePath(),
            "--output", output.getAbsolutePath(),
            "--reverse",
            "--map", mappings.getAbsolutePath(),
            "--ann-fix",
            "--record-fix",
            "--src-fix",
            "--strip-sigs",
            "--disable-abstract-param"
        );

        File jdk;
        try {
            jdk = this.cache.jdks().get(Constants.RENAMER_JAVA_VERSION);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + Constants.RENAMER_JAVA_VERSION, e);
        }

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to rename jar file, See log: " + log.getAbsolutePath());

        cache.save();
        return output;
    }

    public Task clientPom() {
        if (this.clientPom == null)
            this.clientPom = Task.named("pom[" + this.version + "][client]", Task.deps(this.versionJson), this::clientPomImpl);
        return this.clientPom;
    }

    private File clientPomImpl() {
        var output = new File(this.cacheRoot, "client.pom");
        var json = this.versionJson.execute();

        var cache = Util.cache(output)
            .add("json", json);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var meta = JsonData.minecraftVersion(json);

        var builder = new POMBuilder("net.minecraft", "client", version)
            .preferGradleModule()
            .dependencies(deps -> {
                for (var lib : meta.libraries)
                    deps.add(Artifact.from(lib.name));
            });


        FileUtils.ensureParent(output);
        try (var os = new FileOutputStream(output)) {
            os.write(builder.build().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | ParserConfigurationException | TransformerException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }

    public Task serverPom() {
        if (this.serverPom == null)
            this.serverPom = Task.named("pom[" + this.version + "][server]", Task.deps(versionFile(MCFile.SERVER_JAR)), this::serverPomImpl);
        return this.serverPom;
    }

    private File serverPomImpl() {
        var output = new File(this.cacheRoot, "client.pom");
        var jarFile = versionFile(MCFile.SERVER_JAR).execute();

        var cache = Util.cache(output)
            .add("jar", jarFile);

        if (Mavenizer.checkCache(output, cache))
            return output;

        var libs = Util.listBundleArtifacts(jarFile);
        var builder = new POMBuilder("net.minecraft", "server", version)
            .preferGradleModule()
            .dependencies(deps -> libs.forEach(deps::add));

        FileUtils.ensureParent(output);
        try (var os = new FileOutputStream(output)) {
            os.write(builder.build().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | ParserConfigurationException | TransformerException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }

    public Task extractServer() {
        if (this.extractServer == null) {
            var self = MinecraftVersion.from(version);
            var serverJar = versionFile(MCFile.SERVER_JAR);
            if (self.compareTo(BUNDLE_START) >= 0)
                this.extractServer = Task.named("extract[" + this.version +"][server]", Task.deps(serverJar), this::extractServerImpl);
            else
                this.extractServer = serverJar;
        }
        return this.extractServer;
    }

    private File extractServerImpl() {
        var output = new File(this.cacheRoot, "server-extracted.jar");
        var bundle = versionFile(MCFile.SERVER_JAR).execute().getAbsoluteFile();
        var cache = Util.cache(output)
            .add("bundle", bundle);

        if (Mavenizer.checkCache(output, cache))
            return output;

        try (var jar = new JarFile(bundle)) {
            var entries = Util.readBundle(bundle, jar, "versions");

            if (entries.size() != 1)
                throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Expected 1 entry in versions.list found " + entries.size());

            Files.createDirectories(output.getParentFile().toPath());

            var path = "META-INF/versions/" + entries.getFirst().path();
            var jarEntry = jar.getEntry(path);
            if (jarEntry == null)
                throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing " + path);

            Files.copy(jar.getInputStream(jarEntry), output.toPath(), StandardCopyOption.REPLACE_EXISTING);

            cache.save();

            return output;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    public record ArtifactFile(Artifact artifact, File file) {}
    private List<ArtifactFile> clientLibraries = null;
    public List<ArtifactFile> getClientLibraries() {
        if (clientLibraries == null) {
            var ret = new ArrayList<ArtifactFile>();
            var json = JsonData.minecraftVersion(this.versionJson.execute());
            for (var lib : json.getLibs()) {
                //Natives don't have main download
                if (lib.dl == null)
                    continue;
                var artifact = Artifact.from(lib.coord).withOS(lib.os);
                var file = this.cache.minecraft().download(artifact);
                ret.add(new ArtifactFile(artifact, file));
            }
            clientLibraries = ret;
        }
        return clientLibraries;
    }

    private List<ArtifactFile> serverLibraries = null;
    public List<ArtifactFile> getServerLibraries() {
        if (serverLibraries == null) {
            var self = MinecraftVersion.from(this.version);
            // TODO: [Mavenizer] Server Libraries - Find 'extra' server jar somehow
            if (self.compareTo(MinecraftTasks.BUNDLE_START) < 0)
                serverLibraries = List.of();
            else
                serverLibraries = getServerLibrariesBundle();
        }
        return serverLibraries;
    }

    private List<ArtifactFile> getServerLibrariesBundle() {
        var ret = new ArrayList<ArtifactFile>();
        var bundle = this.versionFile(MCFile.SERVER_JAR).execute();
        try (var jar = new JarFile(bundle)) {
            var list = Util.readBundle(bundle, jar, "libraries");
            var root = new File(this.cacheRoot, "libraries");
            for (var lib : list) {
                var target = new File(root, lib.path());
                if (!target.exists()) {
                    var entry = jar.getEntry("META-INF/libraries/" + lib.path());
                    if (entry == null)
                        throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing META-INF/libraries/" + lib.path());

                    FileUtils.ensureParent(target);

                    try (var os = new FileOutputStream(target);
                         var is = jar.getInputStream(entry)) {
                        is.transferTo(os);
                    }
                }
                ret.add(new ArtifactFile(Artifact.from(lib.name()), target));
            }
        } catch (IOException e) {
            Util.sneak(e);
        }
        return ret;
    }
}
