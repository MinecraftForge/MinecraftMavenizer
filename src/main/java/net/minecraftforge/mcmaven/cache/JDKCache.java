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

import net.minecraftforge.jver.api.IJavaInstall;
import net.minecraftforge.jver.api.IJavaLocator;

public class JDKCache {
    private boolean attemptedLocate = false;
    private final File FAILED_MARKER = new File("FAILED TO DOWNLOAD FROM DISCO");
    private final Map<Integer, File> jdks = new HashMap<>();
    private final IJavaLocator disco;

    public JDKCache(File cache) {
        this.disco = IJavaLocator.disco(cache);
    }

    public File get(int version) {
        if (!attemptedLocate)
            attempLocate();

        File ret = jdks.get(version);
        if (ret != null)
            return ret == FAILED_MARKER ? null : ret;

        IJavaInstall downloaded = disco.provision(version); // Implementation detail, we only download jdks, so no need to check here
        ret = downloaded == null ? FAILED_MARKER : downloaded.home();
        ret = jdks.putIfAbsent(version, ret);
        return ret == FAILED_MARKER ? null : ret;
    }

    private void attempLocate() {
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
