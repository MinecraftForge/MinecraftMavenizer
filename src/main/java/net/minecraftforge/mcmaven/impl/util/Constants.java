/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

public interface Constants {
    String FORGE_FILES = "https://files.minecraftforge.net/";
    String FORGE_PROMOS = FORGE_FILES + "net/minecraftforge/forge/promotions_slim.json";
    String FORGE_GROUP = "net.minecraftforge";
    String FORGE_NAME = "forge";
    String FORGE_ARTIFACT = FORGE_GROUP + ':' + FORGE_NAME;
    String FORGE_MAVEN = "https://maven.minecraftforge.net/";

    // TODO Other toolchains such as FMLOnly (not required, but would be useful so we have the framework to use other toolchains)
    String FMLONLY_NAME = "fmlonly";
    String FMLONLY_ARTIFACT = FORGE_GROUP + ':' + FMLONLY_NAME;

    // TODO [MCMaven][Options] Change cache timeout timer
    int CACHE_TIMEOUT = 1000 * 60 * 60 * 1; // 1 hour
    String LAUNCHER_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    Artifact ACCESS_TRANSFORMER = Artifact.from("net.minecraftforge:accesstransformers:8.2.1:fatjar");
    int ACCESS_TRANSFORMER_JAVA_VERSION = 8;

    Artifact SIDE_STRIPPER = Artifact.from("net.minecraftforge:mergetool:1.2.0:fatjar");
    int SIDE_STRIPPER_JAVA_VERSION = 8;

    Artifact INSTALLER_TOOLS = Artifact.from("net.minecraftforge:installertools:1.4.3:fatjar");
    int INSTALLER_TOOLS_JAVA_VERSION = 8;
}
