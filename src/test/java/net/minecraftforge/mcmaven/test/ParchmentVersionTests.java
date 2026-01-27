/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import net.minecraftforge.mcmaven.impl.mappings.ParchmentVersion;

public class ParchmentVersionTests {
	@Test
	public void parsing() {
		parses("2026.01.01", null, "2026.01.01", null);
		parses("2026.01.01-1.12-pre1", "1.12-pre1", "2026.01.01", "1.12-pre1");
		parses("1.12.2-2026.01.01-1.13", "1.12.2", "2026.01.01", "1.13");
		fails("2026.01.01-nightly-SNAPSHOT");
		fails("BLEEDING-SNAPSHOT-1.12");
		// Propagates MC version, but not MCP Version
		parses("2026.01.01-1.12-20200101.000000", "1.12", "2026.01.01", "1.12-20200101.000000");
	}

	private void parses(String version, String mapMcVersion, String timestamp, String mcVersion) {
		var parsed = ParchmentVersion.parse(version);
		if (mapMcVersion == null)
			Assertions.assertNull(parsed.mapMcVersion(), "Map MC Version was not null for " + version);
		else
			Assertions.assertEquals(mapMcVersion, parsed.mapMcVersion(), "Map MC Version did not match for " + version);

		Assertions.assertEquals(timestamp, parsed.timestamp(), "Timestamp did not parse correctly: " + version);

		if (mcVersion == null)
			Assertions.assertNull(parsed.mcVersion(), "MC Version was not null for " + version);
		else
			Assertions.assertEquals(mcVersion, parsed.mcVersion(), "MC Version did not match for " + version);
	}

	private void fails(String version) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> ParchmentVersion.parse(version));
	}

	@Test
	public void withMinecraft() {
		// Should propagate to mapping version
		var version = ParchmentVersion.parse("2026.01.01").withMinecraft("1.12");
		Assertions.assertEquals(version.mapMcVersion(), "1.12");
		Assertions.assertEquals(version.mcVersion(), "1.12");

		// Should NOT propagate to mapping version
		version = ParchmentVersion.parse("2026.01.01-1.6").withMinecraft("1.12");
		Assertions.assertEquals(version.mapMcVersion(), "1.6");
		Assertions.assertEquals(version.mcVersion(), "1.12");
	}

	@Test
	public void friendly() {
		friendly("2026.01.01");
		friendly("1.12-2026.01.01");
		friendly("2026.01.01-1.12");
		friendly("1.12-2026.01.01-1.12", "2026.01.01-1.12");
		friendly("1.12-2026.01.01-1.12-20200101.000000", "2026.01.01-1.12-20200101.000000");
	}

	private void friendly(String version) {
		friendly(version, version);
	}

	private void friendly(String version, String friendly) {
		var parsed = ParchmentVersion.parse(version);
		Assertions.assertEquals(friendly, parsed.toFriendly());
	}
}
