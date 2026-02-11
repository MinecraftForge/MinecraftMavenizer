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
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
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
    @Nullable File outputJsonFile
) {
    // Only 1.14.4+ has official mappings, we can support more when we add more mappings
    private static final MinecraftVersion MIN_OFFICIAL_MAPPINGS = MinecraftVersion.from("1.14.4");
    private static final ComparableVersion MIN_SUPPORTED_FORGE = new ComparableVersion("1.14.4");

    public MinecraftMaven(File output, boolean dependenciesOnly, File cacheRoot, File jdkCacheRoot, Mappings mappings,
        Map<String, String> foreignRepositories, boolean globalAuxiliaryVariants, boolean disableGradle, boolean stubJars,
        List<File> accessTransformer, @Nullable File outputJsonFile) {
        this(output, dependenciesOnly, new Cache(cacheRoot, jdkCacheRoot, foreignRepositories), mappings, foreignRepositories, globalAuxiliaryVariants, disableGradle, stubJars, new HashSet<>(), accessTransformer, outputJsonFile);
    }

    public MinecraftMaven {
        LOGGER.info("  Output:             " + output.getAbsolutePath());
        if (outputJsonFile != null)
            LOGGER.info("  Output JSON:        " + outputJsonFile.getAbsolutePath());
        LOGGER.info("  Dependencies Only:  " + dependenciesOnly);
        LOGGER.info("  Cache:              " + cache.root().getAbsolutePath());
        LOGGER.info("  JDK Cache:          " + cache.jdks().root().getAbsolutePath());
        LOGGER.info("  Offline:            " + Mavenizer.isOffline());
        LOGGER.info("  Cache Only:         " + Mavenizer.isCacheOnly());
        LOGGER.info("  Mappings:           " + mappings);
        if (!foreignRepositories.isEmpty())
            LOGGER.info("  Foreign Repos:      [" + String.join(", ", foreignRepositories.values()) + ']');
        LOGGER.info("  GradleVariantHack:  " + globalAuxiliaryVariants);
        LOGGER.info("  Disable Gradle:     " + disableGradle);
        LOGGER.info("  Stub Jars:          " + stubJars);
        if (!accessTransformer.isEmpty()) {
            LOGGER.getInfo().print("  Access Transformer: ");
            var itor = accessTransformer.iterator();
            while (itor.hasNext()) {
                var file = itor.next();
                LOGGER.getInfo().print(file.getAbsolutePath());
                if (itor.hasNext())
                    LOGGER.getInfo().print(", ");
            }
            LOGGER.getInfo().println();
        }
        LOGGER.info();
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
                else if (mappings.channel().equals("official") && hasOfficialMappings(mcprepo, ver.id))
                    artifacts = mcprepo.processWithoutMcp(versioned, mappings.withMCVersion(ver.id), outputJson);
                else {
                    LOGGER.info("Skipping " + versioned + " no mcp config");
                    continue;
                }
                finalize(artifact, mappings, artifacts);
            }
        } else {
            var mcVersion = mcpToMcVersion(version);
            var mappings = this.mappings.withMCVersion(mcVersion);

            List<PendingArtifact> artifacts = null;
            if (hasMcp(mcprepo, version))
                artifacts = mcprepo.process(artifact, mappings, outputJson);
            else if (mappings.channel().equals("official") && hasOfficialMappings(mcprepo, mcVersion))
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

        return json.getDownload(MinecraftTasks.Files.CLIENT_MAPPINGS.key) != null ||
            json.getDownload(MinecraftTasks.Files.SERVER_MAPPINGS.key) != null;
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

            if (stubJars && "jar".equals(artifact.getExtension())) {
                // No sources allowed in stub repos
                if ("sources".equals(artifact.getClassifier()))
                    continue;
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
                var cache = HashStore.fromFile(varTarget)
                    .add("source", source);

                if (!varTarget.exists() || !cache.isSame()) {
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
        var isJar = "jar".equals(artifact.getExtension());
        if (isJar) {
            if (stubJars) {
                writeStub(target, source, artifact);
                return;
            }

            // Only transform main artifacts
            if (!accessTransformer.isEmpty() && artifact.getClassifier() == null) {
                writeAccessTransformed(target, source, artifact);
                return;
            }
        }

        var cache = HashStore.fromFile(target)
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

    private void writeStub(File target, File source, Artifact artifact) {
        var tool = this.cache.maven().download(Constants.STUBIFY);
        var cache = HashStore.fromFile(target)
            .add("tool", tool)
            .add("source", source);

        if (target.exists() && cache.isSame())
            return;

        File jdk;
        try {
            jdk = this.cache.jdks().get(Constants.STUBIFY_JAVA_VERSION);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + Constants.STUBIFY_JAVA_VERSION, e);
        }

        var log = new File(source.getAbsolutePath() + ".stubify.log");
        var ret = ProcessUtils.runJar(jdk, source.getParentFile(), log, tool, Collections.emptyList(),
            List.of("--input", source.getAbsolutePath(), "--output", target.getAbsolutePath())
        );
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to stubify jar file (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        try {
            cache.save();
            HashUtils.updateHash(target);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
        }
    }

    private void writeAccessTransformed(File target, File source, Artifact artifact) {
        var tool = this.cache.maven().download(Constants.ACCESS_TRANSFORMER);
        var cache = HashStore.fromFile(target)
            .add("tool", tool)
            .add(accessTransformer)
            .add("source", source);

        if (target.exists() && cache.isSame())
            return;

        File jdk;
        try {
            jdk = this.cache.jdks().get(Constants.ACCESS_TRANSFORMER_JAVA_VERSION);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find JDK for version " + Constants.ACCESS_TRANSFORMER_JAVA_VERSION, e);
        }

        var log = new File(source.getAbsolutePath() + ".accesstransformer.log");
        var args = new ArrayList<>(List.of(
            "--inJar", source.getAbsolutePath(),
            "--outJar", target.getAbsolutePath()
        ));
        for (var file : accessTransformer) {
            args.add("--atfile");
            args.add(file.getAbsolutePath());
        }

        // Older versions of AT have a bug where it wont create the directories as needed.
        var parent = target.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();

        var ret = ProcessUtils.runJar(jdk, source.getParentFile(), log, tool, Collections.emptyList(), args);
        if (ret.exitCode != 0)
            throw new IllegalStateException("Failed to Access Transform jar file (exit code " + ret.exitCode + "), See log: " + log.getAbsolutePath());

        try {
            cache.save();
            HashUtils.updateHash(target);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate artifact: %s".formatted(artifact), t);
        }
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
        var cache = HashStore.fromFile(target);
        for (var input : inputs) {
            cache.add(input);
        }

        if (target.exists() && cache.isSame())
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
