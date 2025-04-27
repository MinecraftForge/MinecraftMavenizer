/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.cache;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.hash.HashUtils;
import net.minecraftforge.util.logging.Log;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// TODO: [MCMaven][MavenCache] Handle download failures properly
/** Represents the maven cache for this tool. */
public sealed class MavenCache permits MinecraftMavenCache {
    private static final HashFunction[] DEFAULT_HASHES = {
        // can't use SHA256/512 as gradle doesn't always update those files. Depending on version used to publish
        //HashFunction.SHA256,
        HashFunction.SHA1
        //HashFunction.MD5
    };

    private final HashFunction[] knownHashes;
    private final File cache;
    private final String repo;

    /**
     * Initializes a new maven cache with the given name, repository, and cache directory.
     *
     * @param name The name
     * @param repo The repo
     * @param root The cache directory
     */
    public MavenCache(String name, String repo, File root) {
        this(name, repo, root, DEFAULT_HASHES);
    }

    public MavenCache(String name, String repo, File root, HashFunction... knownHashes) {
        this.cache = new File(root, "maven/" + name);
        this.repo = repo;
        this.knownHashes = knownHashes;
    }

    public final File getFolder() {
        return this.cache;
    }

    /**
     * Downloads a maven artifact.
     *
     * @param artifact The artifact
     * @return The downloaded artifact
     *
     * @throws IOException If an error occurs while downloading the file
     */
    @SuppressWarnings("JavadocDeclaration") // IOException thrown by Util.sneak
    public File download(Artifact artifact) {
        return download(false, artifact.getPath());
    }

    /**
     * Downloads the maven metadata for an artifact.
     *
     * @param artifact The artifact
     * @return The downloaded maven metadata
     *
     * @throws IOException If an error occurs while downloading the file
     * @see #downloadVersionMeta(Artifact)
     */
    @SuppressWarnings("JavadocDeclaration") // IOException thrown by Util.sneak
    public final File downloadMeta(Artifact artifact) {
        return download(true, artifact.getGroup().replace('.', '/') + '/' + artifact.getName() + "/maven-metadata.xml");
    }

    /**
     * Downloads the maven metadata for an artifact and its version.
     *
     * @param artifact The artifact
     * @return The downloaded maven metadata
     *
     * @throws IOException If an error occurs while downloading the file
     */
    @SuppressWarnings("JavadocDeclaration") // IOException thrown by Util.sneak
    public final File downloadVersionMeta(Artifact artifact) {
        return download(true, artifact.getGroup().replace('.', '/') + '/' + artifact.getName() + '/' + artifact.getVersion() + "/maven-metadata.xml");
    }

    /**
     * Downloads a maven file.
     *
     * @param changing If we should ignore the cache
     * @param path     The path of the file to download
     * @return The downloaded file
     *
     * @throws IOException If an error occurs while downloading the file
     */
    @SuppressWarnings("JavadocDeclaration") // IOException thrown by Util.sneak
    protected File download(boolean changing, String path) {
        var target = new File(cache, path);

        if (target.exists()) {
            boolean invalidHash = false;

            // TODO [MCMaven][Cache] Double check hashes of existing files
            // maybe set it so we only do this for files from forge maven?
            /* check if existing files don't match the hash for the file, this would happen if something corrupted the file
             * But honestly just a waste of time.
            var existingTypes = new ArrayList<HashFunction>();
            var existing = new ArrayList<String>();
            for (var func : known_hashes) {
                var hfile = new File(target.getAbsolutePath() + '.' + func.extension());
                if (hfile.exists()) {
                    try {
                        existingTypes.add(func);
                        existing.add(Files.readAllLines(hfile.toPath()).get(0));
                    } catch (IOException e) {
                        throw new RuntimeException("Could not download " + repo + path + ", Error reading cached file", e);
                    }
                }
            }

            try {
                var computed = Util.bulkHash(target, existingTypes.toArray(HashFunction[]::new));
                for (int x = 0; x < computed.length; x++) {
                    var fhash = existing.get(x);
                    var chash = computed[x];
                    if (!fhash.equals(chash)) {
                        log("Corrupt file on disc: " + target.getAbsolutePath());
                        log("Expected: " + fhash);
                        log("Actual:   " + chash);
                        invalidHash = true;
                        break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read " + target.getAbsolutePath(), e);
            }
             */

            if (!invalidHash && changing) {
                for (var func : knownHashes) {
                    if (GlobalOptions.isOffline()) continue;

                    var rhash = DownloadUtils.tryDownloadString(true, repo + path + '.' + func.extension());
                    if (rhash == null)
                        continue;

                    try {
                        var chash = func.hash(target);
                        if (!chash.equals(rhash)) {
                            Log.error("Outdated cached file: " + target.getAbsolutePath());
                            Log.error("Expected: " + rhash);
                            Log.error("Actual:   " + chash);
                            invalidHash = true;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Could not download " + repo + path + ", Error reading cached file", e);
                    }

                    // Only care about the first hash the server returns, be it valid or not
                    break;
                }
            }

            if (!invalidHash)
                return target;

            GlobalOptions.assertNotCacheOnly();
            target.delete();
        }

        try {
            GlobalOptions.assertNotCacheOnly();
            GlobalOptions.assertOnline();
            downloadFile(target, path);
            HashUtils.updateHash(target, knownHashes);
            return target;
        } catch (IOException e) {
            return Util.sneak(e);
        }
    }

    /**
     * Downloads a maven file.
     *
     * @param target The file to download to
     * @param path   The path of the file to download
     * @throws IOException If an error occurs while downloading the file
     */
    protected void downloadFile(File target, String path) throws IOException {
        // TODO Currently there is no handling if the download fails. For now, I'm throwing the exception.
        DownloadUtils.downloadFile(target, this.repo + path);
    }

    /**
     * @param artifact The artifact
     * @return All the available versions of the artifact
     */
    public List<String> getVersions(Artifact artifact) {
        File meta = downloadMeta(artifact);
        try (InputStream input = new FileInputStream(meta)) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            NodeList lst = doc.getElementsByTagName("version");
            List<String> ret = new ArrayList<>();
            for (int x = 0; x < lst.getLength(); x++)
                ret.add(lst.item(x).getTextContent());
            return ret;
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new RuntimeException("Failed to parse " + meta.getAbsolutePath(), e);
        }
    }
}
