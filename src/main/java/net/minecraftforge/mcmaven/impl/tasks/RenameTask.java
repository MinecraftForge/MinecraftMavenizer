/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.tasks;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.hash.HashUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Takes a input jar file filled with SRG named sources.
 * Applies mappings provided by a ziped csv file to them and returns the new sources.
 */
public final class RenameTask implements Task {
    private final String name;
    private final MCPSide side;
    private final boolean javadocs;
    private final Task task;

    /**
     * Creates a new renamer for the given patcher.
     *
     * @param build   The directory where the output will be stored
     * @param name    The development artifact, only used for the task name
     * @param sources The task that creates the unnamed sources
     * @param javadocs Wither to inject javadocs and rename lambda parameters, false is used for ForgeDev as we remap to SRG patches when making userdev
     */
    public RenameTask(File build, String name, MCPSide side, Task sources, Mappings mappings, boolean javadocs) {
        this.name = name;
        this.side = side;
        this.javadocs = javadocs;
        this.task = this.remapSources(sources, mappings.getFolder(build), mappings);
    }

    @Override
    public File execute() {
        return this.task.execute();
    }

    @Override
    public boolean resolved() {
        return this.task.resolved();
    }

    @Override
    public String name() {
        return this.task.name();
    }

    private Task remapSources(Task input, File outputDir, Mappings provider) {
        var output = new File(outputDir, !this.javadocs ? "remapped.jar" : "remapped-javadoc.jar");
        var mappings = provider.getCsvZip(side);
        var srg = this.javadocs ? side.getTasks().getMappings() : null;
        var legacy = StupidHacks.isLegacyRenamer(side.getMCP().getMinecraftTasks().getVersion());
        return Task.named("remap[" + this.name + "][" + provider + ']' + (!this.javadocs ? "" : "[javadoc]"),
            Task.deps(input, mappings, srg),
            () -> remapSourcesImpl(input, mappings, output, srg, legacy)
        );
    }

    private static File remapSourcesImpl(Task inputTask, Task mappingsTask, File output, Task srgTask, boolean legacy) {
        var input = inputTask.execute();
        var mappings = mappingsTask.execute();
        var srg = srgTask == null ? null : srgTask.execute();

        var cache = Util.cache(output)
            .add("input", input)
            .add("mappings", mappings);
        if (srg != null)
            cache.add("whitelist", srg);
        if (legacy)
            cache.addKnown("legacy", "true");

        if (Mavenizer.checkCache(output, cache))
            return output;

        try {
            var names = MCPNames.load(mappings);

            Set<String> vanillaClasses = null;
            if (srg != null) {
                vanillaClasses = new HashSet<>();
                var map = IMappingFile.load(srg);
                for (var cls : map.getClasses()) {
                    if (cls.getMapped().indexOf('$') == -1) // Outer classes only
                        vanillaClasses.add(cls.getMapped() + ".java");
                }
            }

            // TODO: [MCMavenizer][Renamer] This garbage was copy-pasted from FG.
            // I changed the while loop to a for loop, though. I guess it is fine?
            FileUtils.ensureParent(output);
            try (var zin = new ZipInputStream(new FileInputStream(input));
                 var zout = new ZipOutputStream(new FileOutputStream(output))) {
                for (var entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));

                    if (entry.getName().endsWith(".java")) {
                        // We only care about injecting javadocs into decompiled classes, patcher classes should have their own docs
                        var javadocs = vanillaClasses != null && vanillaClasses.contains(entry.getName());
                        var mapped = legacy
                            ? names.renameLegacy(zin, javadocs, StandardCharsets.UTF_8)
                            : names.rename(zin, javadocs, javadocs);

                        for (var itr = mapped.iterator(); itr.hasNext(); ) {
                            var line = itr.next();
                            zout.write(line.getBytes(StandardCharsets.UTF_8));
                            if (itr.hasNext())
                                zout.write('\n');
                        }
                    } else {
                        zin.transferTo(zout);
                    }
                }
            }

            HashUtils.updateHash(output, HashFunction.sha1());
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename sources for " + input.getAbsolutePath(), e);
        }

        cache.save();
        return output;
    }
}
