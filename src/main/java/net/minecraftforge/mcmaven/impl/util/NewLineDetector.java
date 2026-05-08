/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// The normal BufferedReader doesn't have a way to determine between a
// file with a newline at the end and a file with no newline at the end.
// So we detect that and add an extra empty result from 'readLine'
public class NewLineDetector {
    public static List<String> readLines(String text) {
        try {
            return readLines(new StringReader(text));
        } catch (IOException e) { // Should never happen as we're reading from a string
            return Util.sneak(e);
        }
    }
    public static List<String> readLines(InputStream input) throws IOException {
        return readLines(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
    private static List<String> readLines(Reader input) throws IOException {
        var reader = new NewLineDetector(input);
        List<String> lines = new ArrayList<String>();
        for (String line; (line = reader.readLine()) != null; )
            lines.add(line);
        return lines;
    }

    private final BufferedReader buffered;
    private final Detector detector;

    public NewLineDetector(Reader reader) {
        this.detector = new Detector(reader);
        this.buffered = new BufferedReader(this.detector);
    }

    public String readLine() throws IOException {
        String ret = this.buffered.readLine();
        if (ret == null && this.detector.lastWasNewline) {
            this.detector.lastWasNewline = false;
            return "";
        }
        return ret;
    }

    private static class Detector extends Reader {
        private final Reader wrapped;
        private boolean lastWasNewline = false;

        public Detector(Reader wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            int ret = this.wrapped.read(cbuf, off, len);
            if (ret > 0) {
                int c = cbuf[off + ret - 1];
                lastWasNewline = c == '\n';
            }
            return ret;
        }

        @Override
        public void close() throws IOException {
            this.wrapped.close();
        }
    }
}