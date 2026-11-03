/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.file.FileUtils;

final class PythonForgeSource {
    private static final String ROOT = "forge/";
    private static final String FML = ROOT + "fml/";
    private static final String CONF = FML + "conf/";
    private static final String FORMAT = "python-src-v9";

    private PythonForgeSource() {}

    static void prepare(File output, File sourceZip) {
        try (var source = new ZipFile(sourceZip)) {
            var cache = Util.cache(output)
                    .add("source", sourceZip)
                    .addKnown("format", FORMAT);

            if (Mavenizer.checkCache(output, cache))
                return;

            var entries = new LinkedHashMap<String, byte[]>();
            addRequired(source, entries, "dev.json", FML + "fml.json");

            for (var csv : new String[]{"fields.csv", "methods.csv", "params.csv"})
                addRequired(source, entries, "mappings/" + csv, FML + "conf/" + csv);

            addRequired(source, entries, "src/main/resources/fml_at.cfg", FML + "common/fml_at.cfg");
            addRequired(source, entries, "src/main/resources/forge_at.cfg", ROOT + "common/forge_at.cfg");
            addPatchArchive(source, entries, "fmlpatches.zip", FML + "patches/minecraft/");
            addPatchArchive(source, entries, "forgepatches.zip", ROOT + "patches/minecraft/");

            for (var prefix : new String[]{
                    FML + "client/", FML + "common/", ROOT + "client/", ROOT + "common/"
            })
                addSourceTree(source, entries, prefix);

            FileUtils.ensureParent(output);
            try (var out = new ZipOutputStream(new FileOutputStream(output))) {
                for (var entry : entries.entrySet()) {
                    out.putNextEntry(FileUtils.getStableEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }

            cache.save();
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

    private static void addRequired(ZipFile source, Map<String, byte[]> entries, String outputName, String sourceName) throws IOException {
        if (!addOptional(source, entries, outputName, sourceName))
            throw new IllegalStateException("Python-era Forge source archive is missing " + sourceName);
    }

    private static boolean addOptional(ZipFile source, Map<String, byte[]> entries, String outputName, String sourceName) throws IOException {
        var data = readOptional(source, sourceName);
        if (data == null)
            return false;
        entries.put(outputName, data);
        return true;
    }

    private static byte[] readRequired(ZipFile source, String name) throws IOException {
        var data = readOptional(source, name);
        if (data == null)
            throw new IllegalStateException("Python-era Forge source archive is missing " + name);
        return data;
    }

    private static byte[] readOptional(ZipFile source, String name) throws IOException {
        var entry = source.getEntry(name);
        if (entry == null || entry.isDirectory())
            return null;

        try (var input = source.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void addPatchArchive(ZipFile source, Map<String, byte[]> entries, String outputName, String prefix) throws IOException {
        var patches = new ByteArrayOutputStream();
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

    private static void addSourceTree(ZipFile source, Map<String, byte[]> entries, String prefix) throws IOException {
        for (var enumeration = source.entries(); enumeration.hasMoreElements(); ) {
            var entry = enumeration.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix))
                continue;

            var relative = safeRelative(entry.getName().substring(prefix.length()));
            var output = (relative.endsWith(".java") ? "src/main/java/" : "src/main/resources/") + relative;
            try (var input = source.getInputStream(entry)) {
                entries.put(output, input.readAllBytes());
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
