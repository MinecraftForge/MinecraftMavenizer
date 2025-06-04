/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import de.siegmar.fastcsv.reader.CsvReader;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.logging.Log;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

// TODO GARBAGE GARBAGE GARBAGE, CLEAN UP OR RE-IMPLEMENT
final class MCPNames {
    //@formatter:off
    private static final Pattern
        SRG_FINDER                  = Pattern.compile("[fF]unc_\\d+_[a-zA-Z_]+|m_\\d+_|[fF]ield_\\d+_[a-zA-Z_]+|f_\\d+_|p_\\w+_\\d+_|p_\\d+_"),
        CONSTRUCTOR_JAVADOC_PATTERN = Pattern.compile("^(?<indent>(?: {3})+|\\t+)(public |private|protected |)(?<generic><[\\w\\W]*>\\s+)?(?<name>[\\w.]+)\\((?<parameters>.*)\\)\\s+(?:throws[\\w.,\\s]+)?\\{"),
        METHOD_JAVADOC_PATTERN      = Pattern.compile("^(?<indent>(?: {3})+|\\t+)(?!return)(?:\\w+\\s+)*(?<generic><[\\w\\W]*>\\s+)?(?<return>\\w+[\\w$.]*(?:<[\\w\\W]*>)?[\\[\\]]*)\\s+(?<name>(?:func_|m_)[0-9]+_[a-zA-Z_]*)\\("),
        FIELD_JAVADOC_PATTERN       = Pattern.compile("^(?<indent>(?: {3})+|\\t+)(?!return)(?:\\w+\\s+)*\\w+[\\w$.]*(?:<[\\w\\W]*>)?[\\[\\]]*\\s+(?<name>(?:field_|f_)[0-9]+_[a-zA-Z_]*) *[=;]"),
        CLASS_JAVADOC_PATTERN       = Pattern.compile("^(?<indent> *|\\t*)([\\w|@]*\\s)*(class|interface|@interface|enum) (?<name>[\\w]+)"),
        CLOSING_CURLY_BRACE         = Pattern.compile("^(?<indent> *|\\t*)}"),
        PACKAGE_DECL                = Pattern.compile("^[\\s]*package(\\s)*(?<name>[\\w|.]+);$"),
        LAMBDA_DECL                 = Pattern.compile("\\((?<args>(?:(?:, ){0,1}p_[\\w]+_\\d+_\\b)+)\\) ->");
    //@formatter:on

    static MCPNames load(File data) throws IOException {
        var names = new HashMap<String, String>();
        var docs = new HashMap<String, String>();
        try (var zip = new ZipFile(data)) {
            var entries = zip.stream().filter(e -> e.getName().endsWith(".csv")).toList();
            for (var entry : entries) {
                try (var reader = CsvReader.builder().ofNamedCsvRecord(new InputStreamReader(zip.getInputStream(entry)))) {
                    for (var row : reader) {
                        var header = row.getHeader();
                        var obf = header.contains("searge") ? "searge" : "param";
                        var searge = row.getField(obf);
                        names.put(searge, row.getField("name"));
                        if (header.contains("desc")) {
                            String desc = row.getField("desc");
                            if (!desc.isBlank())
                                docs.put(searge, desc);
                        }
                    }
                }
            }
        }

        return new MCPNames(HashFunction.SHA1.hash(data), names, docs);
    }

    // NOTE: this is a micro-optimization to avoid creating a new pattern for every line
    private static final Pattern ARGS_DELIM = Pattern.compile(", ");

    // TODO [MCMaven][MCPNames] Not used for anything. Remove?
    private final String hash;
    private final Map<String, String> names;
    private final Map<String, String> docs;

    MCPNames(String hash, Map<String, String> names, Map<String, String> docs) {
        this.hash = hash;
        this.names = names;
        this.docs = docs;
    }

    String rename(String entry) {
        return this.names.getOrDefault(entry, entry);
    }

    String rename(InputStream stream, boolean javadocs) throws IOException {
        return this.rename(stream, javadocs, true, StandardCharsets.UTF_8);
    }

    String rename(InputStream stream, boolean javadocs, boolean lambdas) throws IOException {
        return this.rename(stream, javadocs, lambdas, StandardCharsets.UTF_8);
    }

    String rename(InputStream stream, boolean javadocs, boolean lambdas, Charset sourceFileCharset) throws IOException {
        String data = IOUtils.toString(stream, sourceFileCharset);
        List<String> input = IOUtils.readLines(new StringReader(data));

        // Return early on emtpy files
        if (data.isEmpty())
            return "";

        //Reader doesn't give us the empty line if the file ends with a newline.. so add one.
        if (data.charAt(data.length() - 1) == '\r' || data.charAt(data.length() - 1) == '\n')
            input.add("");

        var lines = new ArrayList<String>();
        var innerClasses = new LinkedList<Pair<String, Integer>>(); //pair of inner class name & indentation
        var _package = ""; //default package
        var blacklist = new HashSet<String>();

        if (!lambdas) {
            for (String line : input) {
                var matcher = LAMBDA_DECL.matcher(line);
                if (!matcher.find()) continue;

                blacklist.addAll(Arrays.asList(ARGS_DELIM.split(matcher.group("args"))));
            }
        }

        for (String line : input) {
            Matcher m = PACKAGE_DECL.matcher(line);
            if (m.find())
                _package = m.group("name") + ".";

            if (javadocs) {
                if (!injectJavadoc(lines, line, _package, innerClasses))
                    javadocs = false;
            }
            lines.add(replaceInLine(line, blacklist));
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * Injects a javadoc into the given list of lines, if the given line is a method or field declaration.
     *
     * @param lines        The current file content (to be modified by this method)
     * @param line         The line that was just read (will not be in the list)
     * @param _package     the name of the package this file is declared to be in, in com.example format;
     * @param innerClasses current position in inner class
     */
    private boolean injectJavadoc(List<String> lines, String line, String _package, Deque<Pair<String, Integer>> innerClasses) {
        Matcher matcher;

        // constructors
        matcher = CONSTRUCTOR_JAVADOC_PATTERN.matcher(line);
        boolean isConstructor = matcher.find() && !innerClasses.isEmpty() && innerClasses.peek().getLeft().contains(matcher.group("name"));

        // methods
        if (!isConstructor)
            matcher = METHOD_JAVADOC_PATTERN.matcher(line);

        if (isConstructor || matcher.find()) {
            String name = isConstructor ? "<init>" : matcher.group("name");
            String javadoc = docs.get(name);
            if (javadoc == null && !innerClasses.isEmpty() && !name.startsWith("func_") && !name.startsWith("m_")) {
                String currentClass = innerClasses.peek().getLeft();
                javadoc = docs.get(currentClass + '#' + name);
            }
            if (javadoc != null)
                insertAboveAnnotations(lines, JavadocAdder.buildJavadoc(matcher.group("indent"), javadoc, true));

            // worked, so return and don't try the fields.
            return true;
        }

        // fields
        matcher = FIELD_JAVADOC_PATTERN.matcher(line);
        if (matcher.find()) {
            String name = matcher.group("name");
            String javadoc = docs.get(name);
            if (javadoc == null && !innerClasses.isEmpty() && !name.startsWith("field_") && !name.startsWith("f_")) {
                String currentClass = innerClasses.peek().getLeft();
                javadoc = docs.get(currentClass + '#' + name);
            }
            if (javadoc != null)
                insertAboveAnnotations(lines, JavadocAdder.buildJavadoc(matcher.group("indent"), javadoc, false));

            return true;
        }

        //classes
        matcher = CLASS_JAVADOC_PATTERN.matcher(line);
        if (matcher.find()) {
            //we maintain a stack of the current (inner) class in com.example.ClassName$Inner format (along with indentation)
            //if the stack is not empty we are entering a new inner class
            String currentClass = (innerClasses.isEmpty() ? _package : innerClasses.peek().getLeft() + "$") + matcher.group("name");
            innerClasses.push(Pair.of(currentClass, matcher.group("indent").length()));
            String javadoc = docs.get(currentClass);
            if (javadoc != null) {
                insertAboveAnnotations(lines, JavadocAdder.buildJavadoc(matcher.group("indent"), javadoc, true));
            }

            return true;
        }

        //detect curly braces for inner class stacking/end identification
        matcher = CLOSING_CURLY_BRACE.matcher(line);
        if (matcher.find()) {
            if (!innerClasses.isEmpty()) {
                int len = matcher.group("indent").length();
                if (len == innerClasses.peek().getRight()) {
                    innerClasses.pop();
                } else if (len < innerClasses.peek().getRight()) {
                    Log.error("Failed to properly track class blocks around class " + innerClasses.peek().getLeft() + ":" + (lines.size() + 1));
                    return false;
                }
            }
        }

        return true;
    }

    /** Inserts the given javadoc line into the list of lines before any annotations */
    private static void insertAboveAnnotations(List<String> list, String line) {
        int back = 0;
        while (list.get(list.size() - 1 - back).trim().startsWith("@"))
            back++;
        list.add(list.size() - back, line);
    }

    /*
     * There are certain times, such as Mixin Accessors that we wish to have the name of this method with the first character upper case.
     */
    private String getMapped(String srg, @Nullable Set<String> blacklist) {
        if (blacklist != null && blacklist.contains(srg))
            return srg;

        boolean cap = srg.charAt(0) == 'F';
        if (cap)
            srg = 'f' + srg.substring(1);

        String ret = names.getOrDefault(srg, srg);
        if (cap)
            ret = ret.substring(0, 1).toUpperCase(Locale.ENGLISH) + ret.substring(1);
        return ret;
    }

    private String replaceInLine(String line, @Nullable Set<String> blacklist) {
        var buf = new StringBuffer();
        var matcher = SRG_FINDER.matcher(line);
        while (matcher.find()) {
            // Since '$' is a valid character in identifiers, but we need to NOT treat this as a regex group, escape any occurrences
            matcher.appendReplacement(buf, Matcher.quoteReplacement(getMapped(matcher.group(), blacklist)));
        }
        matcher.appendTail(buf);
        return buf.toString();
    }

    private interface JavadocAdder {
        /**
         * Converts a raw javadoc string into a nicely formatted, indented, and wrapped string.
         *
         * @param indent    the indent to be inserted before every line.
         * @param javadoc   The javadoc string to be processed
         * @param multiline If this javadoc is mutlilined (for a field, it isn't) even if there is only one line in the
         *                  doc
         * @return A fully formatted javadoc comment string complete with comment characters and newlines.
         */
        static String buildJavadoc(String indent, String javadoc, boolean multiline) {
            var builder = new StringBuilder();

            // split and wrap.
            var list = new LinkedList<String>();

            for (var line : javadoc.split("\n")) {
                list.addAll(wrapText(line, 120 - (indent.length() + 3)));
            }

            if (list.size() > 1 || multiline) {
                builder.append(indent);
                builder.append("/**");
                builder.append(System.lineSeparator());

                for (String line : list) {
                    builder.append(indent);
                    builder.append(" * ");
                    builder.append(line);
                    builder.append(System.lineSeparator());
                }

                builder.append(indent);
                builder.append(" */");
                //builder.append(System.lineSeparator());

            }
            // one line
            else {
                builder.append(indent);
                builder.append("/** ");
                builder.append(javadoc);
                builder.append(" */");
                //builder.append(System.lineSeparator());
            }

            return builder.toString().replace(indent, indent);
        }

        private static @Unmodifiable Collection<String> wrapText(String text, int len) {
            // return empty array for null text
            if (text == null)
                return List.of();

            // return text if len is zero or less OR text length is less than len
            if (len <= 0 || text.length() <= len)
                return List.of(text);

            var lines = new LinkedList<String>();
            var line = new StringBuilder();
            var word = new StringBuilder();
            int tempNum;

            // each char in array
            for (char c : text.toCharArray()) {
                switch (c) {
                    // it's a wordBreaking character.
                    case ' ', ',', '-' -> {
                        // add the character to the word
                        word.append(c);

                        // its a space. set TempNum to 1, otherwise leave it as a wrappable char
                        tempNum = Character.isWhitespace(c) ? 1 : 0;

                        // subtract tempNum from the length of the word
                        if ((line.length() + word.length() - tempNum) > len) {
                            lines.add(line.toString());
                            line.delete(0, line.length());
                        }

                        // new word, add it to the next line and clear the word
                        line.append(word);
                        word.delete(0, word.length());
                    }
                    // not a linebreak char, add it to the word and move on
                    default -> word.append(c);
                }
            }

            // handle any extra chars in current word
            if (!word.isEmpty()) {
                if ((line.length() + word.length()) > len) {
                    lines.add(line.toString());
                    line.delete(0, line.length());
                }
                line.append(word);
            }

            // handle extra line
            if (!line.isEmpty())
                lines.add(line.toString());

            return lines.stream().map(String::trim).toList();
        }
    }
}