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

public class LegacyUserdev extends FG2Userdev{
    private final ForgeRepo forge;
    private final File build;

    private static final Artifact FML_ARGO = Artifact.from("net.sourceforge.argo:argo:3.2");
    private static final Artifact RUNTIME_ARGO = Artifact.from("net.sourceforge.argo:argo:2.25");

    private static final List<Artifact> FML_LIBRARIES = List.of(
            Artifact.from("com.google.guava:guava:14.0-rc3"),
            Artifact.from("org.bouncycastle:bcprov-jdk15on:1.48"),
            Artifact.from("org.ow2.asm:asm-all:4.1")
    );

    private static final List<String> STRIP_PACKAGES = List.of(
            "paulscode/sound/",
            "com/jcraft/jogg/",
            "com/jcraft/jorbis/",
            "org/bouncycastle/",
            "argo/"
    );

    LegacyUserdev(File build, ForgeRepo forge, Artifact name) {
        super(build, forge, name, FGVersion.v1);
        this.forge = forge;
        this.build = build;
    }

    @Override
    protected Task injectLegacy() {
        var strippedSources = Task.named("strip-legacy-libraries",
                Task.deps(getMcpChild().getFinalStep()),
                () -> stripThirdPartyLibraries(getMcpChild().getFinalStep().execute())
        );

        return Task.named("inject",
                Task.deps(strippedSources),
                () -> super.injectLegacy(strippedSources.execute())
        );
    }

    private File stripThirdPartyLibraries(File input) {
        var output = new File(this.build, "stripped-mcp-sources.jar");
        var cache = Util.cache(output)
                .add("input", input)
                .addKnown("filter", "legacy-strip-v3");

        if (Mavenizer.checkCache(output, cache))
            return output;

        FileUtils.ensureParent(output);
        try (var zin = new ZipInputStream(new FileInputStream(input));
             var zout = new ZipOutputStream(new FileOutputStream(output))) {

            for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                var name = entry.getName();

                if (shouldStrip(name)) {
                    continue;
                }

                zout.putNextEntry(FileUtils.getStableEntry(name));
                zin.transferTo(zout);
                zout.closeEntry();
            }
            cache.save();
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to strip legacy libraries from " + input.getAbsolutePath(), e);
        }
    }

    private boolean shouldStrip(String path) {
        if (!path.endsWith(".java")) {
            return false;
        }
        for (var pkg : STRIP_PACKAGES) {
            if (path.startsWith(pkg)) {
                return true;
            }
        }
        return false;
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
    public List<File> getClasspath() {
        var ret = new ArrayList<>(super.getClasspath());

        ret.add(this.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR).execute());

        ret.add(getCache().maven().download(RUNTIME_ARGO));

        return ret;
    }

    @Override
    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        for (var library : getLibraries()) {
            if (filter == null || filter.test(library)) {
                consumer.accept(library);
            }
        }
    }
}
