/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
module net.minecraftforge.mcmaven {
    exports net.minecraftforge.mcmaven.cli;

    requires static org.jetbrains.annotations;
    requires static joptsimple;

    requires com.google.gson;
    requires de.siegmar.fastcsv;
    requires io.codechicken.diffpatch;
    requires net.minecraftforge.java_version;
    requires java.xml;
    requires org.apache.commons.io;
    requires org.apache.commons.lang3;
    requires net.minecraftforge.srgutils;
}