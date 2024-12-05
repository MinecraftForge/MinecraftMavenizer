package net.minecraftforge.mcmaven.cache;

import net.minecraftforge.mcmaven.util.Artifact;
import net.minecraftforge.mcmaven.util.DownloadUtils;
import net.minecraftforge.mcmaven.util.HashFunction;
import net.minecraftforge.mcmaven.util.Log;
import net.minecraftforge.mcmaven.util.Util;
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
public class MavenCache {
    protected HashFunction[] known_hashes = new HashFunction[] {
        // can't use SHA256/512 as gradle doesn't always update those files. Depending on version used to publish
        // HashFunction.SHA256,
        HashFunction.SHA1
        //HashFunction.MD5
    };
    private final File cache;
    private final String name;
    private final String repo;

    /**
     * Initializes a new maven cache with the given name, repository, and cache directory.
     *
     * @param name The name
     * @param repo The repo
     * @param root The cache directory
     */
    public MavenCache(String name, String repo, File root) {
        this.name = name;
        this.cache = new File(root, "maven");
        this.repo = repo;
    }

    private void log(String message) {
        Log.log(message);
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
    public File downloadMeta(Artifact artifact) {
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
    public File downloadVersionMeta(Artifact artifact) {
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
                for (var func : known_hashes) {
                    String rhash = DownloadUtils.downloadString(repo + path + '.' + func.extension());
                    if (rhash == null)
                        continue;

                    try {
                        String chash = func.hash(target);
                        if (!chash.equals(rhash)) {
                            log("Outdated cached file: " + target.getAbsolutePath());
                            log("Expected: " + rhash);
                            log("Actual:   " + chash);
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

            target.delete();
        }

        try {
            downloadFile(target, path);
            Util.updateHash(target, known_hashes);
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
        var ret = DownloadUtils.downloadFile(target, this.repo + path);
        if (!ret)
            throw new IOException("Failed to download " + this.repo + path);
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
