package net.minecraftforge.mcmaven.util;

public class Constants {
    public static final String FORGE_FILES = "https://files.minecraftforge.net/";
    public static final String FORGE_PROMOS = FORGE_FILES + "net/minecraftforge/forge/promotions_slim.json";
    public static final String FORGE_GROUP = "net.minecraftforge";
    public static final String FORGE_NAME = "forge";
    public static final String FORGE_ARTIFACT = FORGE_GROUP + ':' + FORGE_NAME;
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";

    public static final int CACHE_TIMEOUT = 1000 * 60 * 60 * 1; // 1 hour
    public static final String LAUNCHER_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    public static final Artifact ACCESS_TRANSFORMER = Artifact.from("net.minecraftforge:accesstransformers:8.1.1:fatjar");
    public static final int ACCESS_TRANSFORMER_JAVA_VERSION = 8;

    public static final Artifact SIDE_STRIPPER = Artifact.from("net.minecraftforge:mergetool:1.2.0:fatjar");
    public static final int SIDE_STRIPPER_JAVA_VERSION = 8;
}
