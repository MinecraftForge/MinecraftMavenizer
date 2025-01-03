/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import net.minecraftforge.srgutils.IMappingFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// TODO [MCMaven][Documentation] Document
public class Util {
    public static final long ZIPTIME = 628041600000L;
    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    public static ZipEntry getStableEntry(String name) {
        return getStableEntry(name, ZIPTIME);
    }

    public static ZipEntry getStableEntry(ZipEntry entry) {
        long time;

        var _default = TimeZone.getDefault();
        try {
            TimeZone.setDefault(GMT);
            time = entry.getTime();
        } finally {
            TimeZone.setDefault(_default);
        }

        return getStableEntry(entry.getName(), time);
    }

    public static ZipEntry getStableEntry(String name, long time) {
        var ret = new ZipEntry(name);

        var _default = TimeZone.getDefault();
        try {
            TimeZone.setDefault(GMT);
            ret.setTime(time);
        } finally {
            TimeZone.setDefault(_default);
        }

        return ret;
    }

    public static JarEntry getStableEntryJar(String name) {
        return getStableEntryJar(name, ZIPTIME);
    }

    public static JarEntry getStableEntryJar(JarEntry entry) {
        long time;

        var _default = TimeZone.getDefault();
        try {
            TimeZone.setDefault(GMT);
            time = entry.getTime();
        } finally {
            TimeZone.setDefault(_default);
        }

        return getStableEntryJar(entry.getName(), time);
    }

    public static JarEntry getStableEntryJar(String name, long time) {
        var ret = new JarEntry(name);

        var _default = TimeZone.getDefault();
        try {
            TimeZone.setDefault(GMT);
            ret.setTime(time);
        } finally {
            TimeZone.setDefault(_default);
        }

        return ret;
    }

    public static List<File> listFiles(File path) {
        return listFiles(path, new ArrayList<>());
    }

    private static List<File> listFiles(File dir, List<File> files) {
        if (!dir.exists())
            return files;

        if (!dir.isDirectory())
            throw new IllegalArgumentException("Path must be directory: " + dir.getAbsolutePath());

        for (var file : dir.listFiles()) {
            if (file.isDirectory())
                files = listFiles(file, files);
            else
                files.add(file);
        }

        return files;
    }

    public static void ensure(File path) {
        ensureParent(path);
        path.mkdir();
    }

    public static void ensureParent(File path) {
        var parent = path.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
    }

    public static void mergeJars(File output, boolean stripSignatures, File... files) throws IOException {
        mergeJars(output, stripSignatures, null, files);
    }

    public static void mergeJars(File output, boolean stripSignatures, BiFunction<File, String, Boolean> filter, File... files) throws IOException {
        if (output.exists())
            output.delete();
        ensureParent(output);

        var zips = new ArrayList<Closeable>();
        try {
            // Find all entries, first one wins. We also use a TreeMap so the output is sorted.
            // JarInputStream requires the manifest to be the first entry, followed by any signature
            // files, so special case them.
            Info manifest = null;
            var signatures = new TreeMap<String, Info>();
            var services = new TreeMap<String, ByteArrayOutputStream>();
            var entries = new TreeMap<String, Info>();

            for (var input : files) {
                var tmp = new ArrayList<Info>();

                if (input.isDirectory()) {
                    var prefix = input.getAbsolutePath();
                    for (var file : Util.listFiles(input)) {
                        var name = file.getAbsolutePath().substring(prefix.length()).replace('\\', '/');

                        if (filter != null && !filter.apply(input, name))
                            continue;

                        tmp.add(new Info(name, () -> {
                            try {
                                return new FileInputStream(file);
                            } catch (IOException e) {
                                return sneak(e);
                            }
                        }));
                    }
                } else {
                    var zip = new ZipFile(input);
                    zips.add(zip);
                    for (var itr = zip.entries(); itr.hasMoreElements(); ) {
                        var entry = itr.nextElement();
                        var name = entry.getName();

                        if (entry.isDirectory() || (filter != null && !filter.apply(input, name)))
                            continue;

                        tmp.add(new Info(name, () -> {
                            try {
                                return zip.getInputStream(entry);
                            } catch (IOException e) {
                                return sneak(e);
                            }
                        }));
                    }
                }

                for (var entry : tmp) {
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.name)) {
                        if (manifest == null)
                            manifest = entry;
                    // Technically services can be multi-release files, but I don't care. If this comes up in the MC world then i'll care
                    } else if (entry.name.startsWith("META-INF/services/")) {
                        var data = services.get(entry.name);
                        if (data == null) {
                            data = new ByteArrayOutputStream();
                            services.put(entry.name, data);
                        } else
                            data.write('\n');

                        try (var is = entry.stream().get()) {
                            is.transferTo(data);
                        }
                    } else if (isBlockOrSF(entry.name))
                        signatures.putIfAbsent(entry.name, entry);
                    else
                        entries.putIfAbsent(entry.name, entry);
                }
            }

            try (var zos = new ZipOutputStream(new FileOutputStream(output))) {
                if (manifest != null) {
                    if (stripSignatures)
                        writeStrippedManifest(zos, manifest);
                    else
                        writeEntry(zos, manifest);
                }
                if (!stripSignatures) {
                    for (var info : signatures.values())
                        writeEntry(zos, info);
                }
                for (var entry : services.entrySet()) {
                    zos.putNextEntry(Util.getStableEntry(entry.getKey()));
                    zos.write(entry.getValue().toByteArray());
                    zos.closeEntry();
                }
                for (var info : entries.values())
                    writeEntry(zos, info);
            }
        } finally {
            for (var zip : zips) {
                try {
                    zip.close();
                } catch (IOException e) {}
            }
        }
    }

    public static File makeJar(File classes, File sourcesDir, List<File> sources, File jar) {
        ensureParent(jar);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            var classFiles = listFiles(classes);
            classFiles.sort(Comparator.comparing(File::getAbsolutePath));
            makeJarInternal(classes, classFiles, jos);

            sources.sort(Comparator.comparing(File::getAbsolutePath));
            makeJarInternal(sourcesDir, sources, jos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return jar;
    }

    private static void makeJarInternal(File dir, List<File> classes, JarOutputStream jos) throws IOException {
        for (var file : classes) {
            String entryName = file.getAbsoluteFile().toString().substring(dir.getAbsolutePath().length() + 1).replace('\\', '/');
            writeEntry(jos, new Info(entryName, () -> {
                try {
                    return new FileInputStream(file);
                } catch (IOException e) {
                    return sneak(e);
                }
            }));
        }
    }

    private record Info(String name, Supplier<InputStream> stream) {}

    /** @see <a href="https://github.com/MinecraftForge/ForgeGradle/blob/efa70580314c88192486e4ab089f4b970d5b080c/src/common/java/net/minecraftforge/gradle/common/util/MinecraftRepo.java#L323-L327">MinecraftRepo.splitJar(...) in ForgeGradle 6</a> */
    public static void splitJar(File raw, File mappings, File output, boolean slim, boolean stable) throws IOException {
        try (FileInputStream input = new FileInputStream(mappings)) {
            splitJar(raw, input, output, slim, stable);
        }
    }

    /** @see <a href="https://github.com/MinecraftForge/ForgeGradle/blob/efa70580314c88192486e4ab089f4b970d5b080c/src/common/java/net/minecraftforge/gradle/common/util/MinecraftRepo.java#L329-L364">MinecraftRepo.splitJar(...) in ForgeGradle 6</a> */
    public static void splitJar(File raw, InputStream mappings, File output, boolean slim, boolean stable) throws IOException {
        try (ZipFile zin = new ZipFile(raw);
             FileOutputStream fos = new FileOutputStream(output);
             ZipOutputStream out = new ZipOutputStream(fos)) {

            Set<String> whitelist = IMappingFile.load(mappings).getClasses().stream()
                                                .map(IMappingFile.IClass::getOriginal)
                                                .collect(Collectors.toSet());

            for (Enumeration<? extends ZipEntry> entries = zin.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    boolean isNotch = whitelist.contains(name.substring(0, name.length() - 6 /*.class*/));
                    if (slim == isNotch) {
                        ZipEntry _new = getStableEntry(name, stable ? ZIPTIME : 0);
                        out.putNextEntry(_new);
                        try (InputStream ein = zin.getInputStream(entry)) {
                            IOUtils.copy(ein, out);
                        }
                        out.closeEntry();
                    }
                } else {
                    if (!slim) {
                        ZipEntry _new = getStableEntry(name, stable ? ZIPTIME : 0);
                        out.putNextEntry(_new);
                        try (InputStream ein = zin.getInputStream(entry)) {
                            IOUtils.copy(ein, out);
                        }
                        out.closeEntry();
                    }
                }
            }
        }
//        updateHash(output);
    }

    private static void writeEntry(ZipOutputStream zos, Info file) throws IOException {
        zos.putNextEntry(Util.getStableEntry(file.name()));
        file.stream.get().transferTo(zos);
        zos.closeEntry();
    }

    private static void writeStrippedManifest(ZipOutputStream zos, Info file) throws IOException {
        try (var is = file.stream().get()) {
            var manifest = new Manifest(is);
            for (var itr = manifest.getEntries().entrySet().iterator(); itr.hasNext();) {
                var section = itr.next();
                for (var sItr = section.getValue().entrySet().iterator(); sItr.hasNext();) {
                    var attribute = sItr.next();
                    var key = attribute.getKey().toString().toLowerCase(Locale.ROOT);
                    if (key.endsWith("-digest"))
                        sItr.remove();
                }

                if (section.getValue().isEmpty())
                    itr.remove();
            }
            zos.putNextEntry(Util.getStableEntry(file.name));
            manifest.write(zos);
            zos.closeEntry();
        }
    }

    /** @see sun.security.util.SignatureFileVerifier#isBlockOrSF(String) */
    @SuppressWarnings("JavadocReference")
    public static boolean isBlockOrSF(String path) {
        var s = path.toUpperCase(Locale.ENGLISH);
        if (!s.startsWith("META-INF/")) return false;
        // Copy of sun.security.util.SignatureFileVerifier.isBlockOrSF(String), hasn't really ever changed, but just in case
        return s.endsWith(".SF")
            || s.endsWith(".DSA")
            || s.endsWith(".RSA")
            || s.endsWith(".EC");
    }

    /**
     * Gets the Minecraft directory used by Minecraft Launcher. This is the directory where the launcher stores
     * libraries, which we can use instead of downloading them again.
     *
     * @return The Minecraft directory
     */
    public static File getMCDir() {
        var userHomeDir = System.getProperty("user.home", ".");
        var mcDir = ".minecraft";
        if (OS.CURRENT == OS.WINDOWS && System.getenv("APPDATA") != null)
            return new File(System.getenv("APPDATA"), mcDir);
        else if (OS.CURRENT == OS.MACOS)
            return new File(userHomeDir, "Library/Application Support/minecraft");
        return new File(userHomeDir, mcDir);
    }

    /**
     * Gets the hashes of the given file using the given hash functions.
     *
     * @param file      The file to calculate hashes for
     * @param functions The hash functions to use
     * @return The hashes of the file
     *
     * @throws IOException If an error occurs while reading the file
     */
    public static String[] bulkHash(File file, HashFunction... functions) throws IOException {
        if (file == null || !file.exists())
            return null;

        var digests = new MessageDigest[functions.length];
        for (int x = 0; x < functions.length; x++)
            digests[x] = functions[x].get();

        var buf = new byte[1024];
        int count = -1;
        try (var stream = new FileInputStream(file)) {
            while ((count = stream.read(buf)) != -1) {
                for (var digest : digests)
                    digest.update(buf, 0, count);
            }
        }

        var ret = new String[functions.length];
        for (int x = 0; x < functions.length; x++) {
            var func = functions[x];
            var digest = digests[x];
            ret[x] = func.pad(new BigInteger(1, digest.digest()).toString(16));
        }
        return ret;
    }

    /**
     * Updates the hash of the given file using all hash functions. These hashes are stored as files with the same name
     * but different extension at the folder of the target.
     *
     * @param target The file to update the hash of
     * @throws IOException If an error occurs while reading the file
     * @see #updateHash(File, HashFunction...)
     */
    public static void updateHash(File target) throws IOException {
        updateHash(target, HashFunction.values());
    }

    /**
     * Updates the hash of the given file the given hash functions. These hashes are stored as files with the same name
     * but different extension at the folder of the target.
     *
     * @param target The file to update the hash of
     * @throws IOException If an error occurs while reading the file
     */
    public static void updateHash(File target, HashFunction... functions) throws IOException {
        if (!target.exists()) {
            for (var func : functions) {
                var cache = new File(target.getAbsolutePath() + "." + func.extension());
                cache.delete();
            }
        } else {
            var hashes = bulkHash(target, functions);
            for (int x = 0; x < functions.length; x++) {
                var func = functions[x];
                var cache = new File(target.getAbsolutePath() + "." + func.extension());
                Files.write(cache.toPath(), hashes[x].getBytes());
            }
        }
    }

    /**
     * Sneakily deletes a file or directory on JVM exit using {@link FileUtils#forceDeleteOnExit(File)} and
     * {@link #sneak(Throwable)}.
     *
     * @param file The file to delete
     */
    public static void sneakyDeleteOnExit(File file) {
        try {
            FileUtils.forceDeleteOnExit(file);
        } catch (IOException e) {
            // TODO Handle this exception instead of throw?
            Util.sneak(e);
        }
    }

    /**
     * Allows the given {@link Throwable} to be thrown without needing to declare it in the method signature or
     * arbitrarily checked at compile time.
     *
     * @param t   The throwable
     * @param <R> The type of the fake return if used as a return statement
     * @param <E> The type of the throwable
     * @throws E Unconditionally thrown
     */
    @SuppressWarnings("unchecked")
    public static <R, E extends Throwable> R sneak(Throwable t) throws E {
        throw (E)t;
    }

    /**
     * Mimic's Mojang's {@code Util.make(...)} in Minecraft. This is a utility method that allows you to modify an input
     * object in-line with the given action.
     *
     * @param obj    The object to modify
     * @param action The action to apply to the object
     * @param <T>    The type of the object
     * @return The object
     */
    public static <T> T make(T obj, Consumer<T> action) {
        action.accept(obj);
        return obj;
    }

    /**
     * Mimic's Mojang's {@code Util.make(...)} in Minecraft. This is a utility method that allows you to modify an input
     * object in-line with the given action. This version of the method allows you to return a new object instead of the
     * original, which can be useful for in-line {@link String} modifications that would otherwise cause variable
     * re-assignment (bad if you want to use them as lambda parameters).
     *
     * @param obj    The object to modify
     * @param action The action to apply to the object
     * @param <T>    The type of the object
     * @return The object
     */
    public static <T> T make(T obj, Function<T, T> action) {
        return action.apply(obj);
    }
}
