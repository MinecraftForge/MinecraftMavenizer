/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.repo.SourcesProvider;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MCPRenamer implements SourcesProvider {
    private final Artifact name;
    public final MCPSide side;

    private final Task last;

    public MCPRenamer(File build, Artifact name, MCPSide side) {
        this(build, name, side, side);
    }

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    /**
     * Creates a new renamer for the given patcher.
     *
     * @param build   The Forge repo
     * @param name    The developement artifact (usually userdev)
     * @param sources The patcher to get the unnamed sources from
     */
    public MCPRenamer(File build, Artifact name, MCPSide side, SourcesProvider sources) {
        this.name = name;
        this.side = side;

        this.last = this.remapSources(sources.getSources(), build);
    }

    /** @return The final named sources */
    public Task getSources() {
        return this.last;
    }

    private Task remapSources(Task input, File outputDir) {
        var output = new File(outputDir, "remapped.jar");
        var mappings = this.side.getMCP().getMappings("official");
        return Task.named("remap[" + this.name.getName() + ']',
            Set.of(input, mappings),
            () -> remapSourcesImpl(input, mappings, output)
        );
    }

    private static File remapSourcesImpl(Task inputTask, Task mappingsTask, File output) {
        var input = inputTask.execute();
        var mappings = mappingsTask.execute();

        var cache = HashStore.fromFile(output);
        cache.add("input", input);
        cache.add("mappings", mappings);

        if (output.exists() && cache.exists() && cache.isSame())
            return output;

        GlobalOptions.assertNotCacheOnly();

        try {
            var names = MCPNames.load(mappings);
            names.rename(new FileInputStream(input), true);

            // TODO: [MCMaven][Renamer] This garbage was copy-pasted from FG.
            // I changed the while loop to a for loop, though. I guess it is fine?
            try (var zin = new ZipInputStream(new FileInputStream(input));
                 var zout = new ZipOutputStream(new FileOutputStream(output))) {
                for (var entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));

                    if (entry.getName().endsWith(".java")) {
                        var mapped = names.rename(zin, false);
                        IOUtils.write(mapped, zout, StandardCharsets.UTF_8);
                    } else {
                        IOUtils.copy(zin, zout);
                    }
                }
            }

            HashUtils.updateHash(output, HashFunction.SHA1);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename sources for " + input.getAbsolutePath(), e);
        }

        cache.save();
        return output;
    }
}
