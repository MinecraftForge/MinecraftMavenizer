/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.mcmaven.util.Artifact;

// TODO: [MCMaven][Documentation] Document from MinecraftForge/MCPConfig
public class PatcherConfig extends Config {
    public String mcp;    // Do not specify this unless there is no parent.
    public String parent; // To fully resolve, we must walk the parents until we hit null, and that one must specify a MCP value.
    public List<String> ats;
    public List<String> sass;
    public List<String> srgs;
    public List<String> srg_lines;
    public String binpatches; //To be applied to joined.jar, remapped, and added to the classpath
    public Function binpatcher;
    public String patches;
    public String sources;
    public String universal; //Injected into the final jar, TODO: [MCMaven][PatcherConfig] Make Universal Jar separate from main jar
    public List<String> libraries; //Additional libraries.
    public String inject;
    public Map<String, Object/*RunConfig*/> runs;
    public String sourceCompatibility; // Default to 1.8
    public String targetCompatibility; // Default to 1.8

    public Artifact getParent() {
        return Artifact.from(hasParent() ? this.parent : this.mcp);
    }

    public boolean hasParent() {
        return this.parent != null;
    }

    public List<String> getAts() {
        return this.ats == null ? Collections.emptyList() : this.ats;
    }
    public List<String> getSASs() {
        return this.sass == null ? Collections.emptyList() : this.sass;
    }

    public List<String> getMappings() {
        return this.srgs == null ? Collections.emptyList() : this.srgs;
    }

    public List<String> getMappingLines() {
        return this.srg_lines == null ? Collections.emptyList() : this.srg_lines;
    }

    public List<String> libraries() {
        return libraries == null ? List.of() : libraries;
    }

    public static class Function {
        public String version; //Maven artifact for the jar to run
        public String repo; //Maven repo to download the jar from
        public List<String> args;
        public List<String> jvmargs;
        public Integer java_version;

        public int getJavaVersion(MCPConfig.V2 mcp) {
            return java_version != null ? java_version : mcp.java_target;
        }

        public List<String> getArgs() {
            return this.args == null ? Collections.emptyList() : this.args;
        }

        public List<String> getJvmArgs() {
            return this.jvmargs == null ? Collections.emptyList() : this.jvmargs;
        }
    }

    public static class V2 extends PatcherConfig {
        public DataFunction processor;
        public String patchesOriginalPrefix;
        public String patchesModifiedPrefix;
        public Boolean notchObf; //This is a Boolean so we can set to null and it won't be printed in the json.
        public List<String> universalFilters;
        public List<String> modules; // Modules passed to --module-path
        public String sourceFileCharset; // = StandardCharsets.UTF_8.name();

        public V2(PatcherConfig o) {
            this.mcp = o.mcp;
            this.parent = o.parent;
            this.ats = o.ats;
            this.sass = o.sass;
            this.srgs = o.srgs;
            this.srg_lines = o.srg_lines;
            this.binpatches = o.binpatches;
            this.binpatcher = o.binpatcher;
            this.patches = o.patches;
            this.sources = o.sources;
            this.universal = o.universal;
            this.libraries = o.libraries;
            this.inject = o.inject;
            this.runs = o.runs;
            this.sourceCompatibility = o.sourceCompatibility;
            this.targetCompatibility = o.targetCompatibility;
        }

        public static class DataFunction extends Function {
            public Map<String, String> data;
        }

        public boolean notchObf() {
            return this.notchObf != null && this.notchObf;
        }
    }
}
