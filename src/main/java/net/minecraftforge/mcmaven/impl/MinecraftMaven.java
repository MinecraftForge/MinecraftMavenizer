/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.data.GradleModule;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import net.minecraftforge.mcmaven.impl.repo.Repo.PendingArtifact;
import net.minecraftforge.mcmaven.impl.repo.forge.FGVersion;
import net.minecraftforge.mcmaven.impl.repo.forge.ForgeRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MinecraftTasks;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.POMBuilder;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashUtils;
import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

// TODO [MCMavenizer][Deobf] ADD DEOBF
//  use single detached configuration to resolve individual configurations
//  pass in downloaded files to mcmaven (absolute path)
public record MinecraftMaven(
    File output,
    boolean dependenciesOnly,
    Cache cache,
    Mappings mappings,
    Map<String, String> foreignRepositories,
    boolean globalAuxiliaryVariants,
    boolean disableGradle,
    boolean stubJars,
    Set<String> mcpConfigVersions,
    List<File> accessTransformer,
    List<File> facadeConfigs,
    @Nullable File outputJsonFile
) {
    // Only 1.14.4+ has official mappings, we can support more when we add more mappings
    private static final MinecraftVersion MIN_OFFICIAL_MAPPINGS = MinecraftVersion.from("1.14.4");
    private static final ComparableVersion MIN_SUPPORTED_FORGE = new ComparableVersion("1.14.4");

    public MinecraftMaven {
        LOGGER.info("  Output:             " + output.getAbsolutePath());
        if (outputJsonFile != null)
            LOGGER.info("  Output JSON:        " + outputJsonFile.getAbsolutePath());
        LOGGER.info("  Dependencies Only:  " + dependenciesOnly);
        LOGGER.info("  Cache:              " + cache.root().getAbsolutePath());
        LOGGER.info("  JDK Cache:          " + cache.jdks().root().getAbsolutePath());
        LOGGER.info("  Offline:            " + Mavenizer.isOffline());
        LOGGER.info("  Cache Only:         " + Mavenizer.isCacheOnly());
        LOGGER.info("  Ignore Cache:       " + Mavenizer.ignoreCache());
        LOGGER.info("  Mappings:           " + mappings);
        if (!foreignRepositories.isEmpty())
            LOGGER.info("  Foreign Repos:      [" + String.join(", ", foreignRepositories.values()) + ']');
        LOGGER.info("  GradleVariantHack:  " + globalAuxiliaryVariants);
        LOGGER.info("  Disable Gradle:     " + disableGradle);
        LOGGER.info("  Stub Jars:          " + stubJars);
        if (!accessTransformer.isEmpty())
            filter("  Access Transformer: ", accessTransformer);
        if (!facadeConfigs.isEmpty())
            filter("  Facade Config:      ", facadeConfigs);
        LOGGER.info();
    }

    private static void filter(String header, List<File> files) {
        var prefix = header;
        var itor = files.iterator();
        while (itor.hasNext()) {
            var file = itor.next();
            LOGGER.getInfo().print(prefix);
            if (prefix == header)
                prefix = " ".repeat(header.length());

            LOGGER.getInfo().print(file.getAbsolutePath());

            if (!file.exists()) {
                LOGGER.getInfo().print(" SKIPPING DOESN'T EXIST");
                itor.remove();
            }
            LOGGER.getInfo().println();
        }
        LOGGER.getInfo().println();
    }

    public void run(Artifact artifact) {
        var module = artifact.getGroup() + ':' + artifact.getName();
        var version = artifact.getVersion();
        LOGGER.info("Processing Minecraft dependency: %s:%s".formatted(module, version));
        Map<String, Supplier<String>> outputJson = null;
        if (outputJsonFile != null) {
            outputJson = new HashMap<>();
            outputJson.put("spec", () -> "1");
        }

        var mcprepo = new MCPConfigRepo(this.cache, dependenciesOnly);
        if (Constants.FORGE_GROUP.equals(artifact.getGroup()) && Constants.FORGE_NAME.equals(artifact.getName())) {
            var repo = new ForgeRepo(this.cache, mcprepo);
            createForge(artifact, mcprepo, repo, outputJson);
        } else if (Constants.MC_GROUP.equals(artifact.getGroup())) {
            createMinecraft(artifact, mcprepo, outputJson);
        } else {
            throw new IllegalArgumentException("Artifact '%s' is currently Unsupported. Will add later".formatted(module));
        }

        if (outputJson != null) {
            var finalized = new TreeMap<String, String>();
            for (var entry : outputJson.entrySet())
                finalized.put(entry.getKey(), entry.getValue().get());

            var parent = outputJsonFile.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();

            try (var writer = new FileWriter(outputJsonFile)) {
                Util.GSON.toJson(finalized, writer);
            } catch (IOException e) {
                LOGGER.error("Failed to write output json file: " + outputJsonFile.getAbsolutePath(), e);
                Util.sneak(e);
            }
        }
    }

    protected void createForge(Artifact artifact, MCPConfigRepo mcprepo, ForgeRepo repo, Map<String, Supplier<String>> outputJson) {
        if (dependenciesOnly)
            throw new IllegalArgumentException("ForgeRepo doesn't currently support dependenciesOnly");

        var version = artifact.getVersion();
        if (version == null)
            throw new IllegalArgumentException("No version specified for Forge");

        if ("all".equals(version)) {
            if (outputJson != null)
                throw new IllegalArgumentException("Output Json does not support bulk operations");

            var versions = this.cache.maven().getVersions(artifact);
            var mappingCache = new HashMap<String, Mappings>();
            for (var ver : versions.reversed()) {
                var cver = new ComparableVersion(ver);
                if (cver.compareTo(MIN_SUPPORTED_FORGE) < 0)
                    continue;

                var fg = FGVersion.fromForge(ver);
                if (fg == null || fg.ordinal() < FGVersion.v3.ordinal()) // Unsupported
                    continue;

                var mappings = mappingCache.computeIfAbsent(forgeToMcVersion(ver), this.mappings::withMCVersion);
                var art = artifact.withVersion(ver);
                var artifacts = repo.process(art, mappings, outputJson);
                finalize(art, mappings, artifacts);
            }
        } else {
            var mappings = this.mappings.withMCVersion(forgeToMcVersion(version));
            var artifacts = repo.process(artifact, mappings, outputJson);
            finalize(artifact, mappings, artifacts);
        }
    }

    protected void createMinecraft(Artifact artifact, MCPConfigRepo mcprepo, Map<String, Supplier<String>> outputJson) {
        if (artifact.getVersion() == null)
            throw new IllegalArgumentException("No version specified for MCPConfig");

        var version = artifact.getVersion();
        if (version == null)
            throw new IllegalArgumentException("No version specified for Forge");

        if ("all".equals(version)) {
            if (outputJson != null)
                throw new IllegalArgumentException("Output Json does not support bulk operations");

            var manifestFile = mcprepo.getLauncherManifestTask().execute();
            var manifest = JsonData.launcherManifest(manifestFile);
            for (var ver : manifest.versions) {

                try {
                    var cver = MinecraftVersion.from(ver.id);
                    if (cver.compareTo(MIN_OFFICIAL_MAPPINGS) < 0)
                        continue;
                } catch (IllegalArgumentException e) {
                    // Invalid/unknown version, so skip.
                    continue;
                }

                var versioned = artifact.withVersion(ver.id);
                // If there is no MCPConfig, then we just produce a official named jar
                List<PendingArtifact> artifacts = null;
                if (hasMcp(mcprepo, ver.id))
                    artifacts = mcprepo.process(versioned, mappings.withMCVersion(ver.id), outputJson);
                else if (mappings.channel().equals("official") && (hasOfficialMappings(mcprepo, ver.id) || !MCPConfigRepo.isObfuscated(ver.id)))
                    artifacts = mcprepo.processWithoutMcp(versioned, mappings.withMCVersion(ver.id), outputJson);
                else {
                    LOGGER.info("Skipping " + versioned + " no mcp config");
                    continue;
                }
                finalize(versioned, mappings, artifacts);
            }
        } else {
            var mcVersion = mcpToMcVersion(version);
            var mappings = this.mappings.withMCVersion(mcVersion);

            List<PendingArtifact> artifacts = null;
            if (hasMcp(mcprepo, version))
                artifacts = mcprepo.process(artifact, mappings, outputJson);
            else if (mappings.channel().equals("official") && (hasOfficialMappings(mcprepo, mcVersion) || !MCPConfigRepo.isObfuscated(mcVersion)))
                artifacts = mcprepo.processWithoutMcp(artifact, mappings, outputJson);
            else
                throw new IllegalStateException("Can not process " + artifact + " as it does not have a MCPConfig ror official mappings");

            finalize(artifact, mappings, artifacts);
        }
    }

    // This is ugly, but there really isn't a good way to check if a file exists. DownlodUtils could probably use a 'exists' that HEAD's and returns false non 404
    private boolean hasMcp(MCPConfigRepo repo, String version) {
        if (this.mcpConfigVersions.isEmpty()) {
            var versions = repo.getCache().maven().getVersions(MCP.artifact("1.21.11"));
            this.mcpConfigVersions.addAll(versions);
        }
        return this.mcpConfigVersions.contains(version);
    }

    // No official mappings, we can't do anything
    private static boolean hasOfficialMappings(MCPConfigRepo repo, String version) {
        var tasks = repo.getMCTasks(version);
        var versionF = tasks.versionJson.execute();
        var json = JsonData.minecraftVersion(versionF);

        return json.getDownload(MinecraftTasks.MCFile.CLIENT_MAPPINGS.key) != null ||
            json.getDownload(MinecraftTasks.MCFile.SERVER_MAPPINGS.key) != null;
    }

    private static String forgeToMcVersion(String version) {
        // Save for a few april-fools versions, Minecraft doesn't use _ in their version names.
        // So when Forge needs to reference a version of Minecraft that uses - in the name, it replaces
        // it with _
        // This could cause issues if we ever support a version with _ in it, but fuck it I don't care right now.
        int idx = version.indexOf('-');
        if (idx == -1)
            throw new IllegalArgumentException("Invalid Forge version: " + version);
        return version.substring(0, idx).replace('_', '-');
    }

    public static String mcpToMcVersion(String version) {
        // MCP names can either be {MCVersion} or {MCVersion}-{Timestamp}, EXA: 1.21.1-20240808.132146
        // So lets see if the thing following the last - matches a timestamp
        int idx = version.lastIndexOf('-');
        if (idx < 0)
            return version;
        if (!version.substring(idx + 1).matches("\\d{8}\\.\\d{6}"))
            return version;
        return version.substring(0, idx);
    }

    protected void finalize(Artifact module, Mappings mappings, List<Repo.PendingArtifact> artifacts) {
        var variants = new HashSet<Artifact>();
        for (var pending : artifacts) {
            if (pending == null)
                continue;

            // Basically, I want to support multiple variants of a Forge dep.
            // Simplest case would be different mapping channels.
            // I haven't added an opt-in for making artifacts that use mappings, so just assume any artifact with variants
            var artifact = pending.artifact();
            var isJar = "jar".equals(artifact.getExtension());
            var isSources = "sources".equals(artifact.getClassifier());

            if (isJar) {
                // No sources allowed in stub repos
                if (stubJars && isSources)
                    continue;

                // Only transform main artifacts - This should be the main artifact but that may be a breaking change
                if (!accessTransformer.isEmpty() && artifact.getClassifier() == null)
                    pending = pending.withTask(accessTransform(pending));

                // The main artifact
                if (artifact.equals(module)) {
                    if (!facadeConfigs.isEmpty())
                        pending = pending.withTask(facade(pending));
                }

                if (stubJars)
                    pending = pending.withTask(stub(pending));
            }

            String suffix = null;
            if (!disableGradle && pending.variants() != null && !mappings.isPrimary()) {
                // If we are not the primary mapping, but we haven't generated the primary mapping yet, do so.
                // This will duplicate the files. But the other option is to require not writing the variants until the primary mapping is requested.
                var primaryTarget = new File(this.output, artifact.getLocalPath());
                if (!primaryTarget.exists())
                    updateFile(primaryTarget, pending.get(), pending.artifact());

                suffix = mappings.channel() + '-' + mappings.version();
                if (artifact.getClassifier() == null)
                    artifact = artifact.withClassifier(suffix);
                else
                    artifact = artifact.withClassifier(artifact.getClassifier() + '-' + suffix);
            }

            var target = new File(this.output, artifact.getLocalPath());
            updateFile(target, pending.get(), pending.artifact());

            var varTarget = new File(this.output, artifact.getLocalPath() + ".variants");
            if (!disableGradle && pending.variants() != null) {
                var source = pending.variants().execute();
                var cache = Util.cache(varTarget)
                    .add("source", source);

                if (!Mavenizer.checkCache(varTarget, cache)) {
                    variants.add(Artifact.from(artifact.getGroup(), artifact.getName(), artifact.getVersion()));
                    try {
                        GradleModule.Variant[] data = JsonData.fromJson(source, GradleModule.Variant[].class);
                        if (!dependenciesOnly) {
                            var file = new GradleModule.Variant.File(target);
                            for (var variant : data) {
                                variant.file(file);
                                if (suffix != null && !(pending.auxiliary() && this.globalAuxiliaryVariants))
                                    variant.name = variant.name + '-' + suffix;
                            }
                        }
                        // Sort them to make it predictable/easy to diff
                        Arrays.sort(data, (a, b) -> a.name.compareTo(b.name));
                        JsonData.toJson(data, varTarget);
                        cache.save();
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to write artifact variants: %s".formatted(artifact), t);
                    }
                }
            }
        }

        for (var artifact : variants) {
            updateVariants(artifact);
        }
    }

    private void updateFile(File target, File source, Artifact artifact) {
        var cache = Util.cache(target)
            .add("source", source);

        var isPom = "pom".equals(artifact.getExtension());
        boolean write;
        if (isPom) {
            cache.addKnown("disableGradle", Boolean.toString(disableGradle));
            // Write the pom for non-primary mappings if we haven't generated primary mappings yet
            write = !target.exists() || ((disableGradle || mappings.isPrimary()) && !cache.isSame());
        } else {
            write = !target.exists() || !cache.isSame();
        }
        if (Mavenizer.ignoreCache())
            write = true;

        if (write) {
            try {
                if (disableGradle && isPom) {
                    makeNonGradlePom(source, target);
                } else {
                    org.apache.commons.io.FileUtils.copyFile(source, target);
                }
                HashUtils.updateHash(target);
                cache.save();
            } catch (Throwable t) {
                throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
            }
        }
    }

    private Task stub(PendingArtifact pending) {
        var input = pending.task();
        return Task.named("stub", Task.deps(input), () -> stub(input, pending.artifact()));
    }

    private File stub(Task inputTask, Artifact artifact) {
        var tool = this.cache.maven().download(Constants.STUBIFY);
        var target = new File(this.cache.localCache(), artifact.appendClassifier("stub").getLocalPath());
        var input = inputTask.execute();

        var cache = Util.cache(target)
            .add("tool", tool)
            .add("input", input);

        if (Mavenizer.checkCache(target, cache))
            return target;

        var args = List.of("--input", input.getAbsolutePath(), "--output", target.getAbsolutePath());
        execute("stubify", Constants.STUBIFY_JAVA_VERSION, tool, args, target);

        cache.save();
        return target;
    }

    private Task accessTransform(PendingArtifact pending) {
        var input = pending.task();
        return Task.named("access-transform", Task.deps(input), () -> accessTransform(input, pending.artifact()));
    }

    private File accessTransform(Task inputTask, Artifact artifact) {
        var tool = this.cache.maven().download(Constants.ACCESS_TRANSFORMER);
        var target = new File(this.cache.localCache(), artifact.appendClassifier("ated").getLocalPath());
        var input = inputTask.execute();

        var cache = Util.cache(target)
            .add("tool", tool)
            .add(accessTransformer)
            .add("input", input);

        if (Mavenizer.checkCache(target, cache))
            return target;

        var args = new ArrayList<>(List.of(
            "--inJar", input.getAbsolutePath(),
            "--outJar", target.getAbsolutePath()
        ));

        for (var file : accessTransformer) {
            if (!file.exists())
                throw new IllegalStateException("Access Transformer config does not exist: " + file.getAbsolutePath());
            args.add("--atfile");
            args.add(file.getAbsolutePath());
        }

        execute("Access Transform", Constants.ACCESS_TRANSFORMER_JAVA_VERSION, tool, args, target);
        cache.save();
        return target;
    }

    private Task facade(PendingArtifact pending) {
        var input = pending.task();
        return Task.named("facade", Task.deps(input), () -> facade(input, pending.artifact()));
    }

    private File facade(Task inputTask, Artifact artifact) {
        var tool = this.cache.maven().download(Constants.FACADE);
        var target = new File(this.cache.localCache(), artifact.appendClassifier("facade").getLocalPath());
        var input = inputTask.execute();

        var cache = Util.cache(target)
            .add("tool", tool)
            .add(facadeConfigs)
            .add("input", input);

        if (Mavenizer.checkCache(target, cache))
            return target;

        var args = new ArrayList<>(List.of(
            "--input", input.getAbsolutePath(),
            "--output", target.getAbsolutePath()
        ));

        for (var file : facadeConfigs) {
            if (!file.exists())
                throw new IllegalStateException("Facade config does not exist: " + file.getAbsolutePath());
            args.add("--config");
            args.add(file.getAbsolutePath());
        }

        execute("facade ", Constants.FACAD_JAVA_VERSION, tool, args, target);
        cache.save();
        return target;
    }

    private void execute(String name, int javaVersion, File tool, List<String> args, File target) {
        File jdk;
        try {
            jdk = this.cache.jdks().get(javaVersion);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + javaVersion, e);
        }

        // Older versions of AT have a bug where it wont create the directories as needed.
        var parent = target.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();


        var log = new File(target.getAbsolutePath() + ".log");
        var ret = ProcessUtils.runJar(jdk, parent, log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to " + name + " file (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

    }

    private void updateVariants(Artifact artifact) {
        var root = new File(this.output, artifact.getFolder());
        var inputs = new ArrayList<File>();
        for (var file : root.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".variants")) {
                inputs.add(file);
            }
        }

        var target = new File(this.output, artifact.withExtension("module").getLocalPath());
        var cache = Util.cache(target);
        for (var input : inputs) {
            cache.add(input);
        }

        if (Mavenizer.checkCache(target, cache))
            return;

        var module = GradleModule.of(artifact);
        for (var input : inputs) {
            try {
                var data = JsonData.fromJson(input, GradleModule.Variant[].class);
                for (var variant : data)
                    module.variant(variant);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to read artifact variants: %s".formatted(input), t);
            }
        }

        try {
            JsonData.toJson(module, target);
            HashUtils.updateHash(target);
            cache.save();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to write artifact module: %s".formatted(artifact), t);
        }
    }

    private void makeNonGradlePom(File source, File target) throws IOException, SAXException, ParserConfigurationException, TransformerException {
        var docFactory = DocumentBuilderFactory.newInstance();
        var docBuilder = docFactory.newDocumentBuilder();
        var doc = docBuilder.parse(source);

        var modified = false;
        var projects = doc.getElementsByTagName("project");
        for (var x = 0; x < projects.getLength(); x++) {
            var project = projects.item(x);
            var children = project.getChildNodes();
            for (int y = 0; y < children.getLength(); y++) {
                var child = children.item(y);
                if (child.getNodeType() == Node.COMMENT_NODE) {
                    var content = child.getTextContent();
                    if (content.equals(POMBuilder.GRADLE_MAGIC_COMMENT)) {
                        project.removeChild(child);
                        y--;
                        modified = true;
                    }
                }
            }
        }

        if (!modified) {
            org.apache.commons.io.FileUtils.copyFile(source, target);
            return;
        }

        var transformerFactory = TransformerFactory.newInstance();
        var transformer = transformerFactory.newTransformer(new StreamSource(new StringReader(
            // This needs to strip spacing so our output doesn't have extra whitespace
            """
            <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                <xsl:strip-space elements="*"/>
                <xsl:output method="xml" encoding="UTF-8"/>

                <xsl:template match="@*|node()">
                    <xsl:copy>
                        <xsl:apply-templates select="@*|node()"/>
                    </xsl:copy>
                </xsl:template>
            </xsl:stylesheet>
            """
        )));

        transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //Make it pretty
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        var output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(output));
        var data = output.toString().getBytes(StandardCharsets.UTF_8);

        FileUtils.ensureParent(target);
        try (var os = new FileOutputStream(target)) {
            os.write(data);
        }
    }
}
