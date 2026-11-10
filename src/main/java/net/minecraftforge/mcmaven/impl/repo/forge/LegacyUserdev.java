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

    private final File build;
    private final Artifact name;

    public LegacyUserdev(File build, ForgeRepo forge, Artifact name) {
        super(build, forge, name, FGVersion.v1);
        this.build = build;
        this.name = name;
    }

    @Override
    public List<File> getClasspath() {
        var classpath = new ArrayList<>(super.getClasspath());
        classpath.add(this.getMCP().getMinecraftTasks().versionFile(MinecraftTasks.MCFile.CLIENT_JAR).execute());
        return classpath;
    }

    @Override
    public List<MinecraftTasks.ArtifactFile> getLibraryFiles() {
        var libraryFiles = super.getLibraryFiles();
        var cache = getCache();

        for (var artifact : StupidHacks.getPre16Deps(Util.forgeToMcVersion(name.getVersion()))) {
            libraryFiles.add(new MinecraftTasks.ArtifactFile(artifact, Util.getArtifact(cache, artifact, true)));
        }
        return libraryFiles;
    }

    @Override
    public List<Artifact> getLibraries() {
        List<Artifact> pre16Deps = StupidHacks.getPre16Deps(Util.forgeToMcVersion(name.getVersion()));
        var ret = new ArrayList<>(pre16Deps);

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
