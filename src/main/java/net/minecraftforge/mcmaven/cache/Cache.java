package net.minecraftforge.mcmaven.cache;

import java.io.File;

import net.minecraftforge.mcmaven.util.Constants;

public class Cache {
    public final File root;
    public final JDKCache jdks;
    public final MavenCache forge;
    public final MinecraftMavenCache mojang;

    public Cache(File root, File jdkCache) {
        this.root = root;
        this.jdks = new JDKCache(jdkCache);
        this.forge = new MavenCache("forge", Constants.FORGE_MAVEN, root);
        this.mojang = new MinecraftMavenCache(root);
    }

}
