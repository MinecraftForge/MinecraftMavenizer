/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaLocator;
import net.minecraftforge.java_provisioner.api.JavaProvisioner;
import net.minecraftforge.mcmaven.impl.Mavenizer;
import net.minecraftforge.util.logging.Logger;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

/** Represents the JDK cache for this tool. */
public final class JDKCache {
    private boolean attemptedLocate = false;
    private final List<Throwable> attemptedLocateErrors = new ArrayList<>();
    private final File root;
    private final Map<Integer, File> jdks = new HashMap<>();
    private final JavaProvisioner disco;

    /**
     * Initializes the JDK cache with the given cache directory.
     *
     * @param cache The cache directory
     */
    public JDKCache(File cache) {
        this.root = cache;
        this.disco = JavaLocator.disco(cache, Mavenizer.isOffline());
    }

    public File root() {
        return this.root;
    }

    // TODO: [MCMavenizer][JDKCache] Make this thread safe. If this method is accessed concurrently, the same JDK could be downloaded more than once.
    /**
     * Gets the JDK for the given version.
     *
     * @param version The version to get
     * @return The JDK, or {@code null} if it could not be found or downloaded
     */
    public File get(int version) throws Exception {
        if (!attemptedLocate)
            attemptLocate();

        // check cache. stop immediately if we get a hit.
        var ret = jdks.get(version);
        if (ret != null) return ret;

        try {
            ret = disco.provision(version).home(); // Implementation detail, we only download jdks, so no need to check here
        } catch (Exception e) {
            LOGGER.error("Failed to provision JDK " + version);
            e.printStackTrace(LOGGER.getLog(Logger.Level.ERROR));
            throw e;
        }

        // not sure how this would ever hit. but just in case...
        var old = jdks.putIfAbsent(version, ret);
        if (old != null) {
            LOGGER.error("JDKCache: Downloaded JDK " + version + " is replacing an existing download! It was probably downloaded by another thread.");
            LOGGER.error("JDKCache: Old JDK: " + old);
            // TODO [Mavenizer][Provisioner] Properly account for parallel Mavenizer instances
        }

        return ret;
    }

    private void attemptLocate() {
        if (attemptedLocate) return;
        attemptedLocate = true;

        List<JavaLocator> locators = new ArrayList<>();
        locators.add(JavaLocator.home());
        locators.add(JavaLocator.gradle());
        locators.add(this.disco);

        List<JavaInstall> installs = new ArrayList<>();

        for (JavaLocator locator : locators) {
            try {
                installs.addAll(locator.findAll());
            } catch (Exception e) {
                attemptedLocateErrors.add(e);
            }
        }

        // Remove duplicates
        var seen = new HashSet<File>();
        installs.removeIf(install -> !seen.add(install.home()));

        Collections.sort(installs);
        for (JavaInstall install : installs) {
            if (!install.isJdk() || install.majorVersion() <= 0)
                continue;
            this.jdks.putIfAbsent(install.majorVersion(), install.home());
        }
    }
}
