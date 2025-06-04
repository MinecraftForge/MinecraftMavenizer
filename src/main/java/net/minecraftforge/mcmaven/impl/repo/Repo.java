package net.minecraftforge.mcmaven.impl.repo;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.forge.ForgeRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.hash.HashUtils;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.util.function.Supplier;

public abstract class Repo {
    protected final Cache cache;
    protected final File output;

    protected Repo(Cache cache, File output) {
        this.cache = cache;
        this.output = output;
    }

    public final Cache getCache() {
        return cache;
    }

    public final File getOutput() {
        return output;
    }

    public abstract void process(String module, String version);

    protected static PendingArtifact pending(String message, Task task, Artifact artifact) {
        return new PendingArtifact(message, task, artifact);
    }

    protected final OutputArtifact[] output(PendingArtifact... pendingArtifacts) {
        var ret = new OutputArtifact[pendingArtifacts.length];
        for (var i = 0; i < pendingArtifacts.length; i++) {
            var pending = pendingArtifacts[i];
            try {
                var output = new File(this.output, pending.getArtifact().getLocalPath());
                org.apache.commons.io.FileUtils.copyFile(pending.get(), output);
                HashUtils.updateHash(output);
                ret[i] = new OutputArtifact(output, pending.getArtifact());
            } catch (Throwable t) {
                throw new RuntimeException("Failed to generate artifact: %s".formatted(pending.getArtifact()), t);
            }
        }
        return ret;
    }

    protected static final class PendingArtifact implements Supplier<File> {
        private final String message;
        private final Task task;
        private final Artifact artifact;

        private PendingArtifact(String message, Task task, Artifact artifact) {
            this.message = message;
            this.task = task;
            this.artifact = artifact;
        }

        @Override
        public File get() {
            if (this.task.resolved())
                return this.task.get();

            try {
                Log.info(this.message);
                Log.push();
                return this.task.execute();
            } finally {
                Log.pop();
            }
        }

        public Task getAsTask() {
            return task;
        }

        public Artifact getArtifact() {
            return artifact;
        }
    }

    protected record OutputArtifact(File get, Artifact artifact) implements Supplier<File> {
        public OutputArtifact withFile(File file) {
            return new OutputArtifact(file, this.artifact);
        }
    }
}
