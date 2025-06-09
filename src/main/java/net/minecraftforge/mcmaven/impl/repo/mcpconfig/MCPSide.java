/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class MCPSide {
    public static final String CLIENT = "client";
    public static final String SERVER = "server";
    public static final String JOINED = "joined";

    private final MCP mcp;
    private final String side;
    private final File build;
    private final MCPTaskFactory factory;

    MCPSide(MCP owner, String side) {
        this.mcp = owner;
        this.side = side;
        this.build = new File(this.mcp.getBuildFolder(), this.side);
        this.factory = new MCPTaskFactory(this, this.build);
    }

    public boolean containsClient() {
        return this.side.equals(JOINED) || this.side.equals(CLIENT);
    }

    public boolean containsServer() {
        return this.side.equals(JOINED) || this.side.equals(SERVER);
    }

    public MCPTaskFactory getTasks() {
        return this.factory;
    }

    public String getName() {
        return this.side;
    }

    public MCP getMCP() {
        return this.mcp;
    }

    public File getBuildFolder() {
        return this.build;
    }

    public void forAllLibraries(Consumer<Artifact> consumer) {
        this.forAllLibraries(consumer, null);
    }

    public void forAllLibraries(Consumer<Artifact> consumer, Predicate<Artifact> filter) {
        this.forAllLibrariesInternal(consumer, filter, this.getMCLibraries());
        this.forAllLibrariesInternal(consumer, filter, this.getMCPConfigLibraries());
    }

    // to avoid duplicate code
    private void forAllLibrariesInternal(Consumer<? super Artifact> consumer, @Nullable Predicate<? super Artifact> filter, Iterable<? extends Artifact> libraries) {
        for (var library : libraries) {
            if (filter == null || filter.test(library))
                consumer.accept(library);
        }
    }

    public List<Artifact> getMCLibraries() {
        var artifacts = new ArrayList<Artifact>();

        // minecraft version.json libs + mcpconfig libs + userdev libs
        // also for module metadata (same order)
        for (var lib : this.getTasks().getLibraries()) {
            artifacts.add(lib.name());
        }

        return artifacts;
    }

    public List<Artifact> getMCPConfigLibraries() {
        var artifacts = new ArrayList<Artifact>();

        for (var lib : this.mcp.getConfig().getLibraries(this.side)) {
            var artifact = Artifact.from(lib);
            artifacts.add(artifact);
        }

        return artifacts;
    }

    public Task getSources() {
        return this.getTasks().getLastTask();
    }

    public List<File> getClasspath() {
        var classpath = new ArrayList<File>();

        // minecraft version.json libs + mcpconfig libs + userdev libs
        // also for module metadata (same order)
        for (var lib : this.factory.getLibraries()) {
            classpath.add(lib.file());
        }

        for (var lib : this.mcp.getConfig().getLibraries(this.side)) {
            classpath.add(this.mcp.getCache().maven().download(Artifact.from(lib)));
        }

        return classpath;
    }

    // TODO delete this after we've implemented the alternative
    /*
    List<RepositoryArtifact> buildArtifacts(Dependency dep, boolean sources) {
        var metadata = metadata(dep);
        SingleFileOutput bin = null;
        SingleFileOutput src = null;
        if (Util.isSourceDisabled()) {
            /*
             * Sources are disabled so take the pre-decomp jar
             * Make intermediate -> named mapping file
             * Run through Jar Renamer
             *
             * TODO: [FG][MCP] Compile injected code in a seperate smaller jar then merge
             * /
            var namedJar = renameJar(predecomp(), mappings().srg2names());
            bin = mergeExtra("mergeExtraSourceDisabled", namedJar, mappings().obf2srg());
        } else {
            /*
             * Sources are enabled, to take the output of the last step
             * Load Intermediate -> Human names, and javadocs
             * Inject into last output
             * Compile new jar
             * Merge any assets/data from the original minecraft jar.
             * /
            src = renameSources(last(), mappings().names());
            var compile = compile(src);
            var packJar = packageCompiled(compile);
            bin = mergeExtra("mergeExtra", packJar, mappings().obf2srg());
        }

        return List.of(new RepositoryArtifact(dep, metadata, bin, src));
    }

    private SingleFileOutput metadata(Dependency dep) {
        var task = task("metadata", CreateIvyMetadata.class);
        task.getOrganization().convention(dep.getGroup());
        task.getArtifact().convention(dep.getName());
        task.getVersion().convention(dep.getVersion());
        task.getLauncherMeta().convention(mcp.fg.getMcTasks().getVersionJson(mcp.config.version).getOutput());
        mcp.config.getLibraries(side).forEach(task.getAdditionalDeps()::add);
        task.getOutput().convention(local.file("ivy.xml"));
        return task;
    }

    private SingleFileOutput renameJar(SingleFileOutput jar, SingleFileOutput mappings) {
        var task = task("srg2named", RenameJarFile.class);
        task.getInput().convention(jar.getOutput());
        task.getMapping().convention(mappings.getOutput());
        task.getOutput().convention(local.file("named.jar"));
        return task;
    }

    private SingleFileOutput mergeExtra(String name, SingleFileOutput binary, SingleFileOutput mappings) {
        var base = "joined".equals(side) ? "client" : side;
        var baseTask = mcp.fg.getMcTasks().getVersionFile(mcp.config.version, base, "jar");

        var task = task(name, MergeExtra.class);
        task.getInput().convention(binary.getOutput());
        task.getBase().convention(baseTask.getOutput());
        task.getMappings().convention(mappings.getOutput());
        task.getOutput().convention(local.file(name + ".jar"));
        return task;
    }

    private SingleFileOutput renameSources(SingleFileOutput sources, SingleFileOutput names) {
        var task = task("renameSource", RenameSources.class);
        task.getInput().convention(sources.getOutput());
        task.names().convention(names.getOutput());
        task.getJavadocs().convention(true);
        task.getEncoding().convention(mcp.config.encoding);
        task.getOutput().convention(local.file("renamed-sources.jar"));
        return task;
    }

    private JavaCompile compile(SingleFileOutput sources) {
        var task = task("recompile", JavaCompile.class);
        var config = task("recompileConfig", DefaultTask.class);

        var jsonTask = mcp.fg.getMcTasks().getVersionJson(mcp.config.version);
        config.dependsOn(jsonTask);
        config.doFirst(t -> {
            var deps = new ArrayList<Dependency>();
            for (var lib : mcp.config.getLibraries(side))
                deps.add(project.getDependencies().create(lib));
            var json = JsonData.minecraftVersion(jsonTask.getOutputFile());
            for (var lib : json.libraries)
                deps.add(project.getDependencies().create(lib.name));
            var cfg = project.getConfigurations().detachedConfiguration(deps.toArray(Dependency[]::new));
            task.setClasspath(cfg);
        });

        var toolchainService = project.getExtensions().getByType(JavaToolchainService.class);
        var javaCompiler = toolchainService.compilerFor(cfg -> cfg.getLanguageVersion().set(JavaLanguageVersion.of(mcp.config.java_target)));
        task.getOptions().setWarnings(false);
        task.dependsOn(sources, config);
        task.getJavaCompiler().set(javaCompiler);
        task.setSource(project.zipTree(sources.getOutputFile()));
        task.getDestinationDirectory().set(local.dir("recompile-classes"));
        mcp.fg.getRepo().blacklist(task);
        return task;
    }

    private SingleFileOutput packageCompiled(JavaCompile compile) {
        var task = task("recompileJar", JarWithOutput.class);
        task.from(compile.getDestinationDirectory());
        task.getDestinationDirectory().convention(local.dir());
        task.getArchiveFileName().set("recompile.jar");
        return task;
    }

    private class MappingTasks implements MappingTaskHelper {
        private final MappingsExtension ext;
        private SingleFileOutput obf2srg;
        private SingleFileOutput srg2obf;
        private SingleFileOutput srg2srg;
        private Map<Key, SingleFileOutput> srg2names = new HashMap<>();
        private Map<Key, SingleFileOutput> names2srg = new HashMap<>();
        private Map<Key, SingleFileOutput> obf2names = new HashMap<>();
        private Map<Key, SingleFileOutput> names2obf = new HashMap<>();

        private record Key(String channel, String version) {
            private String clean() {
                return Util.sanitizeTaskName(Util.capitalize(channel) + version);
            }
        }

        private MappingTasks(SingleFileOutput base) {
            this.obf2srg = base;
            this.ext = mcp.fg.getMappings();
        }

        @Override
        public SingleFileOutput obf2srg() {
            return this.obf2srg;
        }

        private SingleFileOutput reverse(SingleFileOutput input, String name, String filename) {
            var task = task(name, ConvertMappingFile.class);
            task.getInput().set(input.getOutput());
            task.getFormat().set(IMappingFile.Format.TSRG);
            task.getReverse().set(true);
            task.getOutput().convention(local.file(filename));
            return task;
        }

        @Override
        public SingleFileOutput srg2obf() {
            if (this.srg2obf == null)
                this.srg2obf = reverse(obf2srg(), "makeSrg2Obf", "srg2obf.tsrg");
            return this.srg2obf;
        }

        private SingleFileOutput srg2srg() {
            if (this.srg2srg == null) {
                var task = task("makeSrg2Srg", ChainMappingFiles.class);
                task.getLeft().convention(obf2srg().getOutput());
                task.getReverseLeft().convention(true);
                task.getRight().convention(obf2srg().getOutput());
                task.getOutput().convention(local.file("srg2srg.tsrg"));
                this.srg2srg = task;
            }
            return this.srg2srg;
        }

        @Override
        public SingleFileOutput names() {
            return names(ext.getCannel(), ext.getVersion(mcp.config.version));
        }

        @Override
        public SingleFileOutput names(String channel, String version) {
            var ext = mcp.fg.getMappings();
            var provider = ext.getProvider(channel);
            if (provider == null)
                throw new IllegalStateException("Could not find mapping channel `" + channel + "`");
            return provider.getMappings(channel, version, obf2srg());
        }

        @Override
        public SingleFileOutput srg2names() {
            return srg2names(ext.getCannel(), ext.getVersion(mcp.config.version));
        }

        @Override
        public SingleFileOutput srg2names(String channel, String version) {
            return this.srg2names.computeIfAbsent(new Key(channel, version), key -> {
                var task = task("makeSrg2Names" + key.clean(), RenameMappingFile.class);
                task.getMappings().convention(srg2srg().getOutput());
                task.names().convention(names(channel, version).getOutput());
                task.getOutput().convention(local.file("srg2named" + key.clean() + ".tsrg"));
                return task;
            });
        }

        @Override
        public SingleFileOutput names2srg() {
            return names2srg(ext.getCannel(), ext.getVersion(mcp.config.version));
        }

        @Override
        public SingleFileOutput names2srg(String channel, String version) {
            return this.names2srg.computeIfAbsent(new Key(channel, version), key ->
                reverse(srg2names(channel, version), "makeNames2Srg" + key.clean(), "named2srg" + key.clean() + ".tsrg")
            );
        }

        @Override
        public SingleFileOutput obf2names() {
            return obf2names(ext.getCannel(), ext.getVersion(mcp.config.version));
        }

        @Override
        public SingleFileOutput obf2names(String channel, String version) {
            return this.obf2names.computeIfAbsent(new Key(channel, version), key -> {
                var task = task("makeObf2Names" + key.clean(), RenameMappingFile.class);
                task.getMappings().convention(obf2srg().getOutput());
                task.names().convention(names(channel, version).getOutput());
                task.getOutput().convention(local.file("obf2named" + key.clean() + ".tsrg"));
                return task;
            });
        }

        @Override
        public SingleFileOutput names2obf() {
            return names2obf(ext.getCannel(), ext.getVersion(mcp.config.version));
        }

        @Override
        public SingleFileOutput names2obf(String channel, String version) {
            return this.names2obf.computeIfAbsent(new Key(channel, version), key ->
                reverse(obf2names(channel, version), "makeNames2Obf" + key.clean(), "named2obf" + key.clean() + ".tsrg")
            );
        }
    }
    */
}