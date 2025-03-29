/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.data.OS;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Represents a downloadable Maven artifact.
 */
public class Artifact implements Comparable<Artifact>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern SEMI = Pattern.compile(":");

    // group:name:version[:classifier][@extension]
    private final String group;
    private final String name;
    private final String version;
    private final @Nullable String classifier;
    private final String ext;

    // Cached after building the first time we're asked
    // Transient field so these aren't serialized
    private transient @Nullable String folder;
    private transient @Nullable String path;
    private transient @Nullable String file;
    private transient @Nullable String fullDescriptor;
    private transient @Nullable ComparableVersion comparableVersion;
    private transient @Nullable Boolean isSnapshot;

    /**
     * Parses a descriptor into an artifact.
     *
     * @param descriptor The descriptor to parse
     * @return The created artifact
     *
     * @throws ArrayIndexOutOfBoundsException If the descriptor is invalid
     */
    public static Artifact from(String descriptor) {
        var parsed = ParsedDescriptor.of(descriptor);
        return new Artifact(parsed.group, parsed.name, parsed.version, parsed.classifier, parsed.ext);
    }

    /**
     * Creates an artifact from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @return The created artifact
     */
    public static Artifact from(String group, String name, String version) {
        return new Artifact(group, name, version, null, null);
    }

    /**
     * Creates an artifact from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @param classifier The classifier
     * @return The created artifact
     */
    public static Artifact from(String group, String name, String version, @Nullable String classifier) {
        return new Artifact(group, name, version, classifier, null);
    }

    /**
     * Creates an artifact from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @param classifier The classifier
     * @param ext        The file extension
     * @return The created artifact
     */
    public static Artifact from(String group, String name, String version, @Nullable String classifier, @Nullable String ext) {
        return new Artifact(group, name, version, classifier, ext);
    }

    /**
     * Parses a descriptor into an artifact with an OS.
     *
     * @param descriptor The descriptor to parse
     * @param os         The OS
     * @return The created artifact
     *
     * @throws ArrayIndexOutOfBoundsException If the descriptor is invalid
     */
    public static Artifact.WithOS from(String descriptor, @Nullable OS os) {
        var parsed = ParsedDescriptor.of(descriptor);
        return from(parsed.group, parsed.name, parsed.version, parsed.classifier, parsed.ext, os);
    }

    /**
     * Creates an artifact with OS from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @param os         The OS
     * @return The created artifact
     */
    public static Artifact.WithOS from(String group, String name, String version, OS os) {
        return from(group, name, version, null, null, os);
    }

    /**
     * Creates an artifact with OS from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @param classifier The classifier
     * @param os         The OS
     * @return The created artifact
     */
    public static Artifact.WithOS from(String group, String name, String version, @Nullable String classifier, OS os) {
        return from(group, name, version, classifier, null, os);
    }

    /**
     * Creates an artifact with OS from the given parts.
     *
     * @param group      The group
     * @param name       The name
     * @param version    The version
     * @param classifier The classifier
     * @param ext        The file extension
     * @param os         The OS
     * @return The created artifact
     */
    public static Artifact.WithOS from(String group, String name, String version, @Nullable String classifier, @Nullable String ext, OS os) {
        return new Artifact.WithOS(group, name, version, classifier, ext, os);
    }

    private Artifact(String group, String name, String version, @Nullable String classifier, @Nullable String ext) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.ext = ext != null ? ext : "jar";
    }

    /**
     * @return The path of this artifact, localized for the system using {@link File#separatorChar}.
     *
     * @see #getPath()
     */
    public String getLocalPath() {
        return getPath().replace('/', File.separatorChar);
    }

    /** @return The descriptor of this artifact */
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

    /** @return The path of this artifact */
    public String getPath() {
        if (path == null)
            this.path = this.getFolder() + '/' + getFilename();
        return path;
    }

    /** @return The folder of this artifact */
    public String getFolder() {
        if (this.folder == null)
            this.folder = this.group.replace('.', '/') + '/' + this.name + '/' + this.version;
        return this.folder;
    }

    /** @return The group of this artifact */
    public String getGroup() {
        return group;
    }

    /** @return The name of this artifact */
    public String getName() {
        return name;
    }

    /** @return The version of this artifact */
    public String getVersion() {
        return version;
    }

    /** @return The classifier of this artifact */
    public @Nullable String getClassifier() {
        return classifier;
    }

    /** @return The extension of this artifact (defaults to {@code jar}) */
    public String getExtension() {
        return ext;
    }

    /** @return The file name of this artifact */
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

    /** @return {@code true} if this artifact is a snapshot version */
    public boolean isSnapshot() {
        if (isSnapshot == null)
            this.isSnapshot = this.version.toLowerCase(Locale.ROOT).endsWith("-snapshot");
        return isSnapshot;
    }

    /**
     * Copies this artifact with a modified version.
     *
     * @param version The new version
     * @return The new artifact
     */
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
    public boolean equals(Object obj) {
        return obj instanceof Artifact o &&
            this.getDescriptor().equals(o.getDescriptor());
    }

    ComparableVersion getComparableVersion() {
        if (comparableVersion == null)
            this.comparableVersion = new ComparableVersion(this.version);
        return comparableVersion;
    }

    @Override
    public int compareTo(Artifact o) {
        return this.compare(this.getDescriptor(), o.getDescriptor());
    }

    private <S extends Comparable<S>> int compare(S a, S b) {
        if (a == null)
            return b == null ? 0 : -1;
        else if (b == null)
            return 1;
        return a.compareTo((S) b);
    }

    public static class WithOS extends Artifact {
        public final OS os;

        private WithOS(String group, String name, String version, @Nullable String classifier, @Nullable String ext, OS os) {
            super(group, name, version, classifier, ext);
            this.os = os;
        }
    }

    private record ParsedDescriptor(String group, String name, String version, @Nullable String classifier, @Nullable String ext) {
        private static ParsedDescriptor of(String descriptor) {
            String group, name, version;
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

            // TODO [MCMaven][Artifact] Handle non-version specification?
            //if (pts.length > 2)
            version = pts[2];

            if (pts.length > 3) // We have a classifier
                classifier = pts[3];

            return new ParsedDescriptor(group, name, version, classifier, ext);
        }
    }
}
