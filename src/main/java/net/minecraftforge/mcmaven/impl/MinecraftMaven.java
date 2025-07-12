/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.forge.FGVersion;
import net.minecraftforge.mcmaven.impl.repo.forge.ForgeRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.hash.HashUtils;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

// TODO [MCMavenizer][Deobf] ADD DEOBF
//  use single detached configuration to resolve individual configurations
//  pass in downloaded files to mcmaven (absolute path)
public record MinecraftMaven(File output, Cache cache, Mappings mappings) {
    private static final ComparableVersion MIN_SUPPORTED_FORGE = new ComparableVersion("1.14.4"); // Only 1.14.4+ has official mappings, we can support more when we add more mappings

    public MinecraftMaven(File output, File cacheRoot, File jdkCacheRoot, Mappings mappings) {
        this(output, new Cache(cacheRoot, jdkCacheRoot), mappings);
    }

    public MinecraftMaven {
        Log.info("  Output:     " + output.getAbsolutePath());
        Log.info("  Cache:      " + cache.root().getAbsolutePath());
        Log.info("  JDK Cache:  " + cache.jdks().root().getAbsolutePath());
        Log.info("  Offline:    " + GlobalOptions.isOffline());
        Log.info("  Cache Only: " + GlobalOptions.isCacheOnly());
        Log.info("  Mappings:   " + mappings);
        Log.info();
    }

    public void run(Artifact artifact) {
        var module = artifact.getGroup() + ':' + artifact.getName();
        var version = artifact.getVersion();
        Log.info("Processing Minecraft dependency: %s:%s".formatted(module, version));
        var mcprepo = new MCPConfigRepo(this.cache);

        if (Constants.FORGE_GROUP.equals(artifact.getGroup()) && Constants.FORGE_NAME.equals(artifact.getName())) {
            var repo = new ForgeRepo(this.cache, mcprepo);
            if (artifact.getVersion() == null)
                throw new IllegalArgumentException("No version specified for Forge");

            if ("all".equals(version)) {
                var versions = this.cache.maven().getVersions(artifact);
                var mappingCache = new HashMap<String, Mappings>();
                for (var ver : versions.reversed()) {
                    var cver = new ComparableVersion(ver);
                    if (cver.compareTo(MIN_SUPPORTED_FORGE) < 0)
                        continue;

                    var fg = FGVersion.fromForge(ver);
                    if (fg == null || fg.ordinal() < FGVersion.v3.ordinal()) // Unsupported
                        continue;

                    var mappings = mappingCache.computeIfAbsent(forgeToMcVersion(ver), this.mappings()::withMCVersion);
                    var art = artifact.withVersion(ver);
                    var artifacts = repo.process(art, mappings);
                    finalize(art, mappings, artifacts);
                }
            } else {
                var mappings = this.mappings().withMCVersion(forgeToMcVersion(version));
                var artifacts = repo.process(artifact, mappings);
                finalize(artifact, mappings, artifacts);
            }
        } else if (Constants.MC_GROUP.equals(artifact.getGroup())) {
            if (artifact.getVersion() == null)
                throw new IllegalArgumentException("No version specified for MCPConfig");

            var mappings = this.mappings().withMCVersion(mcpToMcVersion(version));
            var artifacts = mcprepo.process(artifact, mappings);
            finalize(artifact, mappings, artifacts);
        } else {
            throw new IllegalArgumentException("Artifact '%s' is currently Unsupported. Will add later".formatted(module));
        }
    }

    private static String forgeToMcVersion(String version) {
        // Save for a few april-fools versions, Minecraft doesn't use _ in their version names.
        // So when Forge needs to reference a version of Minecraft that uses - in the name, it replaces
        // it with _
        // This could cause issues if we ever support a version with _ in it, but fuck it I don't care right now.
        int idx = version.indexOf('-');
        if (idx == -1)
            throw new IllegalArgumentException("Invalid Forge version: " + version);
        return version.substring(0, idx).replace('_', '-');
    }

    public static String mcpToMcVersion(String version) {
        // MCP names can either be {MCVersion} or {MCVersion}-{Timestamp}, EXA: 1.21.1-20240808.132146
        // So lets see if the thing following the last - matches a timestamp
        int idx = version.lastIndexOf('-');
        if (idx < 0)
            return version;
        if (!version.substring(idx + 1).matches("\\d{8}\\.\\d{6}"))
            return version;
        return version.substring(0, idx);
    }

    private void finalize(Artifact module, Mappings mappings, List<Repo.PendingArtifact> artifacts) {
        var variants = new HashSet<Artifact>();
        for (var pending : artifacts) {
            if (pending == null)
                continue;

            // Basically, I want to support multiple variants of a Forge dep.
            // Simplest case would be different mapping channels.
            // I haven't added an opt-in for making artifacts that use mappings, so just assume any artifact with variants
            var artifact = pending.getArtifact();
            String suffix = null;
            if (pending.getVariants() != null && !mappings.isPrimary()) {
                suffix = mappings.channel() + '-' + mappings.version();
                if (artifact.getClassifier() == null)
                    artifact = artifact.withClassifier(suffix);
                else
                    artifact = artifact.withClassifier(artifact.getClassifier() + '-' + suffix);
            }

            var target = new File(this.output, artifact.getLocalPath());
            var varTarget = new File(this.output, artifact.getLocalPath() + ".variants");
            {
                var source = pending.get();
                var cache = HashStore.fromFile(target)
                    .add("source", source);

                if (!target.exists() || !cache.isSame()) {
                    // TODO: [MCMavenizer] Add --api argument to turn class artifacts to api-only targets for a public repo
                    try {
                        org.apache.commons.io.FileUtils.copyFile(source, target);
                        HashUtils.updateHash(target);
                        cache.save();
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
                    }
                }
            }

            if (pending.getVariants() != null) {
                var source = pending.getVariants().execute();
                var cache = HashStore.fromFile(target)
                    .add("source", source);

                if (!varTarget.exists() || !cache.isSame()) {
                    variants.add(Artifact.from(artifact.getGroup(), artifact.getName(), artifact.getVersion()));
                    try {
                        var data = JsonData.fromJson(source, GradleModule.Variant[].class);
                        var file = new GradleModule.Variant.File(target);
                        for (var variant : data) {
                            variant.file(file);
                            if (suffix != null)
                                variant.name = variant.name + '-' + suffix;
                        }
                        JsonData.toJson(data, varTarget);
                        cache.save();
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to write artifact variants: %s".formatted(artifact), t);
                    }
                }
            }
        }

        for (var artifact : variants) {
            updateVariants(artifact);
        }
    }

    private void updateVariants(Artifact artifact) {
        var root = new File(this.output, artifact.getFolder());
        var inputs = new ArrayList<File>();
        for (var file : root.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".variants")) {
                inputs.add(file);
            }
        }

        var target = new File(this.output, artifact.withExtension("module").getLocalPath());
        var cache = HashStore.fromFile(target);
        for (var input : inputs) {
            cache.add(input);
        }

        if (target.exists() && cache.isSame())
            return;

        var module = GradleModule.of(artifact);
        for (var input : inputs) {
            try {
                var data = JsonData.fromJson(input, GradleModule.Variant[].class);
                for (var variant : data)
                    module.variant(variant);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to read artifact variants: %s".formatted(input), t);
            }
        }

        try {
            JsonData.toJson(module, target);
            HashUtils.updateHash(target);
            cache.save();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to write artifact module: %s".formatted(artifact), t);
        }
    }
}
