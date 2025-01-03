/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.forge;

import net.minecraftforge.mcmaven.util.ComparableVersion;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * Represents a ForgeGradle version.
 *
 * @see <a href="https://github.com/MinecraftForge/ForgeGradle"><code>MinecraftForge/ForgeGradle</code> on
 * GitHub.com</a>
 */
public enum FGVersion {
    v1_1("1.1"),
    v1_2("1.2"),
    v2  ("2.0"),
    v2_1("2.1"),
    v2_2("2.2"),
    v2_3("2.3"),
    /**
     * Gradle 3.9, MCPConfig v1
     * <p>
     * The whole switch to data driven configs!
     */
    v3  ("3"),
    /**
     * Gradle 6.8, MCPConfig v2
     */
    v4  ("4"),
    /**
     * Gradle 7, MCPConfig v3-4
     * <p>
     * Some legacy work that never actually got anywhere. Also, JarInJar disaster.
     *
     * <ul>
     *     <li>Changed mappings artifact to runtime only dependency</li>
     *     <li>Support for extracting bundled server jar file</li>
     *     <li>Support for custom mapping channels</li>
     * </ul>
     */
    v5  ("5"),
    /**
     * Gradle 8
     * <p>
     * A bunch of changes to how run configs are integrated into the IDE/Gradle but nothing of importance to how
     * artifacts are generated.
     */
    v6  ("6");

    private final ComparableVersion comp;

    private FGVersion(String ver) {
        this.comp = new ComparableVersion(ver);
    }

    @Override
    public String toString() {
        return this.comp.toString();
    }

    private static final SequencedMap<FGVersion, ComparableVersion> FORGE_TO_FG = new LinkedHashMap<>();

    private static void forge(FGVersion fg, String forge) {
        FORGE_TO_FG.put(fg, new ComparableVersion(forge));
    }

    static {
        forge(v1_1, "1.7.2-10.12.0.967");
        forge(v1_2, "1.7.2-10.12.0.1048");
        forge(v2,   "1.8-11.14.3.1503");
        //forge(v2_0_1, "1.8-11.14.3.1506");
        //forge(v2_0_2, "1.8-11.14.3.1535");
        forge(v2_1, "1.8.8-11.14.4.1583"); //-1.8.8
        forge(v2_2, "1.9.4-12.17.0.1908"); //-1.9.4
        forge(v2_3, "1.12-14.21.0.2320");
        forge(v3,   "1.13.2-25.0.9");
        forge(v4,   "1.16.5-36.1.66");
        forge(v5,   "1.19-41.0.7");
        //forge(v5_1, "1.19.3-44.0.0");
        forge(v6,   "1.19.2-43.3.5");
        // 1.19.4-45.2.4 - [6.0,6.2)
        //forge(v6_0_14, "1.20.2-48.0.20");
        //forge(v6_0_16, "1.20.2-48.0.36");
        //forge(v6_0_24, "1.20.6-50.0.1");
    }

    /**
     * Gets the ForgeGradle version that corresponds to the given Forge version.
     *
     * @param version the Forge version
     * @return the ForgeGradle version, or {@code null} if none
     */
    public static @Nullable FGVersion fromForge(String version) {
        var ver = new ComparableVersion(version);
        for (var entry : FORGE_TO_FG.reversed().entrySet()) {
            if (entry.getValue().compareTo(ver) <= 0)
                return entry.getKey();
        }

        return null;
    }

}
