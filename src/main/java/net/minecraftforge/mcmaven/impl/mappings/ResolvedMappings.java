/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.tasks.MCPNames;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IRenamer;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;
import net.minecraftforge.srgutils.IMappingFile.IParameter;

public class ResolvedMappings {
    public static final String CHANNEL_ATTR = "net.minecraftforge.mappings.channel";
    public static final String VERSION_ATTR = "net.minecraftforge.mappings.version";

    protected enum Tasks {
        CSVs("srg2names"),
        MappedToSrg("mapped2srg"),
        MappedToObf("mapped2obf");

        private final String name;

        private Tasks(String name) {
            this.name = name;
        }
    };

    protected final Map<Tasks, Task> tasks = new HashMap<>();
    private final String channel;
    private final String version;
    private final Artifact artifact;
    private final Task baseMappings;
    private final Task csvZip;
    private final Task mappedToSrg;
    private final Task mappedToObf;

    public ResolvedMappings(
        String channel, String version,
        Artifact artifact, File root,
        Task baseMappings, Task csvZip,
        @Nullable byte[] extra
    ) {
        this.channel = channel;
        this.version = version;
        this.artifact = artifact;
        this.baseMappings = baseMappings;
        this.csvZip = csvZip;
        this.mappedToObf = getTsrg(root, Tasks.MappedToObf, null);
        this.mappedToSrg = getTsrg(root, Tasks.MappedToSrg, extra);
    }

    public String channel() {
        return this.channel;
    }

    public String version() {
        return this.version;
    }

    @Override
    public String toString() {
        return channel() + '-' + version();
    }

    public File getFolder(File root) {
        return new File(root, channel() + '/' + version());
    }

    public Artifact getArtifact() {
        return this.artifact;
    }

    public Task getCsvZip() {
        return this.csvZip;
    }

    public Task getMapped2Srg() {
        return this.mappedToSrg;
    }

    public Task getMapped2Obf() {
        return this.mappedToObf;
    }





    private Task getTsrg(File root, Tasks type, @Nullable byte[] extra) {
        var csv = getCsvZip();

        return Task.named(type.name + '[' + this + ']',
            Task.deps(this.baseMappings, csv),
            () -> makeTsrg(root, this.baseMappings, csv, type == Tasks.MappedToObf, extra)
        );
    }

    private File makeTsrg(File mcpRoot, Task srgTask, Task csvTask, boolean toObf, @Nullable byte[] extra) {
        var root = getFolder(mcpRoot);
        var output = new File(root, channel() + '-' + version + '-' + (toObf ? "srg" : "obf") + ".tsrg.gz");

        var srg = srgTask.execute();
        var csv = csvTask.execute();

        var cache = Util.cache(output)
            .add("srg", srg)
            .add("csv", csv);

        if (extra != null)
            cache.add("extra", extra);

        if (Mavenizer.checkCache(output, cache))
            return output;

        try {
            var names = MCPNames.load(csv).names();

            var map = IMappingFile.load(srg); // obf2srg
            if (!toObf)
                map = map.reverse().chain(map); // srg2obf + obf2srg = srg2srg

            // Now we rename target2mapped
            map = map.rename(new IRenamer() {
                @Override
                public String rename(IField value) {
                    return names.getOrDefault(value.getMapped(), value.getMapped());
                }

                @Override
                public String rename(IMethod value) {
                    return names.getOrDefault(value.getMapped(), value.getMapped());
                }

                @Override
                public String rename(IParameter value) {
                    return names.getOrDefault(value.getMapped(), value.getMapped());
                }
            });

            // target2mapped -> mapped2target
            map = map.reverse();

            // Extra is for FG2 environements for extra 'reobf' mappings.
            // So named2srg
            if (extra != null) {
                var extraMap = IMappingFile.load(new ByteArrayInputStream(extra));
                map = map.merge(extraMap);
            }

            map.write(output.getAbsoluteFile().toPath(), IMappingFile.Format.TSRG2, false);
        } catch (IOException e) {
            Util.sneak(e);
        }

        cache.save();
        return output;
    }
}
