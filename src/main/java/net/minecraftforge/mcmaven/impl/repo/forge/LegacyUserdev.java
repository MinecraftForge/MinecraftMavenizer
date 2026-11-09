package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.*;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static net.minecraftforge.mcmaven.impl.repo.forge.PythonForgeSource.safeRelative;

public class LegacyUserdev extends FG2Userdev {
    private static final Artifact FML_ARGO = Artifact.from("net.sourceforge.argo:argo:3.2");
    private static final Artifact RUNTIME_ARGO = Artifact.from("net.sourceforge.argo:argo:2.25");

    private static final List<Artifact> FML_LIBRARIES = List.of(
            Artifact.from("com.google.guava:guava:14.0-rc3"),
            Artifact.from("org.bouncycastle:bcprov-jdk15on:1.48"),
            Artifact.from("org.ow2.asm:asm-all:4.1")
    );

    private static final List<String> STRIP_SOURCE_PACKAGES = List.of(
            "paulscode/",
            "com/jcraft/",
            "isom/",
            "ibxm/",
            "de/matthiasmann/twl/",
            "org/xmlpull/",
            "javax/xml/",
            "com/fasterxml/",
            "javax/ws/",
            "org/bouncycastle/",
            "argo/"
    );

    private static final List<String> RESTORE_EMBEDDED_CLASSES = List.of(
            "paulscode/sound/",
            "com/jcraft/jogg/",
            "com/jcraft/jorbis/",
            "ibxm/"
    );

    private final File build;

    public LegacyUserdev(File build, ForgeRepo forge, Artifact name) {
        super(build, forge, name, FGVersion.v1);
        this.build = build;
    }

    @Override
    protected Task injectLegacy() {
        var cleanSources = Task.named("mcp-strip-sources",
                Task.deps(getMcpChild().getFinalStep()),
                () -> stripDecompiledSources(getMcpChild().getFinalStep().execute())
        );

        return Task.named("inject",
                Task.deps(cleanSources),
                () -> super.injectLegacy(cleanSources.execute())
        );
    }

    private File stripDecompiledSources(File input) {
        var output = new File(this.build, "mcp-stripped-sources.jar");
        var cache = Util.cache(output)
                .add("input", input)
                .addKnown("filter", "legacy-strip-sources-v1");

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();

                if (shouldStripSource(name)) {
                    continue;
                }

                zout.putNextEntry(FileUtils.getStableEntry(name));
                zin.transferTo(zout);
                zout.closeEntry();
            }
            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to strip third-party sources from " + input.getAbsolutePath(), e);
        }
    }

    private boolean shouldStripSource(String path) {
        if (!path.endsWith(".java")) {
            return false;
        }
        for (var pkg : STRIP_SOURCE_PACKAGES) {
            if (path.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Task getSources() {
        var baseSources = super.getSources();
        var clientJar = this.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR);

        return Task.named("finalize-legacy-sources",
                Task.deps(baseSources, clientJar),
                () -> finalizeArtifact(baseSources.execute(), clientJar.execute())
        );
    }

    private File finalizeArtifact(File baseSourceJar, File clientJar) {
        var output = new File(this.build, "final-forge-sources.jar");
        var cache = Util.cache(output)
                .add("sources", baseSourceJar)
                .add("client", clientJar)
                .addKnown("finalizer", "legacy-inject-classes-v1");

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var zout = new ZipOutputStream(new FileOutputStream(output))) {
            var seen = new HashSet<String>();

            try (var zin = new ZipInputStream(new FileInputStream(baseSourceJar))) {
                for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                    seen.add(entry.getName());
                    zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));
                    zin.transferTo(zout);
                    zout.closeEntry();
                }
            }

            try (var zip = new ZipFile(clientJar)) {
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    var entry = entries.nextElement();
                    var name = entry.getName();

                    if (entry.isDirectory() || !name.endsWith(".class") || !isEmbeddedClass(name)) {
                        continue;
                    }
                    if (!seen.add(name)) {
                        continue;
                    }

                    zout.putNextEntry(FileUtils.getStableEntry(name));
                    try (var in = zip.getInputStream(entry)) {
                        in.transferTo(zout);
                    }
                    zout.closeEntry();
                }
            }

            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to assemble final legacy artifact", e);
        }
    }

    private boolean isEmbeddedClass(String name) {
        for (var pkg : RESTORE_EMBEDDED_CLASSES) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<File> getClasspath() {
        var classpath = new ArrayList<>(super.getClasspath());
        classpath.add(this.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR).execute());
        classpath.add(getCache().maven().download(FML_ARGO));
        return classpath;
    }

    @Override
    public List<MinecraftTasks.ArtifactFile> getLibraryFiles() {
        var libraryFiles = super.getLibraryFiles();
        var cache = getCache();

        for (var artifact : FML_LIBRARIES) {
            libraryFiles.add(new MinecraftTasks.ArtifactFile(artifact, Util.getArtifact(cache, artifact, true)));
        }
        return libraryFiles;
    }

    @Override
    public List<Artifact> getLibraries() {
        var ret = new ArrayList<>(FML_LIBRARIES);
        ret.add(RUNTIME_ARGO);

        var mc = this.getMCP().getMinecraftTasks();
        for (var lib : mc.getClientLibraries()) {
            ret.add(lib.artifact());
        }
        return ret;
    }

    @Override
    public void forAllLibraries(java.util.function.Consumer<Artifact> consumer, java.util.function.Predicate<Artifact> filter) {
        for (var library : getLibraries()) {
            if (filter == null || filter.test(library)) {
                consumer.accept(library);
            }
        }
    }
}
