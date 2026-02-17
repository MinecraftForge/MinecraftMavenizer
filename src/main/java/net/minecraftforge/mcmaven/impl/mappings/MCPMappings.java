/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;

public class MCPMappings extends Mappings {
	public MCPMappings(String channel, @Nullable String version) {
		super(channel, version);
		if (version == null)
			throw new IllegalArgumentException("MCP Mappings can not have a null version");
	}

	@Override
    public Task getCsvZip(MCPSide side) {
        var key = new Key(Tasks.CSVs, side);
        var ret = tasks.get(key);
        if (ret != null)
            return ret;

        // This is simple because it's the old MCP Bot based zip files
        // So it's just a matter of downloading from Forge's maven.
        var artifact = Artifact.from("de.oceanlabs.mcp", "mcp_" + this.channel(), this.version(), null, "zip");
        var maven = side.getMCP().getCache().maven();

        ret = Task.named("srg2names[" + this + ']',
            () -> maven.download(artifact)
        );
        tasks.put(key, ret);
        return ret;
    }

	@Override
    public Mappings withMCVersion(String version) {
		return this;
    }
}
