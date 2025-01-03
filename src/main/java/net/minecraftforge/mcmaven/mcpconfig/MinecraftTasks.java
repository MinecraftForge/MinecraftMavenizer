/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.mcpconfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.mcmaven.data.JsonData;
import net.minecraftforge.mcmaven.util.Constants;
import net.minecraftforge.mcmaven.util.DownloadUtils;
import net.minecraftforge.mcmaven.util.HashStore;
import net.minecraftforge.mcmaven.util.Task;

// TODO [MCMaven][Documentation] Document
public class MinecraftTasks {
    private final File cache;
    private final String version;
    public final Task launcherManifest;
    public final Task versionJson;
    private final Map<String, Task> versionFiles = new HashMap<>();

    public MinecraftTasks(File cache, String version) {
        this.cache = new File(cache, "minecraft_tasks");
        this.version = version;
        this.launcherManifest = Task.named("downloadLauncherManifest", this::downloadLauncherManifest);
        this.versionJson = Task.named("downloadVersionJson[" + version + ']', this::downloadVersionJson);
    }

    private File downloadLauncherManifest() {
        var target = new File(this.cache, "launcher_manifest.json");
        if (!target.exists() || target.lastModified() < System.currentTimeMillis() - Constants.CACHE_TIMEOUT)
            DownloadUtils.downloadFile(target, Constants.LAUNCHER_MANIFEST);
        return target;
    }

    private File downloadVersionJson() {
        var target = new File(this.cache, this.version + "/version.json");
        var manifestF = downloadLauncherManifest();

        var cache = HashStore.fromFile(target);
        cache.add("manifest", manifestF);

        if (target.exists() && cache.isSame())
            return target;

        var manifest = JsonData.launcherManifest(manifestF);
        var url = manifest.getUrl(this.version);
        if (url == null)
            throw new IllegalStateException("Failed to find url for " + this.version + " version.json");

        if (!DownloadUtils.downloadFile(target, url.toExternalForm()))
            throw new IllegalStateException("Failed to download " + url);

        cache.save();
        return target;
    }

    public Task versionFile(String key, String ext) {
        return this.versionFiles.computeIfAbsent(key, k ->
            Task.named("download[" + this.version + "][" + key + ']',
                () -> downloadVersionFile(key, ext)
            )
        );
    }

    private File downloadVersionFile(String key, String ext) {
        var target = new File(this.cache, this.version + '/' + key  + '.' + ext);
        var manifestF = downloadVersionJson();

        var cache = HashStore.fromFile(target);
        cache.add("manifest", manifestF);

        if (target.exists() && cache.isSame())
            return target;

        var manifest = JsonData.minecraftVersion(manifestF);
        var dl = manifest.getDownload(key);
        if (dl == null || dl.url == null)
            throw new IllegalStateException("Missing '" + key  +"' from " + manifestF.getAbsolutePath());

        if (!DownloadUtils.downloadFile(target, dl.url.toExternalForm()))
            throw new IllegalStateException("Failed to download " + dl.url);

        cache.save();
        return target;
    }
}
