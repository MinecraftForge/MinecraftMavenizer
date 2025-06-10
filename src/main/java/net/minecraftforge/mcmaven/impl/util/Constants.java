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

    // Parchment related things
    public static final String PARCHMENT_MAVEN = "https://maven.parchmentmc.org/";
    public static final String PARCHMENT_GROUP = "org.parchmentmc.data"; // Name is "parchment-{mcversion}'

    // TODO Other toolchains such as FMLOnly (not required, but would be useful so we have the framework to use other toolchains)
    public static final String FMLONLY_NAME = "fmlonly";
    public static final String FMLONLY_ARTIFACT = FORGE_GROUP + ':' + FMLONLY_NAME;

    // TODO [MCMavenizer][Options] Change cache timeout timer
    public static final int CACHE_TIMEOUT = 1000 * 60 * 60 * 1; // 1 hour
    public static final String LAUNCHER_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    public static final Artifact ACCESS_TRANSFORMER = Artifact.from("net.minecraftforge:accesstransformers:8.2.1:fatjar");
    public static final int ACCESS_TRANSFORMER_JAVA_VERSION = 8;

    public static final Artifact SIDE_STRIPPER = Artifact.from("net.minecraftforge:mergetool:1.2.0:fatjar");
    public static final int SIDE_STRIPPER_JAVA_VERSION = 8;

    public static final Artifact INSTALLER_TOOLS = Artifact.from("net.minecraftforge:installertools:1.4.3:fatjar");
    public static final int INSTALLER_TOOLS_JAVA_VERSION = 8;
}
