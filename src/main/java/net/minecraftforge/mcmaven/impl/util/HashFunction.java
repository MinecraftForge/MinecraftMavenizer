/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

// TODO [MCMaven][Documentation] Document
public enum HashFunction {
    MD5   ("md5",      32),
    SHA1  ("SHA-1",    40),
    SHA256("SHA-256",  64),
    SHA512("SHA-512", 128);

    private final String algo;
    private final String pad;
    private final String ext;
    private Boolean supported;

    private HashFunction(String algo, int length) {
        this.algo = algo;
        this.pad = String.format(Locale.ENGLISH, "%0" + length + "d", 0);
        this.ext = this.name().toLowerCase(Locale.ENGLISH);
    }

    public String extension() {
         return this.ext;
    }

    public static HashFunction find(String name) {
        String cleaned = name.toUpperCase(Locale.ENGLISH);
        for (HashFunction func : values()) {
            if (cleaned.equals(func.name()))
                return func;
        }

        return null;
    }

    public static HashFunction findByHash(String hash) {
        int len = hash.length();
        for (HashFunction func : values()) {
            if (func.pad.length() == len)
                return func;
        }

        return null;
    }

    public boolean supported() {
        if (supported == null) {
            try {
                MessageDigest.getInstance(algo);
                supported = true;
            } catch (NoSuchAlgorithmException e) {
                supported = false;
            }
        }
        return supported;
    }

    public MessageDigest get() {
        try {
            return MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String hash(File file) throws IOException {
        try (FileInputStream fin = new FileInputStream(file)) {
            return hash(fin);
        }
    }

    public String sneakyHash(File file) {
        try {
            return hash(file);
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    public String hash(File... files) throws IOException {
        return hash(Arrays.asList(files));
    }

    public String sneakyHash(File... files) {
        try {
            return hash(Arrays.asList(files));
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    public String hash(Iterable<File> files) throws IOException {
        MessageDigest hash = get();
        byte[] buf = new byte[1024];

        for (File file : files) {
            if (!file.exists())
                continue;

            try (FileInputStream fin = new FileInputStream(file)) {
                int count = -1;
                while ((count = fin.read(buf)) != -1)
                    hash.update(buf, 0, count);
            }
        }
        return pad(new BigInteger(1, hash.digest()).toString(16));
    }

    public String hash(String data) {
        return hash(data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8));
    }

    public String hash(InputStream stream) throws IOException {
        MessageDigest hash = get();
        byte[] buf = new byte[1024];
        int count = -1;
        while ((count = stream.read(buf)) != -1)
            hash.update(buf, 0, count);
        return pad(new BigInteger(1, hash.digest()).toString(16));
    }

    public String hash(byte[] data) {
        return pad(new BigInteger(1, get().digest(data)).toString(16));
    }

    public String pad(String hash) {
        return (pad + hash).substring(hash.length());
    }

    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return "";
        byte[] hexChars = new byte[bytes.length * 3 - 1];
        for (int j = 0, k = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[k++] = HEX_ARRAY[v >>> 4];
            hexChars[k++] = HEX_ARRAY[v & 0x0F];
            if (j < bytes.length - 1)
                hexChars[k++] = ' ';
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }
}
