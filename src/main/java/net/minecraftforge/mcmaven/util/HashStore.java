/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// TODO [MCMaven][Documentation] Document
public class HashStore {
    private final HashFunction HASH = HashFunction.SHA1;

    private final String root;
    private final Map<String, String> oldHashes = new HashMap<>();
    private final Map<String, String> newHashes = new HashMap<>();
    private File target;

    public static HashStore fromFile(File path) {
        var parent = path.getAbsoluteFile().getParentFile();
        return new HashStore(parent)
            .load(new File(parent, path.getName() + ".cache"));
    }

    public static HashStore fromDir(File path) {
        return new HashStore(path)
            .load(new File(path, ".cache"));
    }

    public HashStore() {
        this.root = "";
    }

    public HashStore(File root) {
        this.root = root.getAbsolutePath();
    }

    public boolean areSame(File... files) {
        for (var file : files) {
            if (!isSame(file))
                return false;
        }
        return true;
    }

    public boolean areSame(Iterable<File> files) {
        for (var file : files) {
            if (!isSame(file))
                return false;
        }
        return true;
    }

    public boolean isSame(File file) {
        try {
            var path = getPath(file);
            var hash = oldHashes.get(path);
            if (hash == null) {
                if (file.exists()) {
                    newHashes.put(path, HASH.hash(file));
                    return false;
                }
                return true;
            }

            var fileHash = HASH.hash(file);
            newHashes.put(path, fileHash);
            return fileHash.equals(hash);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public HashStore load(File file) {
        this.target = file;
        oldHashes.clear();
        if (!file.exists())
            return this;

        try {
            for (var line : Files.readAllLines(file.toPath())) {
                var split = line.split("=");
                oldHashes.put(split[0], split[1]);
            }
        } catch (IOException e) {
            Log.error("Failed to read cache file: " + e.getMessage());
        }

        return this;
    }

    public boolean exists() {
        return this.target != null && this.target.exists();
    }

    public HashStore add(String key, String data) {
        newHashes.put(key, HASH.hash(data));
        return this;
    }

    public HashStore add(String key, byte[] data) {
        newHashes.put(key, HASH.hash(data));
        return this;
    }

    public HashStore add(String key, File file) {
        try {
            if (key == null)
                key = getPath(file);

            if (file.isDirectory()) {
                var prefix = getPath(file);
                for (var f : Util.listFiles(file)) {
                    var suffix = getPath(f).substring(prefix.length());
                    newHashes.put(key + " - " + suffix, HASH.hash(f));
                }
            } else {
                newHashes.put(key, HASH.hash(file));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public HashStore add(File... files) {
        for (var file : files)
            add(null, file);
        return this;
    }

    public HashStore add(Iterable<File> files) {
        for (var file : files)
            add(null, file);
        return this;
    }

    public HashStore add(File file) {
        add(null, file);
        return this;
    }

    public boolean isSame() {
        return oldHashes.equals(newHashes);
    }

    public void save() {
        if (target == null)
            throw new RuntimeException("HashStore.save() called without load(File) so we dont know where to save it! Use load(File) or save(File)");
        save(target);
    }

    public void save(File file) {
        var buf = new StringBuilder();
        var keys = new ArrayList<String>(newHashes.keySet());
        Collections.sort(keys);

        for (var key : keys)
            buf.append(key).append('=').append(newHashes.get(key)).append('\n');

        try {
            Files.write(file.toPath(), buf.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            sneak(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneak(Throwable t) throws E {
        throw (E)t;
    }

    private String getPath(File file) {
        var path = file.getAbsolutePath();

        if (path.startsWith(root))
            path = path.substring(root.length());

        path = path.replace('\\', '/');

        if (file.isDirectory() && !path.endsWith("/"))
            path += '/';

        return path;
    }
}
