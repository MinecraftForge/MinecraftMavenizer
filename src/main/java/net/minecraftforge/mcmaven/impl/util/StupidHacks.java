/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

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
    }

}
