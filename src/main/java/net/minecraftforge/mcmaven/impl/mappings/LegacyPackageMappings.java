/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.siegmar.fastcsv.reader.CsvReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class LegacyPackageMappings {
    private static final Pattern LEGACY_CLASS = Pattern.compile("net/minecraft/src/([a-zA-Z0-9_$]+)");
    private static final Pattern LEGACY_PATCH_CLASS = Pattern.compile("net[\\\\/]minecraft[\\\\/]src[\\\\/]([a-zA-Z0-9_$]+)");

    private LegacyPackageMappings() {}

    public static byte[] repackage(byte[] mappings, byte[] packages) throws IOException {
        var classPackages = readPackages(packages);
        var input = new String(mappings, StandardCharsets.UTF_8);
        return replaceClasses(input, classPackages, LEGACY_CLASS).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] repackagePatch(byte[] patchBytes, byte[] packages) throws IOException {
        var classPackages = readPackages(packages);
        var patch = new String(patchBytes, StandardCharsets.UTF_8);
        patch = replaceClasses(patch, classPackages, LEGACY_PATCH_CLASS);

        var lines = patch.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (!line.startsWith("--- ") && !line.startsWith("+++ ")) {
                continue;
            }

            var pathStart = 4;
            var pathEnd = line.indexOf('\t', pathStart);
            if (pathEnd == -1) {
                pathEnd = line.length();
            }

            var path = line.substring(pathStart, pathEnd).replace('\\', '/');

            lines[i] = line.substring(0, pathStart) + path + line.substring(pathEnd);
        }
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> readPackages(byte[] packages) throws IOException {
        var classPackages = new HashMap<String, String>();
        try (var reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(packages), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                var parts = line.split(",", -1);
                if (parts.length >= 2) {
                    var cls = parts[0].trim();
                    var pkg = parts[1].trim();

                    if ("class".equalsIgnoreCase(cls) && "package".equalsIgnoreCase(pkg)) {
                        continue;
                    }
                    classPackages.put(cls, pkg);
                }
            }
        }
        return classPackages;
    }

    private static String replaceClasses(String input, Map<String, String> classPackages, Pattern pattern) {
        var matcher = pattern.matcher(input);
        var output = new StringBuffer(input.length());
        while (matcher.find()) {
            var simpleName = matcher.group(1);

            var rootClass = simpleName.contains("$") ? simpleName.substring(0, simpleName.indexOf('$')) : simpleName;
            var targetPackage = classPackages.get(simpleName);
            if (targetPackage == null) {
                targetPackage = classPackages.get(rootClass);
            }

            if (targetPackage != null) {
                String fullPath = targetPackage.isEmpty() ? simpleName : (targetPackage + '/' + simpleName);
                matcher.appendReplacement(output, Matcher.quoteReplacement(fullPath));
            }
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
