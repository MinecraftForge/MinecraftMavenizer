/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

public final class Constants {
    public static final String MC_GROUP = "net.minecraft";
    public static final String MC_CLIENT = "client";

    public static final String FORGE_FILES = "https://files.minecraftforge.net/";
    public static final String FORGE_PROMOS = FORGE_FILES + "net/minecraftforge/forge/promotions_slim.json";
    public static final String FORGE_GROUP = "net.minecraftforge";
    public static final String FORGE_NAME = "forge";
    public static final String FORGE_ARTIFACT = FORGE_GROUP + ':' + FORGE_NAME;
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    public static final String MCP_DOWNLOADS = FORGE_FILES + "mcp/";

    // Legacy FG2 Decompilers
    // This was https://files.minecraftforge.net/fernflower_temporary.zip!/fernflower.jar but that has issues with running on anything higher then java 6
    // Java 6 doesn't exist for OSX, so i've had to implement patches to fix those decompile differences.
    // See https://github.com/MinecraftForge/ForgeFlower/tree/legacy-1.0
    public static final Artifact FERNFLOWER_FG_1_0 = Artifact.from("net.minecraftforge:forgeflower:0.1.0.0");
    // This was https://files.minecraftforge.net/fernflower-fix-1.0.zip!/fernflower.jar but I extracted it to make it easier to download
    // This is the old obfusicated fernflower decompiler, with all of my fixes from: https://github.com/LexManos/FernFlowerFixer/
    public static final Artifact FERNFLOWER_FG_2_0_LEGACY = Artifact.from("net.minecraftforge:fernflower:0.2.0.0");
    // 2.0-SNAPSHOT used for MC 1.8.8
    public static final Artifact FERNFLOWER_FG_2_0_188 = Artifact.from("net.minecraftforge:fernflower:2.0.9");
    // 2.0-SNAPSHOT used for MC 1.9.4
    public static final Artifact FERNFLOWER_FG_2_0_194 = Artifact.from("net.minecraftforge:fernflower:2.0.17");
    public static final Artifact FERNFLOWER_FG_2_2 = Artifact.from("net.minecraftforge:forgeflower:0.2.2.0");
    public static final Artifact FERNFLOWER_FG_2_3 = Artifact.from("net.minecraftforge:forgeflower:0.2.3.1");

    // TODO Other toolchains such as FMLOnly (not required, but would be useful so we have the framework to use other toolchains)
    public static final String FMLONLY_NAME = "fmlonly";
    public static final String FMLONLY_ARTIFACT = FORGE_GROUP + ':' + FMLONLY_NAME;

    // TODO [MCMavenizer][Options] Change cache timeout timer
    public static final int CACHE_TIMEOUT = 1000 * 60 * 60 * 1; // 1 hour
    //public static final String LAUNCHER_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    public static final String LAUNCHER_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    public static final Artifact ACCESS_TRANSFORMER = Artifact.from("net.minecraftforge:accesstransformers:8.2.17:fatjar");
    public static final int ACCESS_TRANSFORMER_JAVA_VERSION = 8;

    public static final Artifact SIDE_STRIPPER = Artifact.from("net.minecraftforge:mergetool:1.2.5:fatjar");
    public static final int SIDE_STRIPPER_JAVA_VERSION = 8;

    public static final Artifact INSTALLER_TOOLS = Artifact.from("net.minecraftforge:installertools:1.4.5:fatjar");
    public static final int INSTALLER_TOOLS_JAVA_VERSION = 8;

    public static final Artifact RENAMER = Artifact.from("net.minecraftforge:renamer:2.0.4:all");
    public static final int RENAMER_JAVA_VERSION = 8;

    public static final Artifact STUBIFY = Artifact.from("net.minecraftforge:jar-stubify:1.0.0");
    public static final int STUBIFY_JAVA_VERSION = 25;

    public static final Artifact FACADE = Artifact.from("net.minecraftforge:facade:1.0.2");
    public static final int FACADE_JAVA_VERSION = 25;

    public static final Artifact MCINJECTOR = Artifact.from("de.oceanlabs.mcp:mcinjector:3.4.5:fatjar");
    public static final int MCINJECTOR_JAVA_VERSION = 8;

    public static final Artifact MCPCLEANUP = Artifact.from("net.minecraftforge:mcpcleanup:2.4.3:fatjar");
    public static final int MCPCLEANUP_JAVA_VERSION = 8;;

    public static final Artifact LEGACY_MERGETOOL = Artifact.from("net.minecraftforge:mergetool:0.2.3.4:fatjar");
    public static final int LEGACY_MERGETOOL_JAVA_VERSION = 8;
}
