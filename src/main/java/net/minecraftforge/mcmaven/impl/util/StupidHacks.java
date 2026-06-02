/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.srgutils.MinecraftVersion;
import net.minecraftforge.util.file.FileUtils;

/*
 * Here be stupid hacks that help us support old versions,
 * I've decided to stick them all in one place so we can just call
 * a magic function and never have to actually look at these abominations.
 */
public class StupidHacks {

    public static Artifact fixLegacyTools(Artifact artifact) {
        // Some MCPConfig versions reference a merge tool that has an invalid 'BUKKIT' side.
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
        "org.scala-lang:scala-parser-combinators_2.11:1.0.1", "org.scala-lang.modules:scala-parser-combinators_2.11:1.0.1",
        // They renamed the artifact
        "tv.twitch:twitch-external-platform:4.5", "tv.twitch:twitch-platform:6.5",
        // Bumped transitivly somewhere in 1.7.10-pre4, and relied on the bumped version
        "com.google.guava:guava:15.0", "com.google.guava:guava:16.0"
    );
    // these are artifacts that Mojang referenced in the past, but now are deleted.
    //private static Set<String> DELETED_ARTIFACTS = Set.of(
    //    "tv.twitch:twitch-external-platform"
    //);

    public static @Nullable Artifact fixLegacyForgeDeps(Artifact artifact) {
        //if (DELETED_ARTIFACTS.contains(artifact.withVersion(null).toString()))
        //    return null;
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

    private static final int OUT_OF_MEMORY = -1001;
    private static final int FAILED_DECOMPILE = -1002;
    private static final int INVALID_INITAL_HEAP = -1003;
    private static final int NOT_ENOUGH_MEMORY = -1004;
    // Yes this is slow as fuck, but this is only run during a decompile run which is already slow,
    // This is to check if Fernflower is broken and returning success when it really failed.
    // It also eagerly exits the process when something fails so as to not waste time.
    private static int parseDecompileLog(String line) {
        if (line.startsWith("Initial heap size set to a larger value than the maximum heap size"))
            return INVALID_INITAL_HEAP;
        if (line.startsWith("Could not reserve enough space for object heap"))
            return NOT_ENOUGH_MEMORY;
        if (line.startsWith("java.lang.OutOfMemoryError:"))
            return OUT_OF_MEMORY;
        if (line.startsWith("Exception in thread") && line.contains("java.lang.OutOfMemoryError"))
            return OUT_OF_MEMORY;
        if (line.contains("ERROR:")) {
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + cl.qualifiedName + " couldn't be written.";
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + classWrapper.getClassStruct().qualifiedName + " couldn't be written.";
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + node.classStruct.qualifiedName + " couldn't be written.";
            if (line.endsWith(" couldn't be written."))
                return FAILED_DECOMPILE;
            // DecompilerContext.getLogger().logError("Class " + cl.qualifiedName + " couldn't be processed.", t);
            if (line.endsWith(" couldn't be processed."))
                return FAILED_DECOMPILE;
            // DecompilerContext.getLogger().logError("Class " + cl.qualifiedName + " couldn't be fully decompiled.", t);
            if (line.endsWith(" couldn't be fully decompiled."))
                return FAILED_DECOMPILE;
            // String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + classStruct.qualifiedName + " couldn't be decompiled.";
            if (line.endsWith(" couldn't be decompiled."))
                return FAILED_DECOMPILE;
        }
        return 0;
    }

    public static ProcessUtils.Result runDecompiler(File jdk, File log, File tool, List<String> defaultJvm, List<String> run) {
        ToIntFunction<String> logHandler = StupidHacks::parseDecompileLog;
        var jvm = Mavenizer.fillDecompileJvmArgs(defaultJvm, true, true);

        var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, jvm, run, logHandler);
        if (ret.exitCode == NOT_ENOUGH_MEMORY) {
            LOGGER.error("Failed to create JVM with Not Enough Memory issue, Modern minecraft requires atleast 4GB to decompile. Run it on a system with more ram.");
        } else if (ret.exitCode == INVALID_INITAL_HEAP) {
            LOGGER.error("Attempted to run decompile with JVM args: " + jvm + " resulted in Invalid Inital and Max heap settings.");
            LOGGER.error("This is typically caused by you having a environement variable setting the global memory options, remove or set those variables to values higher then 4GB.");
        }
        if (ret.exitCode == OUT_OF_MEMORY || ret.exitCode == INVALID_INITAL_HEAP) {
            var newJvm = Mavenizer.fillDecompileJvmArgs(defaultJvm, false, false);
            if (!newJvm.equals(jvm)) {
                LOGGER.error("First decompile failed with OutOfMemory using JVM Args: " + jvm);
                LOGGER.error("Attempting again with: " + newJvm);
                ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, newJvm, run, logHandler);
                if (ret.exitCode == OUT_OF_MEMORY)
                    LOGGER.error("Ran out of memory again, you can specify more manually using the --decompile-memory Mavenizer argument");
            }
        }
        return ret;
    }

    // Forge patches sometimes arn't in full SRG names so we need to pick known 'good' mappings
    // So I scanned Forge's git history and found each mapping change.
    private record ForgeMappings(ComparableVersion start, String mappings) {}
    private static final List<ForgeMappings> FORGE_MAPPINGS = new ArrayList<ForgeMappings>();
    private static void mappings(String forge, String mappings) {
        FORGE_MAPPINGS.add(new ForgeMappings(new ComparableVersion(forge), mappings));
    }
    static {
        // FG 1.x, these were packed in the userdev. Manually uploaded
        mappings("1.7.2-10.12.0.967",        "snapshot:20131226-1.7.2");
        mappings("1.7.2-10.12.0.1024",       "snapshot:20140205-1.7.2");
        mappings("1.7.10_pre4-10.12.2.1137", "snapshot:20140624-1.7.10-pre4");

        // FG2, normal published mappings
        mappings("1.7.2-10.12.0.1024",  "snapshot:20140205-1.7.2");
        mappings("1.8-11.14.3.1503",    "snapshot:20141130-1.8");
        mappings("1.8.8-11.14.4.1575",  "snapshot:20151122-1.8");
        mappings("1.8.9-11.15.0.1656",  "stable:20-1.8.8");
        mappings("1.9-12.16.0.1766",    "snapshot:20160312-1.9");
        mappings("1.9.4-12.17.0.1908",  "snapshot:20160518-1.9.4");
        mappings("1.10.2-12.18.2.2125", "snapshot:20161111-1.10.2");
        mappings("1.11-13.19.1.2198",   "snapshot:20161220-1.11");
        mappings("1.12-14.21.0.2334",   "snapshot:20170617-1.12");
        mappings("1.12-14.21.0.2352",   "snapshot:20170624-1.12");
        mappings("1.12.2-14.23.0.2502", "snapshot:20171003-1.12");
        mappings("1.13",                "snapshot:20180921-1.13");
        mappings("1.14.2-26.0.0",       "snapshot:20190526-1.13.2");
        mappings("1.14.2-26.0.6",       "snapshot:20190608-1.14.2");
        mappings("1.14.2-26.0.47",      "snapshot:20190621-1.14.2");
        mappings("1.14.3-27.0.60",      "snapshot:20190719-1.14.3");
        mappings("1.14.4",              "official");
    }
    public static Mappings getDefaultMappings(ComparableVersion forgeVersion) {
        String version = null;
        for (var map : FORGE_MAPPINGS) {
            if (forgeVersion.compareTo(map.start) < 0)
                break;
            version = map.mappings();
        }
        if(version == null)
            throw new IllegalStateException("Could not determine default mappings for " + forgeVersion);
        return Mappings.of(version);
    }

    // List of 'bad' forge builds that are just broke for some reason and we explicitly don't want to support
    public static final Set<String> BLACKLISTED_FORGE_BUILDS = Set.of(
        // Has some fucked up patches with weird generics, I have no idea how they came into existance, they dont match github...
        // This is the very first 1.8.8 build, and the next one works fine, so I'm chalking it up to Gradle Snapshot voodoo
        "1.8.8-11.14.4.1575-1.8.8",
        // Has a reference to scala.actors.threadpool.Arrays which is part of org.scala-lang:scala-actors
        // Which we don't ship, but instead is a transitive of org.scala-lang:scala-actors-migration_2.11:1.1.0
        // I could add the library to this version, but i'd rather just skip it
        "1.10.2-12.18.2.2103"
    );

    private static final ComparableVersion MC_1_12 = new ComparableVersion("1.12");
    private static final ComparableVersion MC_1_12_FIXED_SRG = new ComparableVersion("1.12-14.21.0.2340");
    public static String legacyMcp(ComparableVersion forge) {
        // net/minecraft/command/AdvancementCommand$ActionType$Mode -> AdvancementCommand$Mode
        if (forge.compareTo(MC_1_12) >= 0 && forge.compareTo(MC_1_12_FIXED_SRG) < 0)
            return "mcp940.zip";
        return null;
    }

    // Has a invalid `/cp/MethodsReturnNonnullByDefault.java` patch file.
    private static final ComparableVersion NON_NULL_PATCH_START = new ComparableVersion("1.12.2-14.23.5.2839");
    private static final ComparableVersion NON_NULL_PATCH_END = new ComparableVersion("1.12.2-14.23.5.2844");
    public static boolean needsPatchFixes(Artifact name) {
        if (!Constants.FORGE_GROUP.equals(name.getGroup()) || !Constants.FORGE_NAME.equals(name.getName()))
            return false;
        var current = new ComparableVersion(name.getVersion());
        if (current.compareTo(NON_NULL_PATCH_START) >= 0 && current.compareTo(NON_NULL_PATCH_END) <= 0)
            return true;
        return false;
    }

    public static File fixPatches(Artifact artifact, File input, File output) {
        var cache = Util.cache(output)
            .add("input", output);

        if (Mavenizer.checkCache(output, cache))
            return output;

        // Has a invalid `/cp/MethodsReturnNonnullByDefault.java` patch file.
        // Guess it was created by old FG bug, so we can just kill it
        FileUtils.ensureParent(output);
        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();
                // Good night sweet prince
                if ("cp/MethodsReturnNonnullByDefault.java.patch".equals(name))
                    continue;

                var newEntry = new ZipEntry(name);
                newEntry.setTime(entry.getTime());
                zout.putNextEntry(newEntry);
                zin.transferTo(zout);
                zout.closeEntry();
            }
        } catch (IOException e) {
            Util.sneak(e);
        }

        return output;
    }

    public static Artifact fixMCPArtifact(Artifact artifact) {
        // There is an artifact created in 2014 that doesn't follow the
        // same format as the other mcp artifacts, and has bad parameter names
        // And rather then update an existing file on the maven that may be used by whatever
        // I made a new fixed file
        if ("1.7.10".equals(artifact.getVersion()))
            return artifact.withVersion("1.7.10-fixed");
        return artifact;
    }
}
