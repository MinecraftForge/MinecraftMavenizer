package net.minecraftforge.mcmaven.cache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import net.minecraftforge.mcmaven.data.MinecraftVersion.LibraryDownload;
import net.minecraftforge.mcmaven.util.Constants;
import net.minecraftforge.mcmaven.util.HashFunction;
import net.minecraftforge.mcmaven.util.Util;

public class MinecraftMavenCache extends MavenCache {
    private final File mcDir = new File(Util.getMCDir(), "libraries");

    public MinecraftMavenCache(File root) {
        super("mojang", Constants.MOJANG_MAVEN, root);

        // Can't use MD5 or SHA256 as Mojang doesn't seem to provide them.
        this.known_hashes = new HashFunction[] {
            HashFunction.SHA1
        };
    }

    public File download(LibraryDownload lib) {
        return this.download(false, lib.path);
    }

    @Override
    protected void downloadFile(File target, String path) {
        if (!mcDir.exists()) {
            super.downloadFile(target, path);
            return;
        }

        var local = new File(mcDir, path.replace('/', File.separatorChar));
        if (local.exists()) {
            Util.ensureParent(target);
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
