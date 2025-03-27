/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.hash.HashFunction;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.function.Consumer;
import java.util.function.Function;

// TODO [MCMaven][Documentation] Document
public class Util {
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
