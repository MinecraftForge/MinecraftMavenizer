/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import net.minecraftforge.jver.util.OS;

public class ProcessUtils {
    public static class Result {
        public final List<String> lines;
        public final int exitCode;
        private Result(List<String> lines, int exitCode) {
            this.lines = Collections.unmodifiableList(lines);
            this.exitCode = exitCode;
        }
    }

    private static String getStackTrace(Throwable t) {
        var string = new StringWriter();
        t.printStackTrace(new PrintWriter(string, true));
        return string.toString();
    }

    private static void getStackTrace(Throwable t, Consumer<String> lines) {
        for (var line : getStackTrace(t).split("\r?\n"))
            lines.accept(line);
    }

    public static Result runCommand(String... args) {
        return runCommand((File)null, args);
    }

    public static Result runCommand(File workDir, String... args) {
        var lines = new ArrayList<String>();
        int exitCode = runCommand(workDir, lines::add, args);
        return new Result(lines, exitCode);
    }

    public static int runCommand(List<String> lines, String... args) {
        return runCommand(lines::add, args);
    }

    public static int runCommand(Consumer<String> lines, String... args) {
        return runCommand(null, lines, args);
    }

    public static int runCommand(File workDir, Consumer<String> lines, String... args) {
        Process process;
        try {
            var builder = new ProcessBuilder(args)
                .redirectErrorStream(true);

            if (workDir != null)
                builder.directory(workDir);

            process = builder.start();
        } catch (IOException e) {
            getStackTrace(e, lines);
            return -1;
        }

        var is = new BufferedReader(new InputStreamReader(process.getInputStream()));

        while (process.isAlive()) {
            try {
                while (is.ready()) {
                    String line = is.readLine();
                    if (line != null)
                        lines.accept(line);
                }
            } catch (IOException e) {
                getStackTrace(e, lines);
                process.destroy();
                return -2;
            }
        }

        return process.exitValue();
    }

    protected static Path getPathFromResource(String resource) {
        return getPathFromResource(resource, ProcessUtils.class.getClassLoader());
    }

    protected static Path getPathFromResource(String resource, ClassLoader cl) {
        URL url = cl.getResource(resource);
        if (url == null)
            throw new IllegalStateException("Could not find " + resource + " in classloader " + cl);

        String str = url.toString();
        int len = resource.length();
        if ("jar".equalsIgnoreCase(url.getProtocol())) {
            str = url.getFile();
            len += 2;
        }
        str = str.substring(0, str.length() - len);
        return Paths.get(URI.create(str));
    }

    public static int runJar(File javaHome, File workDir, File logFile, File tool, List<String> jvm, List<String> run) {
        Util.ensureParent(logFile);
        try (var log = new PrintWriter(new FileWriter(logFile), true)) {
            String classpath = tool.getAbsolutePath();
            // Some old jvms require manually adding the classes zip, so lets add it if it exists
            File classes = new File(javaHome, "libs/classes.zip");
            if (classes.exists())
                classpath += File.pathSeparator + classes.getAbsolutePath();

            var main = getMainClass(tool);
            var launcher = new File(javaHome, "bin/java" + OS.CURRENT.exe());
            Consumer<String> lines = line -> {
                Log.log(line);
                log.println(line);
            };
            lines.accept("Java:      " + launcher.getAbsolutePath());
            lines.accept("Arguments: " + run.stream().collect(Collectors.joining(", ", "'", "'")));
            lines.accept("JVMArgs:   " + jvm.stream().collect(Collectors.joining(", ", "'", "'")));
            lines.accept("Classpath: " + classpath);
            lines.accept("Main:      " + main);
            lines.accept("Work Dir:  " + workDir.getAbsolutePath());
            log.println("====================================");

            var args = new ArrayList<String>();
            args.add(launcher.getAbsolutePath());
            args.addAll(jvm);
            args.add("-classpath");
            args.add(classpath);
            args.add(main);
            args.addAll(run);

            lines = line -> {
                log.println(line);
            };

            int ret = runCommand(workDir, lines, args.toArray(String[]::new));
            System.out.println();

            log.flush();
            return ret;
        } catch (IOException e) {
            return sneak(e);
        }
    }

    private static String getMainClass(File tool) throws IOException {
        try (var jar = new JarFile(tool)) {
            return jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
    }

    @SuppressWarnings("unchecked")
    private static <R, E extends Throwable> R sneak(Throwable t) throws E {
        throw (E)t;
    }
}
