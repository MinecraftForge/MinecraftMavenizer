/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.mcmaven.impl.tasks.MCPNames;
import net.minecraftforge.mcmaven.impl.tasks.MCPNames.JavadocAdder;
import net.minecraftforge.mcmaven.impl.util.NewLineDetector;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.file.FileUtils;

/*
 * Older versions require renaming between FML and Forge patches,
 * and include custom comments for injecting javadocs as a second step.
 * So this implements that. Basically just copy pasted from FG 1.0
 */
class LegacyRenamer {
    //private static final Pattern SRG_FINDER = Pattern.compile("func_[0-9]+_[a-zA-Z_]+|field_[0-9]+_[a-zA-Z_]+|p_[\\w]+_\\d+_");
    private static final Pattern METHOD     = Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+(func_[0-9]+_[a-zA-Z_]+)\\(");
    private static final Pattern FIELD      = Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+(field_[0-9]+_[a-zA-Z_]+) *(?:=|;)");

    public static void rename(File input, File mappings, File output, boolean javadocMarkers) {
        try {
            var names = MCPNames.load(mappings);
            FileUtils.ensureParent(output);
            try (var zin = new ZipInputStream(new FileInputStream(input));
                 var zout = new ZipOutputStream(new FileOutputStream(output))) {
                for (ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                    var newEntry = new ZipEntry(entry.getName());
                    newEntry.setTime(entry.getTime());
                    zout.putNextEntry(newEntry);

                    if (!entry.getName().endsWith(".java")) {
                        zin.transferTo(zout);
                    } else {
                        var lines = NewLineDetector.readLines(zin);
                        lines = rename(names, lines, javadocMarkers);
                        for (var itr = lines.iterator(); itr.hasNext();) {
                            var line = itr.next();
                            zout.write(line.getBytes(StandardCharsets.UTF_8));
                            if (itr.hasNext())
                                zout.write('\n');
                        }
                    }

                    zout.closeEntry();
                }
            }
        } catch (IOException e) {
            Util.sneak(e);
        }
    }

    private static List<String> rename(MCPNames names, List<String> lines, boolean javadocMarkers) {
        var newLines = new ArrayList<String>(lines.size());
        Matcher matcher = null;
        for (var line : lines) {
            if (line.trim().startsWith("// JAVADOC ")) {
                var idx = line.indexOf('$');
                if (idx != -1 && idx < line.length() - 2) {
                    var name = line.substring(idx + 2);
                    var docs = names.docs().get(name);
                    if (docs != null && !docs.isEmpty())
                        line = JavadocAdder.buildJavadoc(line.substring(line.indexOf('/')), docs, true);
                }
            } else if ((matcher = METHOD.matcher(line)).find()) {
                var name = matcher.group(2);
                var docs = names.docs().get(name);

                if (!name.equals(names.names().getOrDefault(name, name)) && docs != null && !docs.isEmpty()) {
                    String formatted;
                    if (javadocMarkers)
                        formatted = matcher.group(1) + "// JAVADOC METHOD $$ " + name;
                    else
                        formatted = JavadocAdder.buildJavadoc(matcher.group(1), docs, true);
                    MCPNames.insertAboveAnnotations(newLines, formatted);
                }
            } else if ((matcher = FIELD.matcher(line)).find()) {
                var name = matcher.group(2);
                var docs = names.docs().get(name);
                if (!name.equals(names.names().getOrDefault(name, name)) && docs != null && !docs.isEmpty()) {
                    String formatted;
                    if (javadocMarkers)
                        formatted = matcher.group(1) + "// JAVADOC FIELD $$ " + name;
                    else
                        formatted = JavadocAdder.buildJavadoc(matcher.group(1), docs, true);
                    MCPNames.insertAboveAnnotations(newLines, formatted);
                }
            }
            newLines.add(names.replaceInLine(line, null));
        }
        return newLines;
    }
}
