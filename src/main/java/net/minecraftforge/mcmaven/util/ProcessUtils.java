/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import net.minecraftforge.java_version.util.OS;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// TODO [MCMaven][JavaVersion] Move to Java Version? It would be useful for ForgeGradle 7.
/** Utility class for running processes. */
public final class ProcessUtils {
    /** Represents the result of a process execution. */
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

    /**
     * Runs a command without an explicit working directory.
     *
     * @param args The command-line arguments
     * @return The result of the process
     *
     * @see #runCommand(File, String...)
     */
    public static Result runCommand(String... args) {
        return runCommand((File) null, args);
    }

    /**
     * Runs a command.
     *
     * @param workDir The working directory
     * @param args    The command-line arguments
     * @return The result of the process
     */
    public static Result runCommand(File workDir, String... args) {
        var lines = new ArrayList<String>();
        int exitCode = runCommand(workDir, lines::add, args);
        return new Result(lines, exitCode);
    }

    /**
     * Runs a command, without an explicit working directory, and collects the output into the given list.
     *
     * @param lines The list to collect the output into
     * @param args  The command-line arguments
     * @return The exit code of the process
     */
    public static int runCommand(List<String> lines, String... args) {
        return runCommand(lines::add, args);
    }

    /**
     * Runs a command, without an explicit working directory, and collects the output into the given consumer.
     *
     * @param lines The consumer to collect the output into
     * @param args  The command-line arguments
     * @return The exit code of the process
     */
    public static int runCommand(Consumer<String> lines, String... args) {
        return runCommand(null, lines, args);
    }

    /**
     * Runs a command and collects the output into the given consumer.
     *
     * @param workDir The working directory
     * @param lines   The consumer to collect the output into
     * @param args    The command-line arguments
     * @return The exit code of the process
     */
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

    static Path getPathFromResource(String resource) {
        return getPathFromResource(resource, ProcessUtils.class.getClassLoader());
    }

    static Path getPathFromResource(String resource, ClassLoader cl) {
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

    /**
     * Executes a jar file with the given arguments.
     *
     * @param javaHome The Java home directory
     * @param workDir  The working directory
     * @param logFile  The output log file
     * @param tool     The jar file to run (usually a tool)
     * @param jvm      The JVM arguments
     * @param run      The program arguments
     * @return The exit code of the process
     */
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
            System.currentTimeMillis();
            System.out.println();

            log.flush();
            return ret;
        } catch (IOException e) {
            return sneak(e);
        }
    }

    public static File recompileJar(File javaHome, List<File> classpath, File sourcesJar, File outputJar, File workDir) {
        // classpath arg
        var classpathFile = makeClasspathFile(workDir, makeClasspathArg(classpath));

        // unzip sources jar
        var sourcesOutput = new File(workDir, "recompileSources");
        try {
            // Ensure the output directory exists
            Util.ensureParent(sourcesOutput);

            try (ZipInputStream zin = new ZipInputStream(new FileInputStream(sourcesJar))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    File entryFile = new File(sourcesOutput, entry.getName());
                    Util.ensureParent(entryFile);

                    if (entry.isDirectory()) {
                        entryFile.mkdirs();
                    } else {
                        try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                            IOUtils.copy(zin, fos);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // track source files
        var sourcePath = new StringBuilder();
        var sourceFiles = Util.listFiles(sourcesOutput);
        var nonSourceFiles = new ArrayList<File>();
        for (var sourceFile : sourceFiles) {
            var absolutePath = sourceFile.getAbsolutePath();
            if (absolutePath.endsWith(".java")) {
                sourcePath.append(sourceFile.getAbsolutePath()).append(' ');
            } else {
                nonSourceFiles.add(sourceFile);
            }
        }

        var sourcePathOutput = new File(workDir, "recompile_sourcepath.txt");
        Util.ensureParent(sourcePathOutput);
        try (var os = new FileOutputStream(sourcePathOutput)) {
            os.write(sourcePath.deleteCharAt(sourcePath.lastIndexOf(" ")).toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Util.sneak(e);
        }

        var outputClasses = new File(workDir, "classes");
        Util.ensureParent(outputClasses);
        outputClasses.mkdir();
        var args = List.of(
            "-nowarn",
            "-d",
            outputClasses.getAbsolutePath(),
            "@" + sourcePathOutput.getAbsolutePath()
        );

        var process = ProcessUtils.runJavac(javaHome, workDir, new File(outputJar.getAbsolutePath() + ".log"), classpathFile, args);
        if (process.exitCode != 0) {
            Log.error("Javac failed to execute! Exit code " + process.exitCode);
            Log.error("--- BEGIN JAVAC LOG ---");
            process.lines.forEach(Log::error);
            Log.error("--- END JAVAC LOG ---");
            throw new RuntimeException("Javac failed to execute! Exit code " + process.exitCode);
        }

        try {
            return Util.makeJar(outputClasses, sourcesOutput, nonSourceFiles, outputJar);
        } finally {
            Util.sneakyDeleteOnExit(sourcesOutput);
            Util.sneakyDeleteOnExit(outputClasses);
        }
    }

    private static File makeClasspathFile(File workDir, String classpathArg) {
        var classpath = new File(workDir, "recompile_classpath.txt");
        Util.ensureParent(classpath);
        try (var os = new FileOutputStream(classpath)) {
            if (classpathArg.charAt(classpathArg.length() - 1) == File.pathSeparatorChar)
                os.write(classpathArg.substring(0, classpathArg.length() - 1).getBytes(StandardCharsets.UTF_8));
            else
                os.write(classpathArg.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Util.sneak(e);
        }

        return classpath;
    }

    private static String makeClasspathArg(List<File> classpath) {
        var classpathArg = new StringBuilder().append("-classpath ");

        for (var file : classpath) {
            classpathArg.append(file.getAbsolutePath()).append(File.pathSeparator);
        }

        return classpathArg.toString();
    }

    public static Result runJavac(File javaHome, File workDir, File logFile, File classpathFile, List<String> run) {
        Util.ensureParent(logFile);
        try (var log = new PrintWriter(new FileWriter(logFile), true)) {
            String classpath = "@" + classpathFile.getAbsolutePath();
            var launcher = new File(javaHome, "bin/javac" + OS.CURRENT.exe());
            Consumer<String> lines = line -> {
                Log.log(line);
                log.println(line);
            };
            lines.accept("Javac:     " + launcher.getAbsolutePath());
            lines.accept("Classpath: " + classpath);
            lines.accept("Work Dir:  " + workDir.getAbsolutePath());
            lines.accept("Arguments: " + run.stream().collect(Collectors.joining(", ", "'", "'")));
            log.println("====================================");

            var args = new ArrayList<String>();
            args.add(launcher.getAbsolutePath());
            args.addAll(run);
            args.add(classpath);

            var consoleLog = new ArrayList<String>();
            lines = line -> {
                consoleLog.add(line);
                log.println(line);
            };

            int ret = runCommand(workDir, lines, args.toArray(String[]::new));
            System.currentTimeMillis();
            System.out.println();

            log.flush();
            return new Result(consoleLog, ret);
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
        throw (E) t;
    }
}
