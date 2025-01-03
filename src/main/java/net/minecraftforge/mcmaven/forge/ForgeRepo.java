/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.forge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.mcmaven.cache.Cache;
import net.minecraftforge.mcmaven.data.GradleModule;
import net.minecraftforge.mcmaven.data.JsonData;
import net.minecraftforge.mcmaven.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.util.Artifact;
import net.minecraftforge.mcmaven.util.ComparableVersion;
import net.minecraftforge.mcmaven.util.Constants;
import net.minecraftforge.mcmaven.util.HashFunction;
import net.minecraftforge.mcmaven.util.Log;
import net.minecraftforge.mcmaven.util.OS;
import net.minecraftforge.mcmaven.util.POMBuilder;
import net.minecraftforge.mcmaven.util.Task;
import net.minecraftforge.mcmaven.util.Util;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

// TODO: [MCMaven][ForgeRepo] For now, the ForgeRepo needs to be fully complete with everything it has to do.
// later, we can worry about refactoring it so that other repositories such as MCP (clean) and FMLOnly can function.
// And yes, I DO want this tool to support as far back as possible. But for now, we worry about UserDev3 and up.
// - Jonathing
/** Represents the Forge repository. */
public class ForgeRepo {
    // TODO: [MCMaven][FGVersion] Handle this as an edge-case in FGVersion
    private static final ComparableVersion USERDEV3_START = new ComparableVersion("1.12.2-14.23.5.2851");
    private static final ComparableVersion USERDEV3_END   = new ComparableVersion("1.12.2-14.23.5.2860");

    // CACHE
    @Nullable File build;
    @Nullable File globalBuild;
    final Cache cache;

    // ARTIFACTS
    private File sources;
    private File recompiled;
    private File pom;
    private File gradleModule;

    // TODO TEMP ARTIFACTS
    private File clientExtra;
    private File clientExtraPom;
    private File clientExtraGradleModule;

    // OUTPUT
    final File output;

    // MCP
    final MCPConfigRepo mcpconfig;

    /**
     * Creates a new Forge repository.
     *
     * @param cache  The cache directory
     * @param output The output directory.
     */
    public ForgeRepo(Cache cache, File output) {
        this.cache = cache;
        this.output = output;
        this.mcpconfig = new MCPConfigRepo(cache, output);
    }

    private static void log(String msg) {
        Log.log(msg);
    }

    // TODO: [MCMaven][ForgeRepo] Please clean this up
    public void process(String version) {
        var fg = FGVersion.fromForge(version);
        log("Processing Forge:");
        try {
            Log.push();
            log("Version: " + version);

            if (fg == null) {
                log("Python version unsupported!");
                return;
            }

            if (fg.ordinal() < FGVersion.v3.ordinal()) {
                log("Only FG 3+ supported");
            } else {
                processV3(version, "official", null);
            }
        } finally {
            Log.pop();
        }
    }

    @SuppressWarnings("unused")
    private static String forgeToMcVersion(String version) {
        // Save for a few april-fools versions, Minecraft doesn't use _ in their version names.
        // So when Forge needs to reference a version of Minecraft that uses - in the name, it replaces
        // it with _
        // This could cause issues if we ever support a version with _ in it, but fuck it I don't care right now.
        int idx = version.indexOf('-');
        return version.substring(0, idx).replace('_', '-');
    }

    private void processV3(String forge, String channel, @Nullable String mapping) {
        var userdev = this.getUserdev(forge);
        this.setBuildFolders(userdev.getFolder());

        var patcher = new Patcher(this, userdev);
        var renamer = new Renamer(this, userdev, patcher);
        var recompiler = new Recompiler(this, userdev, patcher, renamer);

        this.sources = this.execute("Generating Sources", renamer.getNamedSources());
        this.recompiled = this.execute("Recompiling Sources", recompiler.getRecompiledJar());

        var minecraft = patcher.getMCP().getName().getVersion();

        this.pom = new File(this.build, "forge.pom");
        forgePom(forge, minecraft, patcher, this.pom);
        this.gradleModule = new File(this.build, "forge.module");
        forgeGradleModule(forge, minecraft, patcher, this.gradleModule);

        // TODO [MCMaven][ForgeRepo] I don't know where to put these, so the will stay here for now
        // I'm sure in the future we can find a decent way to clean this up.
        // Can't really put them in MCPConfigRepo right now since it doesn't have access to an MCP instance like Patcher
        // - Jonathing
        this.clientExtra = this.execute("Getting Client Extra", patcher.getMCP().getSide("joined").getTasks().getClientExtra());
        this.clientExtraPom = new File(this.build, "clientExtra.pom");
        clientExtraPom(minecraft, patcher, clientExtraPom);
        this.clientExtraGradleModule = new File(this.build, "clientExtra.module");
        clientExtraGradleModule(minecraft, patcher, clientExtraGradleModule);

        this.generateOutput(forge, minecraft);
    }

    private List<Artifact> generateOutput(String forge, String minecraft) {
        Util.ensureParent(this.output);

        var artifacts = new ArrayList<Artifact>();
        this.outputArtifact(artifacts, this.sources, Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, "sources"));
        this.outputArtifact(artifacts, this.recompiled, Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge));
        this.outputArtifact(artifacts, this.pom, Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, null, "pom"));
        this.outputArtifact(artifacts, this.gradleModule, Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, null, "module"));

        this.outputArtifact(artifacts, this.clientExtra, Artifact.from("net.minecraft", "client", minecraft, "extra"));
        this.outputArtifact(artifacts, this.clientExtraPom, Artifact.from("net.minecraft", "client", minecraft, null, "pom"));
        this.outputArtifact(artifacts, this.clientExtraGradleModule, Artifact.from("net.minecraft", "client", minecraft, null, "module"));

        return artifacts;
    }

    private void outputArtifact(List<Artifact> list, File file, Artifact artifact) {
        list.add(artifact);

        try {
            var output = new File(this.output, artifact.getLocalPath());
            FileUtils.copyFile(file, output);
            Util.updateHash(output);
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    private void forgePom(String forge, String minecraft, Patcher patcher, File output) {
        var builder = new POMBuilder("net.minecraftforge", "forge", forge);

        builder.dependencies().add("net.minecraft", "client", minecraft, "extra", null, "compile");

        for (var a : patcher.getArtifacts()) {
            builder.dependencies().add(a, "compile");
        }

        Util.ensureParent(output);
        try (var os = new FileOutputStream(output)) {
            os.write(builder.build(true).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | ParserConfigurationException | TransformerException e) {
            Util.sneak(e);
        }
    }

    // TODO CLEANUP
    // TODO [MCMaven][ForgeRepo] store partial variants as files in a "variants" folder, then merge them into the module
    private void forgeGradleModule(String forge, String minecraft, Patcher patcher, File output) {
        @Deprecated final var mcVersionWithoutMCP = forgeToMcVersion(forge);
        @Deprecated final var officialVersion = "official-" + mcVersionWithoutMCP;

        // set the mappings version. unused for now.
        // if the current mappings = the official mappings, set to null so we don't duplicate the variants
        final var mappingsVersion = Util.make("official-" + mcVersionWithoutMCP, s -> {
            if (officialVersion.equals(s)) {
                return null;
            }

            return s;
        });

        var module = GradleModule.of("net.minecraftforge", "forge", forge);

        // TODO move this variant creation to it's own method. it is easily reproducible
        // official
        var officialClasses = Util.make(new GradleModule.Variant(), variant -> {
            variant.name = "classes-" + officialVersion;
            variant.attributes = Map.of(
                "org.gradle.usage", "java-runtime",
                "org.gradle.category", "library",
                "org.gradle.dependency.bundling", "external",
                "org.gradle.libraryelements", "jar",
                "net.minecraftforge.mappings.channel", "official",
                "net.minecraftforge.mappings.version", mcVersionWithoutMCP
            );
            variant.files = Util.make(new ArrayList<>(), files -> {
                files.add(Util.make(new GradleModule.Variant.File(), f -> {
                    f.name = f.url = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge).getFilename();
                    f.size = this.recompiled.length();
                    f.sha1 = HashFunction.SHA1.sneakyHash(this.recompiled);
                    f.sha256 = HashFunction.SHA256.sneakyHash(this.recompiled);
                    f.sha512 = HashFunction.SHA512.sneakyHash(this.recompiled);
                    f.md5 = HashFunction.MD5.sneakyHash(this.recompiled);
                }));
            });
            variant.dependencies = Util.make(new ArrayList<>(), dependencies -> {
                dependencies.add(GradleModule.Variant.Dependency.of(Artifact.from("net.minecraft", "client", minecraft, "extra")));
                for (var a : patcher.getArtifacts()) {
                    dependencies.add(GradleModule.Variant.Dependency.of(a));
                }
            });
        });
        var officialSources = Util.make(new GradleModule.Variant(), variant -> {
            variant.name = "sources-" + officialVersion;
            variant.attributes = Map.of(
                "org.gradle.usage", "java-runtime",
                "org.gradle.category", "documentation",
                "org.gradle.dependency.bundling", "external",
                "org.gradle.docstype", "sources",
                "org.gradle.libraryelements", "jar",
                "net.minecraftforge.mappings.channel", "official",
                "net.minecraftforge.mappings.version", mcVersionWithoutMCP
            );
            variant.files = Util.make(new ArrayList<>(), files -> {
                files.add(Util.make(new GradleModule.Variant.File(), f -> {
                    f.name = f.url = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, "sources").getFilename();
                    f.size = this.sources.length();
                    f.sha1 = HashFunction.SHA1.sneakyHash(this.sources);
                    f.sha256 = HashFunction.SHA256.sneakyHash(this.sources);
                    f.sha512 = HashFunction.SHA512.sneakyHash(this.sources);
                    f.md5 = HashFunction.MD5.sneakyHash(this.sources);
                }));
            });
        });
        module.variant(officialClasses);
        module.variant(officialSources);

        // TODO [MCMaven][ForgeRepo] This is a mess. Clean it up.
        // ALSO TODO add parchment mappings and other mappings support

        Util.ensureParent(output);
        try {
            JsonData.toJson(module, output);
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    private void clientExtraPom(String minecraft, Patcher patcher, File output) {
        var builder = new POMBuilder("net.minecraft", "client", minecraft);

        var side = patcher.getMCP().getSide("joined");

        side.getMCLibraries().forEach(a -> {
            // we handle these exclusively in the module metadata instead
            if (a instanceof Artifact.WithOS) return;

            builder.dependencies().add(a, "compile");
        });
        side.getMCPConfigLibraries().forEach(a -> {
            builder.dependencies().add(a, "compile");
        });

        Util.ensureParent(output);
        try (var os = new FileOutputStream(output)) {
            os.write(builder.build(true).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | ParserConfigurationException | TransformerException e) {
            Util.sneak(e);
        }
    }

    private void clientExtraGradleModule(String minecraft, Patcher patcher, File output) {
        var module = GradleModule.of("net.minecraft", "client", minecraft);
        var files = Util.make(new ArrayList<GradleModule.Variant.File>(), list -> {
            list.add(Util.make(new GradleModule.Variant.File(), f -> {
                f.name = f.url = Artifact.from("net.minecraft", "client", minecraft, "extra").getFilename();
                f.size = this.clientExtra.length();
                f.sha1 = HashFunction.SHA1.sneakyHash(this.clientExtra);
                f.sha256 = HashFunction.SHA256.sneakyHash(this.clientExtra);
                f.sha512 = HashFunction.SHA512.sneakyHash(this.clientExtra);
                f.md5 = HashFunction.MD5.sneakyHash(this.clientExtra);
            }));
        });

        var windows = module.variant(Util.make(GradleModule.Variant.ofNative(OS.WINDOWS), variant -> {
            variant.files = files;
        }));
        var macOS = module.variant(Util.make(GradleModule.Variant.ofNative(OS.MACOS), variant -> {
            variant.files = files;
        }));
        var linux = module.variant(Util.make(GradleModule.Variant.ofNative(OS.LINUX), variant -> {
            variant.files = files;
        }));
        var all = new GradleModule.Variant[] { windows, macOS, linux };

        var side = patcher.getMCP().getSide("joined");
        side.getMCLibraries().forEach(artifact -> {
            var selected = artifact instanceof Artifact.WithOS withOS ? switch (withOS.os) {
                case WINDOWS -> windows;
                case MACOS -> macOS;
                case LINUX -> linux;
                default -> throw new IllegalStateException();
            } : null;
            var dependency = GradleModule.Variant.Dependency.of(artifact);

            if (selected != null) {
                selected.dependencies.add(dependency);
            } else {
                for (var variant : all) {
                    variant.dependencies.add(dependency);
                }
            }
        });

        Util.ensureParent(output);
        try {
            JsonData.toJson(module, output);
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    private void setBuildFolders(String version) {
        this.build = new File(this.cache.root, "forge/" + version);
        this.globalBuild = new File(this.build.getParentFile(), ".global");
    }

    private Artifact getUserdev(String forge) {
        var forgever = new ComparableVersion(forge);
        // userdev3, old attempt to make 1.12.2 FG3 compatible
        var userdev3 = forgever.compareTo(USERDEV3_START) >= 0 && forgever.compareTo(USERDEV3_END) <= 0;
        return Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, userdev3 ? "userdev3" : "userdev", "jar");
    }

    private File execute(String message, Task task) {
        try {
            Log.log(message);
            Log.push();
            return task.execute();
        } finally {
            Log.pop();
        }
    }
}
