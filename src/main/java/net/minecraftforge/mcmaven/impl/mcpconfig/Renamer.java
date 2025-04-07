/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mcpconfig;

import net.minecraftforge.mcmaven.impl.HasNamedSources;
import net.minecraftforge.mcmaven.impl.HasUnnamedSources;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Renamer implements HasNamedSources {
    private final Artifact name;
    public final MCPSide side;

    private final Task last;

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    /**
     * Creates a new renamer for the given patcher.
     *
     * @param build   The Forge repo
     * @param name    The developement artifact (usually userdev)
     * @param patcher The patcher to get the unnamed sources from
     */
    public Renamer(File build, Artifact name, MCPSide side, HasUnnamedSources patcher) {
        this.name = name;
        this.side = side;

        this.last = this.remapSources(patcher.getUnnamedSources(), build);
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message);
    }

    private RuntimeException except(String message, Throwable e) {
        return new IllegalArgumentException("Invalid Patcher Dependency: " + this.name + " - " + message, e);
    }

    /** @return The final named sources */
    public Task getNamedSources() {
        return this.last;
    }

    private Task remapSources(Task input, File outputDir) {
        var output = new File(outputDir, "remapped.jar");
        var mappings = this.side.getTasks().getMappings("official");
        return Task.named("remap[" + this.name.getName() + ']',
            Set.of(input, mappings),
            () -> remapSourcesImpl(input, mappings, output)
        );
    }

    private File remapSourcesImpl(Task inputTask, Task mappingsTask, File output) {
        var input = inputTask.execute();
        var mappings = mappingsTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("mappings", mappings);

        if (output.exists() && cache.exists())
            return output;

        try {
            var names = MCPNames.load(mappings);
            names.rename(new FileInputStream(input), true);

            // TODO: [MCMaven][Renamer] This garbage was copy-pasted from FG.
            // I changed the while loop to a for loop, though. I guess it is fine?
            try (ZipInputStream zin = new ZipInputStream(new FileInputStream(input));
                 ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(output))) {
                for (ZipEntry entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));

                    if (entry.getName().endsWith(".java")) {
                        String mapped = names.rename(zin, false);
                        IOUtils.write(mapped, zout, StandardCharsets.UTF_8);
                    } else {
                        IOUtils.copy(zin, zout);
                    }
                }
            }

            HashUtils.updateHash(output, HashFunction.SHA1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        cache.save();
        return output;
    }
}
