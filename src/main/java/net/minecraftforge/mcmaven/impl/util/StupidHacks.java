/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.util.Map;

import net.minecraftforge.srgutils.MinecraftVersion;

/*
 * Here be stupid hacks that help us support old versions,
 * I've decided to stick them all in one place so we can just call
 * a magic function and never have to actually look at these abominations.
 */
public class StupidHacks {

    public static Artifact fixLegacyTools(Artifact artifact) {
        // Some MCPConfig versions reference a merge tool that has an invlaid 'BUKKIT' side.
        // This was fixed in 0.2.3.3 https://github.com/MinecraftForge/MergeTool/commit/e68e1b06ba87c68bc1a5c922395286b53a17dddf
        if ("net.minecraftforge".equals(artifact.getGroup()) && "mergetool".equals(artifact.getName()) && "0.2.3.2".equals(artifact.getVersion()))
            return artifact.withVersion("0.2.3.3");
        return artifact;

        // scala did some releases overwriting their own files, so we host them ourselves, but
    }

    private static Map<String, String> FORGE_FIXES = Map.of(
        // They changed the hashes for a while, then changed them back
        "org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2_mc", "org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2",
        "org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2_mc", "org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2",
        // They moved then from root to modules
        "org.scala-lang:scala-swing_2.11:1.0.1",              "org.scala-lang.modules:scala-swing_2.11:1.0.1",
        "org.scala-lang:scala-xml_2.11:1.0.2",                "org.scala-lang.modules:scala-xml_2.11:1.0.2",
        "org.scala-lang:scala-parser-combinators_2.11:1.0.1", "org.scala-lang.modules:scala-parser-combinators_2.11:1.0.1"
    );

    public static Artifact fixLegacyForgeDeps(Artifact artifact) {
        // scala did some releases overwriting their own files, so we host them ourselves because of the hashes, but during dev it should be fine to just use the official
        var newCoords = FORGE_FIXES.get(artifact.toString());
        if (newCoords != null)
            return Artifact.from(newCoords);
        return artifact;
    }

    private static final MinecraftVersion MC_1_12_2 = MinecraftVersion.from("1.12.2");
    public static boolean isLegacyRenamer(String version) {
        try {
            var current = MinecraftVersion.from(version);
            return current.compareTo(MC_1_12_2) <= 0;
        } catch (IllegalArgumentException e) {
            return false;
        }

    }

}
