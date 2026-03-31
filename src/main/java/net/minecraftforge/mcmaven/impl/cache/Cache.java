/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.cache;

import net.minecraftforge.mcmaven.impl.util.Constants;

import java.io.File;
import java.util.Map;

/** Represents the cache for this tool. */
public record Cache(File root, File localCache, JDKCache jdks, MavenCache maven, MinecraftMavenCache minecraft) {
    /**
     * Makes a new cache with the given root and JDK cache directories.
     *
     * @param root     The root cache directory.
     * @param jdkCache The JDK cache directory.
     */
    public Cache(File root, File jdkCache) {
        this(root, new File(root, "local"), jdkCache, Map.of());
    }

    public Cache(File root, File localCache, File jdkCache, Map<String, String> foreignRepositories) {
        this(root,
            localCache,
            new JDKCache(jdkCache),
            new MavenCache(Constants.FORGE_NAME, Constants.FORGE_MAVEN, root, foreignRepositories),
            new MinecraftMavenCache(root)
        );
    }
}
