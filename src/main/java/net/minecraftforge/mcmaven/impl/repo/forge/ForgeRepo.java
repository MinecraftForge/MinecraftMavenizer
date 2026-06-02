/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.tasks.RecompileTask;
import net.minecraftforge.mcmaven.impl.tasks.RenameTask;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.POMBuilder.Dependencies.Dependency;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.RunConfig;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// TODO: [MCMavenizer][ForgeRepo] For now, the ForgeRepo needs to be fully complete with everything it has to do.
// later, we can worry about refactoring it so that other repositories such as MCP (clean) and FMLOnly can function.
// And yes, I DO want this tool to support as far back as possible. But for now, we worry about UserDev3 and up.
// - Jonathing

/** Represents the Forge repository. */
public final class ForgeRepo extends Repo {
    // TODO: [MCMavenizer][FGVersion] Handle this as an edge-case in FGVersion
    private static final ComparableVersion USERDEV3_START = new ComparableVersion("1.12.2-14.23.5.2851");
    private static final ComparableVersion USERDEV3_END = new ComparableVersion("1.12.3"); //There is no 1.12.3, but I haven't disabled Userdev3 for 1.12.2 yet, so
    private static final ComparableVersion PYTHON_START = new ComparableVersion("1.1-1.3.2.1");
    private static final ComparableVersion PYTHON_END = new ComparableVersion("1.7.2-10.12.0.967");

    final MCPConfigRepo mcpconfig;
    final File globalBuild;

    /**
     * Creates a new Forge repository.
     *
     * @param cache     The cache directory
     * @param mcpconfig The MCPConfig repo
     */
    public ForgeRepo(Cache cache, MCPConfigRepo mcpconfig) {
        super(cache);
        this.mcpconfig = mcpconfig;
        this.globalBuild = new File(cache.root(), "forge/.global");
    }

    private static boolean isPython(String version) {
        var ver = new ComparableVersion(version);
        return ver.compareTo(PYTHON_START) >= 0 && ver.compareTo(PYTHON_END) < 0;
    }

    @Override
    public List<PendingArtifact> process(Artifact artifact, Mappings mappings, Map<String, Supplier<String>> outputJson) {
        var module = artifact.getGroup() + ':' + artifact.getName();
        var version = artifact.getVersion();
        if (!Constants.FORGE_ARTIFACT.equals(module))
            throw new IllegalArgumentException("Unknown or unsupported module: " + module);

        var fg = FGVersion.fromForge(version);
        LOGGER.info("Processing Minecraft Forge (userdev): " + version);
        var indent = LOGGER.push();
        try {
            // TODO [MCMavenizer][Backporting] You know what has to be done eventually...
            if (isPython(version)) {
                Info.gatherVariants(this.cache.maven(), version);
                throw new IllegalArgumentException("Python version unsupported!");
            }

            if (fg == null)
                throw new IllegalArgumentException("Unknown Forge version " + version);

            switch (fg) {
                case v1:
                case v1_1:
                case v1_2: // V2 can process V1
                case v2_0_1:
                case v2_0_2:
                case v2:
                case v2_1:
                case v2_2:
                case v2_3:
                    return processV2(version, mappings, outputJson, fg);
                case v3:
                case v4:
                case v5:
                case v6:
                    return processV3(version, mappings, outputJson);
                default:
                    throw new IllegalArgumentException("Forge version %s is not supported yet".formatted(version));
            }
        } finally {
            LOGGER.pop(indent);
        }
    }

    private static Artifact getUserdev(String forge) {
        var forgever = new ComparableVersion(forge);
        // userdev3, old attempt to make 1.12.2 FG3 compatible
        var userdev3 = forgever.compareTo(USERDEV3_START) >= 0 && forgever.compareTo(USERDEV3_END) < 0;
        return Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, userdev3 ? "userdev3" : "userdev", "jar");
    }

    /// This handles legacy FG2.0->2.3 artifacts
    ///
    /// We need to generate the following artifacts:
    /// - `net.minecraftforge:forge:{version}`
    ///   - default:
    ///     - The default jar contains the recompiled class files, patcher assets
    ///   - sources:
    ///     - Source files used to recompile the default jar.
    ///   - metadata.zip:
    ///     - Metadata about the version, such as runs.json and version.json.
    /// exist.
    /// - `net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip`
    ///   - A zip file containing fields, methods, and params.csv files mapping SRG->MCP names.
    private List<PendingArtifact> processV2(String version, Mappings baseMappings, Map<String, Supplier<String>> outputJson, FGVersion fgVersion) {
        var name = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, version);
        var userdev = getUserdev(version);

        var build = new File(this.cache.root(), "forge/" + userdev.getFolder());
        var jdks = this.cache.jdks();

        var dev = new FG2Userdev(build, this, userdev, fgVersion);
        var mcVersion = Util.forgeToMcVersion(version);
        var srgTask = dev.getMCP().getMappings();
        var srgSources = dev.getSources();

        var mappings = baseMappings.withContext(dev);

        Task sourcesTask = srgSources;
        // v1 and v1.1 have Forge patches in mapped names, and I haven't implemented converting to SRG yet as it would need to run Srg2Source
        // So check if we are using the 'correct' mappings, and if not throw and exception
        if (fgVersion == FGVersion.v1 || fgVersion == FGVersion.v1_1) {
            var required = StupidHacks.getDefaultMappings(new ComparableVersion(version));
            if (!required.channel().equals(mappings.channel()) || !required.version().equals(mappings.version()))
                throw new IllegalStateException("Can not setup " + name + " as it requires you to us the explicit mappings: " + required.toString());
            sourcesTask = dev.getSourcesWithJavadocs();
        } else if (!mappings.channel().equals("srg")) {
            sourcesTask = new RenameTask(build, userdev.getName(), srgSources, mappings, true, srgTask, mcVersion);
        }
        var classesTask = new RecompileTask(build, name, jdks, dev.getJavaTarget(), dev::getClasspath, sourcesTask, mappings);

        var mappingCoords = mappings.getArtifact();

        var mappingArtifacts = mappingArtifacts(build, mappings, mcVersion, outputJson);

        var sources = pending("Sources", sourcesTask, name.withClassifier("sources"), true, sourceVariant(baseMappings));
        var classes = pending("Classes", classesTask, name, false, () -> classVariants(baseMappings, dev, mappingCoords));
        var metadata = pending("Metadata", metadata(build, dev, dev.getRuns()), name.withClassifier("metadata").withExtension("zip"), false, metadataVariant());

        var pom = pending("Maven POM", pom(mappings.getFolder(build), dev, version, null, mappingCoords), name.withExtension("pom"), false);

        // Gradle only allows downloading artifacts from one repo, so we need to pull in any classifers that we reference
        var classifiers = getClassifieres(name, dev.getLibraries(), new HashMap<>());

        addJsonData(outputJson, dev);

        var ret = new ArrayList<PendingArtifact>();
        ret.addAll(mappingArtifacts);
        ret.addAll(List.of(sources, classes, pom, metadata));
        ret.addAll(classifiers.values());
        return ret;
    }


    /// This handles UserDev3 artifacts, which are anything created using FG 3->6
    ///
    /// We need to generate the following artifacts:
    /// - `net.minecraftforge:forge:{version}`
    ///   - default:
    ///     - The default jar contains the recompiled class files, patcher assets
    ///   - sources:
    ///     - Source files used to recompile the default jar.
    ///   - metadata.zip:
    ///     - Metadata about the version, such as runs.json and version.json.
    /// - `net.minecraft:{mcp-version}:client`
    ///   - extra:
    ///     - This is the client jar file with class files removed. This is for legacy versions which expect it to
    /// exist.
    /// - `net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip`
    ///   - A zip file containing fields, methods, and params.csv files mapping SRG->MCP names.
    ///
    /// All variants need to provide their gradle module metadata information to be merged as needed.
    ///
    /// Currently, we don't support any non-standard variants. So there is no merging. But my idea is that the custom
    /// mapping channels would be custom attributes. As well as a new flag to skip the client-extra, and merge it in the
    /// main jar instead.
    ///
    /// If the mappings are `official`, we also need to generate:
    ///   - pom:
    ///     - Standard maven pom file that contains all dependency information.
    // Made this an MD comment to make it easier to read in IDE - Jonathan
    private List<PendingArtifact> processV3(String version, Mappings baseMappings, Map<String, Supplier<String>> outputJson) {
        var name = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, version);
        var userdev = getUserdev(version);

        var build = new File(this.cache.root(), "forge/" + userdev.getFolder());
        var jdks = this.cache.jdks();

        var patcher = new Patcher(build, this, userdev);
        var joined = patcher.getMCP().getSide(MCPSide.JOINED);
        var mcVersion = joined.getMCP().getMinecraftTasks().getVersion();
        var mappings = baseMappings.withContext(joined);
        var srgTask = joined.getTasks().getMappings();
        var srgSources = patcher.get();

        var sourcesTask = mappings.channel().equals("srg")
            ? srgSources
            : new RenameTask(build, userdev.getName(), srgSources, mappings, true, srgTask, mcVersion);
        var recompile = new RecompileTask(build, name, jdks, patcher.getJavaTarget(), patcher::getClasspath, sourcesTask, mappings);
        var classesTask = new InjectTask(build, this.cache, name, patcher, recompile, mappings);

        var extraCoords = Artifact.from(Constants.MC_GROUP, Constants.MC_CLIENT + "-extra", patcher.getMCP().getName().getVersion());

        // If we are not obfuscated, don't add the csv zip as a extra artifact
        var mappingCoords = patcher.isObfuscated() ? mappings.getArtifact() : null;

        var mappingArtifacts = mappingArtifacts(build, mappings, mcVersion, outputJson);
        var mappingFolder = mappingCoords == null ? build : mappings.getFolder(build);

        var sources = pending("Sources", sourcesTask, name.withClassifier("sources"), true, sourceVariant(baseMappings));
        var classes = pending("Classes", classesTask, name, false, () -> classVariants(baseMappings, patcher, extraCoords, mappingCoords));
        var metadata = pending("Metadata", metadata(build, patcher, patcher.config.runs), name.withClassifier("metadata").withExtension("zip"), false, metadataVariant());

        var pom = pending("Maven POM", pom(mappingFolder, patcher, version, extraCoords, mappingCoords), name.withExtension("pom"), false);

        var extraOutput = this.mcpconfig.processExtra(Constants.MC_GROUP + ':' + Constants.MC_CLIENT, patcher.getMCP().getName().getVersion());

        // Gradle only allows downloading artifacts from one repo, so we need to pull in any classifers that we reference
        var classifiers = new HashMap<Artifact, PendingArtifact>();
        for (var parent : patcher.getStack())
            getClassifieres(name, parent.getLibraries(), classifiers);

        addJsonData(outputJson, patcher);

        var ret = new ArrayList<PendingArtifact>();
        ret.addAll(mappingArtifacts);
        ret.addAll(extraOutput);
        ret.addAll(List.of(sources, classes, pom, metadata));
        ret.addAll(classifiers.values());
        return ret;
    }

    // Gradle only allows downloading artifacts from one repo, so we need to pull in any classifers that we reference
    private Map<Artifact, PendingArtifact> getClassifieres(Artifact name, Collection<Artifact> artifacts, Map<Artifact, PendingArtifact> classifiers) {
        for (var artifact : artifacts) {
            if (!name.getGroup().equals(artifact.getGroup())
             || !name.getName().equals(artifact.getName())
             || !name.getVersion().equals(artifact.getVersion())
             || classifiers.containsKey(artifact)
            )
                continue;
            // Classifers can not have variants in gradle, so we just need to download them
            classifiers.put(artifact,
                pending("Classifier-" + artifact.getClassifier(),
                    Task.named(
                        "classifier[" + artifact.getClassifier() + '@' + artifact.getExtension() + ']',
                        () -> this.cache.maven().download(artifact)
                    ),
                    artifact,
                    false
                )
            );
        }
        return classifiers;
    }

    private void addJsonData(@Nullable Map<String, Supplier<String>> outputJson, ForgeVersionCommon info) {
        // Add some extra metadata for FG to consume:
        if (outputJson == null)
            return;

        var mcp = info.getMCPArtifact();
        outputJson.put("mcp.version", mcp::getVersion);
        outputJson.put("mcp.artifact", mcp::toString);
        outputJson.put("mc.version", info::getMinecraftVersion);

        var modules = info.getModules();
        if (modules == null || modules.isEmpty())
            outputJson.put("patcher.modules", () -> "");
        else
            outputJson.put("patcher.modules", () -> String.join(",", modules));
    }

    private static Task metadata(File build, ForgeVersionCommon forge, Map<String, RunConfig> runs) {
        return Task.named("metadata[forge]", Task.deps(forge.getMinecraftTasks().versionJson), () -> {
            var output = new File(build, "metadata.zip");

            // metadata
            var metadataDir = new File(output.getParentFile(), "metadata");
            var versionProperties = new File(metadataDir, "version.properties");

            // metadata/launcher
            var launcherDir = new File(metadataDir, "launcher");
            var runsJsonStr = JsonData.toJson(runs);

            // metadata/minecraft
            var minecraftDir = new File(metadataDir, "minecraft");
            var versionJson = forge.getMinecraftTasks().versionJson.execute();

            var cache = Util.cache(output)
                .add("json", versionJson)
                .add("properties", versionProperties)
                .add("runs", runsJsonStr)
                .addKnown("data", forge.getDataHash())
                .addKnown("version", "1");

            if (Mavenizer.checkCache(output, cache))
                return output;

            try {
                FileUtils.ensureParent(output);
                FileUtils.ensure(metadataDir);
                FileUtils.ensure(launcherDir);
                FileUtils.ensure(minecraftDir);

                // version.properties
                try (FileWriter writer = new FileWriter(versionProperties)) {
                    writer.append("version=1").append('\n').flush();
                }

                // runs.json
                Files.writeString(
                    new File(launcherDir, "runs.json").toPath(),
                    runsJsonStr,
                    StandardCharsets.UTF_8
                );

                // version.json
                Files.copy(
                    versionJson.toPath(),
                    new File(minecraftDir, "version.json").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );

                // metadata.zip
                FileUtils.makeZip(metadataDir, output);
            } catch (IOException e) {
                Util.sneak(e);
            }

            cache.save();
            return output;
        });
    }

    private static Task pom(File build, ForgeVersionCommon forge, String version, @Nullable Artifact clientExtra, @Nullable Artifact mappings) {
        return Task.named("pom[forge]", () -> {
            var output = new File(build, "forge.pom");

            var cache = Util.cache(output)
                .addKnown("data", forge.getDataHash())
                .addKnown("code-version", "1");

            if (clientExtra != null)
                cache.addKnown("extra", clientExtra.toString());

            if (mappings != null)
                cache.addKnown("mappings", mappings.toString());

            if (Mavenizer.checkCache(output, cache))
                return output;

            var builder = new POMBuilder(Constants.FORGE_GROUP, Constants.FORGE_NAME, version).preferGradleModule().dependencies(dependencies -> {
                if (clientExtra != null)
                    dependencies.add(clientExtra);

                if (mappings != null)
                    dependencies.add(mappings);

                forge.forAllLibraries(dependencies::add, Artifact::hasNoOs);

                // Following https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#dependency-scope
                // PROVIDED == 'compileOnly'
                for (var descriptor : forge.getCompileOnly())
                    dependencies.add(Artifact.from(descriptor), Dependency.Scope.PROVIDED);

                // RUNTIME = 'runtimeOnly'
                for (var descriptor : forge.getRuntimeOnly())
                    dependencies.add(Artifact.from(descriptor), Dependency.Scope.RUNTIME);
            });

            FileUtils.ensureParent(output);
            try (var os = new FileOutputStream(output)) {
                os.write(builder.build().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | ParserConfigurationException | TransformerException e) {
                Util.sneak(e);
            }

            cache.save();
            return output;
        });
    }

    @SuppressWarnings("deprecation")
    protected GradleModule.Variant[] classVariants(Mappings mappings, ForgeVersionCommon patcher, Artifact... extraDeps) {
        var libs = new ArrayList<>(Arrays.asList(extraDeps));
        patcher.forAllLibraries(libs::add);

        var extraCompile = new ArrayList<Artifact>();
        for (var descriptor : patcher.getCompileOnly())
            extraCompile.add(Artifact.from(descriptor));
        var extraRuntime = new ArrayList<Artifact>();
        for (var descriptor : patcher.getRuntimeOnly())
            extraRuntime.add(Artifact.from(descriptor));


        return super.classVariants(mappings, patcher.getMinecraftTasks().getJavaVersion(), libs, extraCompile);
    }


    // Old code that I think may be useful for older versions, so I don't want to delete quite yet, delete when all legacy versions are supported
    public static class Info {
        public static final Map<String, String> MAPPINGS = new LinkedHashMap<>();
        public static final Map<String, String> CONF = new LinkedHashMap<>();

        private static String hashEntries(ZipFile zip, List<String> entries) {
            Collections.sort(entries);
            var bos = new ByteArrayOutputStream();
            for (var name : entries) {
                var entry = zip.getEntry(name);
                try {
                    var stream = zip.getInputStream(entry);
                    stream.transferTo(bos);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return HashFunction.sha1().hash(bos.toByteArray());
        }
        private static void zip(ZipFile zip, List<String> entries, String suffix, String version, String hash) {
            Collections.sort(entries);
            var output = new File(version + '-' + suffix + '-' + hash + ".zip");
            try (var out = new ZipOutputStream(new FileOutputStream(output))) {
                for (var ent : entries) {
                    var entry = zip.getEntry(ent);
                    var name = entry.getName().replace("conf/", "");
                    if (name.isEmpty() || entry.isDirectory())
                        continue;
                    byte[] data = zip.getInputStream(entry).readAllBytes();

                    switch (name) {
                        case "packaged.srg":
                            name = "joined.srg";
                            var map = IMappingFile.load(new ByteArrayInputStream(data));
                            var tmp = Path.of("Z:/test/_test/joined.srg");
                            map.write(tmp, IMappingFile.Format.SRG, false);
                            data = Files.readAllBytes(tmp);
                            break;
                        case "packaged.exc":
                            name = "joined.exc";
                            break;
                        case "Start.java":
                            name = "patches/Start.java";
                            continue;
                            //break;
                    }
                    if (name.endsWith(".csv"))
                        name = name.substring(name.indexOf('/') + 1);
                    if (name.startsWith("minecraft_ff/"))
                        name = "patches/minecraft_merged_ff/" + name.substring(13);

                    var newEntry = new ZipEntry(name);
                    newEntry.setTime(entry.getTime());
                    out.putNextEntry(newEntry);
                    out.write(data);
                    out.closeEntry();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        private static List<PendingArtifact> gatherVariants(MavenCache cache, String version) {
            var userdev = getUserdev(version);
            var mappingsFiles = new ArrayList<String>();
            var confFiles = new ArrayList<String>();

            var file = cache.download(userdev);
            try (var zip = new ZipFile(file)) {
                for (var itr = zip.entries().asIterator(); itr.hasNext(); ) {
                    var entry = itr.next();
                    var ename = entry.getName();
                    var filename = ename.indexOf('/') == -1 ? ename : ename.substring(ename.indexOf('/') + 1);

                    if ("fields.csv".equals(filename) || "methods.csv".equals(filename) || "params.csv".equals(filename)) {
                        mappingsFiles.add(ename);
                    } else if (ename.startsWith("conf/")) {
                        confFiles.add(ename);
                    }
                }

                var hash = hashEntries(zip, mappingsFiles);
                if (!MAPPINGS.containsKey(hash)) {
                    System.out.println("New Mappings: " + hash + " " + version);
                    MAPPINGS.put(hash, version);
                    zip(zip, mappingsFiles, "mappings", version, hash);
                }
                hash = hashEntries(zip, confFiles);
                if (!CONF.containsKey(hash)) {
                    System.out.println("New Conf:     " + hash + " " + version);
                    CONF.put(hash, version);
                    zip(zip, confFiles, "conf", version, hash);
                }

            } catch (IOException e) {
                throw new IllegalStateException("Error reading config " + file.getAbsolutePath(), e);
            }
            return List.of();
        }
        public static void finish() {
            if (!MAPPINGS.isEmpty()) {
                System.out.println("Mappings: ");
                for (var entry : MAPPINGS.entrySet())
                    System.out.println("\t" + entry.getKey() + ' ' + entry.getValue());
            }
            if (!CONF.isEmpty()) {
                System.out.println("Conf: ");
                for (var entry : CONF.entrySet())
                    System.out.println("\t" + entry.getKey() + ' ' + entry.getValue());
            }
        }
    }
}
