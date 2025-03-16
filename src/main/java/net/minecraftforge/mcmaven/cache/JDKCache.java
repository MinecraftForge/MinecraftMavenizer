/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.java_version.api.IJavaInstall;
import net.minecraftforge.java_version.api.IJavaLocator;
import net.minecraftforge.mcmaven.util.Log;
import org.jetbrains.annotations.Nullable;

/** Represents the JDK cache for this tool. */
public class JDKCache {
    private boolean attemptedLocate = false;
    private final Map<Integer, File> jdks = new HashMap<>();
    private final IJavaLocator disco;

    /**
     * Initializes the JDK cache with the given cache directory.
     *
     * @param cache The cache directory
     */
    public JDKCache(File cache) {
        this.disco = IJavaLocator.disco(cache);
    }

    // TODO: [MCMaven][JDKCache] Make this thread safe. If this method is accessed concurrently, the same JDK could be downloaded more than once.
    /**
     * Gets the JDK for the given version.
     *
     * @param version The version to get
     * @return The JDK, or {@code null} if it could not be found or downloaded
     */
    public @Nullable File get(int version) {
        if (!attemptedLocate)
            attemptLocate();

        // check cache. stop immediately if we get a hit.
        File ret = jdks.get(version);
        if (ret != null) return ret;

        IJavaInstall downloaded = disco.provision(version); // Implementation detail, we only download jdks, so no need to check here
        if (downloaded == null) return null;
        ret = downloaded.home();

        // not sure how this would ever hit. but just in case...
        var old = jdks.putIfAbsent(version, ret);
        if (old != null) {
            Log.error("JDKCache: Downloaded JDK " + version + " is replacing an existing download! It was probably downloaded by another thread.");
            Log.error("JDKCache: Old JDK: " + old);
            // TODO Throw exception here
        }

        return ret;
    }

    private void attemptLocate() {
        if (attemptedLocate)
            return;
        attemptedLocate = true;

        List<IJavaLocator> locators = new ArrayList<>();
        locators.add(IJavaLocator.home());
        locators.add(IJavaLocator.gradle());
        locators.add(this.disco);

        List<IJavaInstall> installs = new ArrayList<>();

        for (IJavaLocator locator : locators) {
            List<IJavaInstall> found = locator.findAll();
            installs.addAll(found);
        }

        // Remove duplicates
        Set<File> seen = new HashSet<File>();
        for (Iterator<IJavaInstall> itr = installs.iterator(); itr.hasNext(); ) {
            IJavaInstall install = itr.next();
            if (!seen.add(install.home()))
                itr.remove();
        }

        Collections.sort(installs);
        for (IJavaInstall install : installs) {
            if (!install.isJdk() || install.majorVersion() <= 0)
                continue;
            this.jdks.putIfAbsent(install.majorVersion(), install.home());
        }
    }
}
