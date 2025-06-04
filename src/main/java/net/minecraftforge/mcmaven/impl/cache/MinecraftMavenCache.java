/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.cache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.util.data.MCJsonUtils;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.mcmaven.impl.util.Util;

/** Represents the Minecraft maven cache for this tool. */
public final class MinecraftMavenCache extends MavenCache {
    private static final HashFunction[] KNOWN_HASHES = {
        // Can't use MD5 or SHA256 as Mojang doesn't seem to provide them.
        HashFunction.SHA1
    };

    private static final File LOCAL_MCLIBS = new File(MCJsonUtils.getMCDir(), "libraries");

    /**
     * Initializes a new maven cache with the given cache directory.
     *
     * @param root The cache directory
     */
    public MinecraftMavenCache(File root) {
        super("mojang", Constants.MOJANG_MAVEN, root, KNOWN_HASHES);
    }

    /**
     * Downloads an artifact using the given library download information.
     *
     * @param lib The library download information
     * @return The downloaded file
     *
     * @throws IOException If an error occurs while downloading the file
     */
    @SuppressWarnings("JavadocDeclaration") // IOException thrown by Util.sneak
    public File download(MinecraftVersion.LibraryDownload lib) {
        return this.download(false, lib.path);
    }

    @Override
    protected void downloadFile(File target, String path) throws IOException {
        if (!LOCAL_MCLIBS.exists()) {
            super.downloadFile(target, path);
            return;
        }

        var local = new File(LOCAL_MCLIBS, path.replace('/', File.separatorChar));
        if (local.exists()) {
            FileUtils.ensureParent(target);
            // TODO: [MCMaven] Check hashes for local minecraft archive
            try {
                Files.copy(local.toPath(), target.toPath());
            } catch (IOException e) {
                Util.sneak(e);
            }
            return;
        }

        super.downloadFile(target, path);
    }
}
