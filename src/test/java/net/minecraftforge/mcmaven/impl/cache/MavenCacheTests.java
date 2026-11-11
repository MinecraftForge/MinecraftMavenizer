/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.mcmaven.impl.util.Artifact;

class MavenCacheTests {
    @TempDir
    Path temp;

    @Test
    void rejectsIncompleteDownloadsAndRetriesOnNextRequest() throws Exception {
        var contents = createJar();
        var downloads = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var response = downloads.getAndIncrement() == 0
                ? Arrays.copyOf(contents, contents.length - 20)
                : contents;
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            var artifact = Artifact.from("net.minecraftforge:mergetool:0.2.3.5:fatjar");
            var root = this.temp.toFile();
            var cached = new File(new File(root, "maven/test"), artifact.getPath());
            Files.createDirectories(cached.toPath().getParent());

            var cache = new MavenCache("test", "http://127.0.0.1:" + server.getAddress().getPort() + '/', root);
            assertThrows(java.io.IOException.class, () -> cache.download(artifact));
            assertEquals(1, downloads.get());
            org.junit.jupiter.api.Assertions.assertFalse(cached.exists());

            var downloaded = cache.download(artifact);

            assertEquals(2, downloads.get());
            assertArrayEquals(contents, Files.readAllBytes(downloaded.toPath()));
            try (var jar = new java.util.jar.JarFile(downloaded)) {
                assertNotNull(jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
            }
        } finally {
            server.stop(0);
        }
    }

    private static byte[] createJar() throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("example/Main.class"));
            jar.write(new byte[]{0});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
