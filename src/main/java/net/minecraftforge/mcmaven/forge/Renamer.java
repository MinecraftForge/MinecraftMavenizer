package net.minecraftforge.mcmaven.forge;

import net.minecraftforge.mcmaven.mcpconfig.MCP;
import net.minecraftforge.mcmaven.mcpconfig.MCPNames;
import net.minecraftforge.mcmaven.util.Artifact;
import net.minecraftforge.mcmaven.util.HashFunction;
import net.minecraftforge.mcmaven.util.HashStore;
import net.minecraftforge.mcmaven.util.Log;
import net.minecraftforge.mcmaven.util.Task;
import net.minecraftforge.mcmaven.util.Util;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// TODO: [MCMaven] Move this Renamer to MCP, since renaming is not forge-specific.
/**
 * This class is responsible for naming the unnamed sources provided by the {@link Patcher}.
 */
class Renamer {
    private final ForgeRepo forge;
    private final Artifact name;
    private final File data;
    public final MCP mcp;

    private final Map<String, Task> extracts = new HashMap<>();
    private final Task last;

    // TODO: [MCMaven][Renamer] Custom mappings. For now: official.
    /**
     * Creates a new renamer for the given patcher.
     *
     * @param forge   The Forge repo
     * @param name    The developement artifact (usually userdev)
     * @param patcher The patcher to get the unnamed sources from
     */
    Renamer(ForgeRepo forge, Artifact name, Patcher patcher) {
        this.forge = forge;
        this.name = name;
        this.mcp = patcher.getMCP();

        this.data = this.forge.cache.forge.download(name);
        if (!this.data.exists())
            throw new IllegalStateException("Failed to download " + name);

        this.last = this.remapSources(patcher.getUnnamedSources(), this.forge.build);
    }

    private static void log(String message) {
        Log.log(message);
    }

    private static void debug(String message) {
        Log.debug(message);
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
        var mappings = this.mcp.getSide("joined").getTasks().getMappings("official");
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
                    zout.putNextEntry(Util.getStableEntry(entry.getName()));

                    if (entry.getName().endsWith(".java")) {
                        String mapped = names.rename(zin, false);
                        IOUtils.write(mapped, zout, StandardCharsets.UTF_8);
                    } else {
                        IOUtils.copy(zin, zout);
                    }
                }
            }

            Util.updateHash(output, HashFunction.SHA1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        cache.save();
        return output;
    }
}
