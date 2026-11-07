/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;

public final class PythonForgePre16Source {
    private static final String ROOT = "forge/";
    private static final String FML = ROOT + "fml/";
    private static final String CONF = FML + "conf/";
    private static final String FORMAT = "python-pre16-v3";

    record McpDistribution(String version, String filename, String md5) {}

    private PythonForgePre16Source() {}

    static McpDistribution readMcpDistribution(File sourceZip, String mcVersion) {
        try (var source = new ZipFile(sourceZip)) {
            var version = readMcpVersion(source);
            var entry = source.getEntry(FML + "mc_versions.cfg");
            if (entry == null)
                throw new IllegalStateException("Python-era Forge source archive is missing " + FML + "mc_versions.cfg");
            var values = new Properties();
            var section = "";
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(source.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                        continue;
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length() - 1);
                    } else if (section.equals(mcVersion)) {
                        var idx = line.indexOf('=');
                        if (idx > 0)
                            values.setProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                }
            }
            var filename = values.getProperty("mcp_url");
            var md5 = values.getProperty("mcp_md5");
            if (filename == null || md5 == null || md5.isBlank())
                throw new IllegalStateException("Missing MCP URL or checksum for Minecraft " + mcVersion + " in " + FML + "mc_versions.cfg");
            filename = filename.substring(filename.lastIndexOf('/') + 1);
            var configuredVersion = values.getProperty("mcp_ver");
            if (configuredVersion == null || !version.equals(configuredVersion))
                throw new IllegalStateException("MCP version in " + FML + "conf/version.cfg does not match " + FML + "mc_versions.cfg for Minecraft " + mcVersion);
            return new McpDistribution(version, filename, md5);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Python-era Forge source archive " + sourceZip.getAbsolutePath(), e);
        }
    }

    static File downloadPythonMcp(File output, String expectedMd5) {
        if (!output.exists()) {
            Mavenizer.assertOnline();
            if (!DownloadUtils.tryDownloadFile(output, Constants.MCP_DOWNLOADS + output.getName()))
                throw new IllegalStateException("Failed to download Python-era MCP archive " + Constants.MCP_DOWNLOADS + output.getName());
        }

        var actualMd5 = Util.sneak(() -> HashFunction.md5().hash(output));
        if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
            if (!output.delete())
                throw new IllegalStateException("MCP archive checksum mismatch and invalid cached file could not be removed: " + output.getAbsolutePath());
            Mavenizer.assertOnline();
            if (!DownloadUtils.tryDownloadFile(output, Constants.MCP_DOWNLOADS + output.getName()))
                throw new IllegalStateException("Failed to redownload Python-era MCP archive " + Constants.MCP_DOWNLOADS + output.getName());
            actualMd5 = Util.sneak(() -> HashFunction.md5().hash(output));
            if (!expectedMd5.equalsIgnoreCase(actualMd5))
                throw new IllegalStateException("MCP archive checksum mismatch for " + output.getName() + ": expected " + expectedMd5 + ", got " + actualMd5);
        }
        return output;
    }

    public static String getSnapshotVersion(File source) {
        try (var mcp = new ZipFile(source)) {
            var fields = mcp.getEntry(CONF+ "fields.csv");
            if (fields == null)
                throw new IllegalStateException("Python-era MCP archive is missing conf/fields.csv: " + source);
            var modified = fields.getTime();
            if (modified < 0)
                throw new IllegalStateException("Python-era MCP conf/fields.csv has no modification time: " + source);

            var versionEntry = mcp.getEntry(CONF+ "version.cfg");
            if (versionEntry == null)
                throw new IllegalStateException("Python-era MCP archive is missing conf/version.cfg: " + source);
            var properties = new Properties();
            try (var input = mcp.getInputStream(versionEntry)) {
                properties.load(input);
            }
            var minecraftVersion = properties.getProperty("ClientVersion");
            if (minecraftVersion == null || minecraftVersion.isBlank())
                throw new IllegalStateException("Python-era MCP conf/version.cfg is missing ClientVersion: " + source);

            var date = Instant.ofEpochMilli(modified).atZone(ZoneOffset.UTC).toLocalDate();
            return DateTimeFormatter.BASIC_ISO_DATE.format(date) + '-' + minecraftVersion.trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Python-era MCP archive " + source.getAbsolutePath(), e);
        }
    }

    static void prepare(File assetsOutput, File mcpOutput, File sourceZip, File mcpZip, File srgZip) {
        try (var source = new ZipFile(sourceZip);
             var mcp = new ZipFile(mcpZip);
             var srg = new ZipFile(srgZip)) {
            var assetCache = Util.cache(assetsOutput)
                    .add("source", sourceZip)
                    .addKnown("format", FORMAT + "-assets");
            var mcpCache = Util.cache(mcpOutput)
                    .add("source", sourceZip)
                    .add("mcp", mcpZip)
                    .add("srg", srgZip)
                    .addKnown("format", FORMAT + "-mcp");
            var prepareAssets = !Mavenizer.checkCache(assetsOutput, assetCache);
            var prepareMcp = !Mavenizer.checkCache(mcpOutput, mcpCache);
            if (!prepareAssets && !prepareMcp)
                return;

            if (prepareAssets) {
                var assets = new LinkedHashMap<String, byte[]>();
                addPatchArchive(source, assets, "fmlpatches.zip", FML + "patches/minecraft/");
                addPatchArchive(source, assets, "forgepatches.zip", ROOT + "patches/minecraft/");
                addRequired(source, assets, "fml_at.cfg", FML + "common/fml_at.cfg");
                addRequired(source, assets, "forge_at.cfg", ROOT + "common/forge_at.cfg");
                addRequired(source, assets, "mcp_merge.cfg", ROOT + "fml/mcp_merge.cfg");
                addRequired(source, assets, "commands.patch", ROOT + "fml/commands.patch");
                addSourceTree(source, assets, FML + "client/", "overlays/fml/");
                addSourceTree(source, assets, FML + "common/", "overlays/fml/");
                addSourceTree(source, assets, ROOT + "client/", "overlays/forge/");
                addSourceTree(source, assets, ROOT + "common/", "overlays/forge/");
                writeArchive(assetsOutput, assets);
                assetCache.save();
            }

            if (prepareMcp) {
                mergeMcpConfig(mcpOutput, source, mcp, srg);
                mcpCache.save();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare Python-era Forge source archive " + sourceZip.getAbsolutePath(), e);
        }
    }

    private static String readMcpVersion(ZipFile source) throws IOException {
        var entry = source.getEntry(FML + "conf/version.cfg");
        if (entry == null)
            throw new IllegalStateException("Python-era Forge source archive is missing " + FML + "conf/version.cfg");
        var properties = new Properties();
        try (var input = source.getInputStream(entry)) {
            properties.load(input);
        }
        var version = properties.getProperty("MCPVersion");
        if (version == null || version.isBlank())
            throw new IllegalStateException("Missing MCPVersion in " + FML + "conf/version.cfg");
        return version.trim();
    }

    private static void mergeMcpConfig(File output, ZipFile source, ZipFile mcp, ZipFile srg) throws IOException {
        var mcpVersion = readMcpVersion(source);
        var entries = new LinkedHashMap<String, byte[]>();
        for (var enumeration = mcp.entries(); enumeration.hasMoreElements(); ) {
            var entry = enumeration.nextElement();
            if (entry.isDirectory() || entry.getName().startsWith("conf/"))
                continue;
            try (var input = mcp.getInputStream(entry)) {
                putEntry(entries, entry.getName(), input.readAllBytes());
            }
        }

        var confCount = 0;
        for (var enumeration = source.entries(); enumeration.hasMoreElements(); ) {
            var entry = enumeration.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(CONF))
                continue;
            var relative = safeRelative(entry.getName().substring(CONF.length()));
            if (relative.equals("joined.srg"))
                continue;
            try (var input = source.getInputStream(entry)) {
                putEntry(entries, "conf/" + relative, input.readAllBytes());
            }
            confCount++;
        }
        for (var mapping : new String[]{"joined.srg", "joined.exc"}) {
            var entry = srg.getEntry(mapping);
            if (entry == null || entry.isDirectory())
                throw new IllegalStateException("MCP SRG archive is missing " + mapping + ": " + srg.getName());
            try (var input = srg.getInputStream(entry)) {
                entries.put("conf/" + mapping, input.readAllBytes());
            }
        }
        if (confCount == 0)
            throw new IllegalStateException("Python-era Forge source archive is missing " + CONF);
        for (var required : new String[]{"conf/mcp.cfg", "conf/version.cfg", "conf/joined.srg", "conf/joined.exc"})
            if (!entries.containsKey(required))
                throw new IllegalStateException("Python-era Forge source archive is missing " + required);
        if (!mcpVersion.equals(readMcpVersionFromConfig(entries.get("conf/version.cfg"))))
            throw new IllegalStateException("Inconsistent MCP version in Python-era Forge source archive");

        writeArchive(output, entries);
    }

    private static String readMcpVersionFromConfig(byte[] data) throws IOException {
        var properties = new Properties();
        try (var input = new ByteArrayInputStream(data)) {
            properties.load(input);
        }
        return properties.getProperty("MCPVersion", "").trim();
    }

    private static void putEntry(Map<String, byte[]> entries, String name, byte[] data) {
        if (entries.putIfAbsent(name, data) != null)
            throw new IllegalStateException("Duplicate entry in Python-era Forge source archive: " + name);
    }

    private static void writeArchive(File output, Map<String, byte[]> entries) throws IOException {
        FileUtils.ensureParent(output);
        try (var out = new ZipOutputStream(new FileOutputStream(output))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(FileUtils.getStableEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static void addRequired(ZipFile source, Map<String, byte[]> entries, String outputName, String sourceName) throws IOException {
        var entry = source.getEntry(sourceName);
        if (entry == null || entry.isDirectory())
            throw new IllegalStateException("Python-era Forge source archive is missing " + sourceName);
        try (var input = source.getInputStream(entry)) {
            entries.put(outputName, input.readAllBytes());
        }
    }

    private static void addPatchArchive(ZipFile source, Map<String, byte[]> entries, String outputName, String prefix) throws IOException {
        var patches = new java.io.ByteArrayOutputStream();
        try (var out = new ZipOutputStream(patches)) {
            for (var enumeration = source.entries(); enumeration.hasMoreElements(); ) {
                var entry = enumeration.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix) || !entry.getName().endsWith(".patch"))
                    continue;
                var name = safeRelative(entry.getName().substring(prefix.length()));
                try (var input = source.getInputStream(entry)) {
                    out.putNextEntry(FileUtils.getStableEntry(name));
                    input.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        entries.put(outputName, patches.toByteArray());
    }

    private static void addSourceTree(ZipFile source, Map<String, byte[]> entries, String prefix, String outputPrefix) throws IOException {
        for (var enumeration = source.entries(); enumeration.hasMoreElements(); ) {
            var entry = enumeration.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix))
                continue;
            var relative = safeRelative(entry.getName().substring(prefix.length()));
            try (var input = source.getInputStream(entry)) {
                if (entries.put(outputPrefix + relative, input.readAllBytes()) != null)
                    throw new IllegalStateException("Duplicate source overlay in Python-era Forge archive: " + outputPrefix + relative);
            }
        }
    }

    private static String safeRelative(String name) {
        var normalized = name.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains(":"))
            throw new IllegalStateException("Invalid path in Python-era Forge source archive: " + name);
        for (var segment : normalized.split("/")) {
            if (segment.equals(".") || segment.equals(".."))
                throw new IllegalStateException("Invalid path in Python-era Forge source archive: " + name);
        }
        return normalized;
    }
}
