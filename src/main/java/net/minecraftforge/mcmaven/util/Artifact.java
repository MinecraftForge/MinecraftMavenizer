/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

import java.io.File;
import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

public class Artifact implements Comparable<Artifact>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern SEMI = Pattern.compile(":");

    // group:name:version[:classifier][@extension]
    private final String group;
    private final String name;
    private final String version;
    private final String classifier;
    private final String ext;

    // Cached after building the first time we're asked
    // Transient field so these aren't serialized
    private transient String folder;
    private transient String path;
    private transient String file;
    private transient String fullDescriptor;
    private transient ComparableVersion comparableVersion;
    private transient Boolean isSnapshot;

    public static Artifact from(String descriptor) {
        String group, name, version = null;
        String ext = null, classifier = null;

        String[] pts = SEMI.split(descriptor);
        group = pts[0];
        name = pts[1];

        int last = pts.length - 1;
        int idx = pts[last].indexOf('@');
        if (idx != -1) { // we have an extension
            ext = pts[last].substring(idx + 1);
            pts[last] = pts[last].substring(0, idx);
        }

        if (pts.length > 2)
            version = pts[2];

        if (pts.length > 3) // We have a classifier
            classifier = pts[3];

        return new Artifact(group, name, version, classifier, ext);
    }

    public static Artifact from(String group, String name, String version, String classifier, String ext) {
        return new Artifact(group, name, version, classifier, ext);
    }

    private Artifact(String group, String name, String version, String classifier, String ext) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.ext = ext != null ? ext : "jar";
    }

    public String getLocalPath() {
        return getPath().replace('/', File.separatorChar);
    }

    public String getDescriptor() {
        if (fullDescriptor == null) {
            StringBuilder buf = new StringBuilder();
            buf.append(this.group).append(':').append(this.name).append(':').append(this.version);
            if (this.classifier != null)
                buf.append(':').append(this.classifier);
            if (ext != null && !"jar".equals(this.ext))
                buf.append('@').append(this.ext);
            this.fullDescriptor = buf.toString();
        }
        return fullDescriptor;
    }

    public String getPath() {
        if (path == null)
            this.path = this.getFolder() + '/' + getFilename();
        return path;
    }

    public String getFolder() {
        if (this.folder == null)
            this.folder = this.group.replace('.', '/') + '/' + this.name + '/' + this.version;
        return this.folder;
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getExtension() {
        return ext;
    }

    public String getFilename() {
        if (file == null) {
            String file;
            file = this.name + '-' + this.version;
            if (this.classifier != null) file += '-' + this.classifier;
            file += '.' + this.ext;
            this.file = file;
        }
        return file;
    }

    public boolean isSnapshot() {
        if (isSnapshot == null)
            this.isSnapshot = this.version.toLowerCase(Locale.ROOT).endsWith("-snapshot");
        return isSnapshot;
    }

    public Artifact withVersion(String version) {
        return Artifact.from(group, name, version, classifier, ext);
    }

    @Override
    public String toString() {
        return getDescriptor();
    }

    @Override
    public int hashCode() {
        return getDescriptor().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Artifact &&
                this.getDescriptor().equals(((Artifact)o).getDescriptor());
    }

    ComparableVersion getComparableVersion() {
        if (comparableVersion == null)
            this.comparableVersion = new ComparableVersion(this.version);
        return comparableVersion;
    }

    @Override
    public int compareTo(Artifact o) {
        int ret = compare(group, o.group);
        if (ret == 0)
            ret = compare(name, o.name);
        if (ret == 0)
            ret = compare(getComparableVersion(), o.getComparableVersion());
        if (ret == 0)
            ret = compare(classifier, o.classifier);
        if (ret == 0)
            ret = compare(ext, o.ext);
        return ret;
    }

    private <S extends Comparable<S>> int compare(S a, S b) {
        if (a == null)
            return b == null ? 0 : -1;
        else if (b == null)
            return 1;
        return a.compareTo((S)b);
    }
}
