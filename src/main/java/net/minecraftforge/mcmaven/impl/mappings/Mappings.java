/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;

import net.minecraftforge.mcmaven.impl.repo.forge.FG2Userdev;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPLegacy;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import org.jetbrains.annotations.Nullable;

public abstract class Mappings {
    public static final String CHANNEL_ATTR = "net.minecraftforge.mappings.channel";
    public static final String VERSION_ATTR = "net.minecraftforge.mappings.version";

    private final String channel;
    private final @Nullable String version;

    public static Mappings of(String mappingsNotation) {
        var split = mappingsNotation.split(":", 2);
        var channel = split[0];
        var version = split.length > 1 ? split[1] : null;
        return of(channel, version);
    }

    public static Mappings of(String channel, @Nullable String version) {
        switch (channel) {
            case "parchment":
                return new ParchmentMappings(version);
            case "official":
                return new OfficialMappings(channel, version);
            case "snapshot":
            case "snapshot_nodoc":
            case "stable":
            case "stable_nodoc":
                return new MCPMappings(channel, version);
            case "srg":
                return new SRGMappings("srg", version);
        }
        throw new IllegalArgumentException("Unsupported Mappings: " + channel + (version == null ? "" : ':' + version));
    }

    public Mappings(String channel, @Nullable String version) {
        this.channel = channel;
        this.version = version;
    }

    public String channel() {
        return this.channel;
    }

    public @Nullable String version() {
        return this.version;
    }

    public boolean equals(ResolvedMappings other) {
        return equals(other.channel(), other.version());
    }
    public boolean equals(Mappings other) {
        return equals(other.channel(), other.version());
    }
    public boolean equals(String channel, @Nullable String version) {
        if (!this.channel().equals(channel))
            return false;
        if (this.version() == null)
            return version == null;
        return this.version().equals(version);
    }

    @Override
    public String toString() {
        return channel() + (version() == null ? "" : '-' + version());
    }

    public File getFolder(File root) {
        return new File(root, channel() + '/' + version());
    }

    public abstract Mappings withMCVersion(String version);

    public abstract ResolvedMappings withContext(MCPSide side);
    public abstract ResolvedMappings withContext(FG2Userdev fg2);
    public abstract ResolvedMappings withContext(MCPLegacy legacy);


    protected Artifact getArtifact(MCPSide side) {
        //net.minecraft:mappings_{CHANNEL}:{MCP_VERSION}[-{VERSION}]@zip
        var name = "mappings_" + this.channel;
        var mcpVersion = side.getMCP().getName().getVersion();
        var mcVersion = side.getMCP().getConfig().version;
        var artifactVersion = mcpVersion;
        if (this.version() != null && !mcVersion.equals(this.version()))
            artifactVersion += '-' + this.version();

        return Artifact.from(Constants.MC_GROUP, name, artifactVersion).withExtension("zip");
    }

    protected Artifact getArtifact(FG2Userdev fg2) {
        //net.minecraft:mappings_{CHANNEL}:{MC_VERSION}-{legacy|FORGE_VERSION}[-{VERSION}]@zip
        var name = "mappings_" + this.channel;
        // either the raw mc version, or MC_VERSION-PYTHON_VERSION;
        var version = fg2.getMCP().getName().getVersion();
        if (fg2.getExtraMappings() == null)
            version += "-legacy";
        else
            version += '-' + fg2.getForgeVersion();
        if (this.version() != null && !fg2.getMinecraftVersion().equals(this.version()))
            version += '-' + this.version();

        return Artifact.from(Constants.MC_GROUP, name, version).withExtension("zip");
    }

    protected Artifact getArtifact(MCPLegacy legacy) {
        //net.minecraft:mappings_{CHANNEL}:{MC_VERSION}-legacy[-{VERSION}]@zip
        var name = "mappings_" + this.channel;
        // either the raw mc version, or MC_VERSION-PYTHON_VERSION;
        var version = legacy.getName().getVersion() + "-legacy";
        if (this.version() != null && !legacy.getMinecraftTasks().getVersion().equals(this.version()))
            version += '-' + this.version();

        return Artifact.from(Constants.MC_GROUP, name, version).withExtension("zip");
    }
}
