/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.data.OS;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Represents a downloadable Maven artifact.
 */
public class Artifact implements Comparable<Artifact>, Serializable {
    private static final @Serial long serialVersionUID = 1L;
    private static final Pattern SEMI = Pattern.compile(":");
    private static final ComparableVersion MISSING_VERSION = new ComparableVersion("0");

    // group:name:version[:classifier][@extension]
    private final String group, name;
    private final @Nullable String version;
    private final @Nullable String classifier;
    private final String ext;
    private final OS os;
    private final Arch arch;

    // Cached after building the first time we're asked
    // Transient field so these aren't serialized
    private transient @Nullable String folder, path, file;
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
        return new Artifact(descriptor);
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
        return new Artifact(group, name, version, null, null, OS.UNKNOWN, Arch.UNKNOWN);
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
        return new Artifact(group, name, version, classifier, null, OS.UNKNOWN, Arch.UNKNOWN);
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
        return new Artifact(group, name, version, classifier, ext, OS.UNKNOWN, Arch.UNKNOWN);
    }

    private Artifact(String descriptor) {
        String[] pts = SEMI.split(descriptor);
        this.group = pts[0];
        this.name = pts[1];

        int last = pts.length - 1;
        int idx = pts[last].indexOf('@');
        if (idx != -1) {
            ext = pts[last].substring(idx + 1);
            pts[last] = pts[last].substring(0, idx);
        } else {
            ext = "jar";
        }

        this.version = pts.length > 2 ? pts[2] : null;
        this.classifier = pts.length > 3 ? pts[3] : null;
        this.os = this.classifier != null ? findOS(this.classifier) : OS.UNKNOWN;
        this.arch = this.classifier != null ? findArch(this.classifier) : Arch.UNKNOWN;
    }

    private Artifact(String group, String name, String version, @Nullable String classifier, @Nullable String ext, OS os, Arch arch) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.ext = Objects.requireNonNullElse(ext, "jar");
        this.os = os == OS.UNKNOWN && classifier != null ? findOS(classifier) : os;
        this.arch = arch == Arch.UNKNOWN && classifier != null ? findArch(classifier) : arch;
    }

    private static OS findOS(String classifier) {
        for (var s : classifier.split("-")) {
            if (s.isBlank()) continue;

            var osCandidate = OS.byName(s);
            if (osCandidate != OS.UNKNOWN) {
                return osCandidate;
            }
        }

        return OS.UNKNOWN;
    }

    private static Arch findArch(String classifier) {
        for (var s : classifier.split("-")) {
            if (s.isBlank()) continue;

            var archCandidate = Arch.byName(s);
            if (archCandidate != Arch.UNKNOWN) {
                return archCandidate;
            }
        }

        return Arch.UNKNOWN;
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
            buf.append(this.group).append(':').append(this.name);
            if (this.version != null) {
                buf.append(':').append(this.version);
                if (this.classifier != null)
                    buf.append(':').append(this.classifier);
            }
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
        if (this.folder == null) {
            if (this.version == null)
                this.folder = this.group.replace('.', '/') + '/' + this.name;
            else
                this.folder = this.group.replace('.', '/') + '/' + this.name + '/' + this.version;
        }
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
    public @Nullable String getVersion() {
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

    /** @return The os of this artifact (defaults to {@link OS#UNKNOWN}) */
    public OS getOs() {
        return os;
    }

    /** @return The arch of this artifact (defaults to {@link Arch#UNKNOWN}) */
    public Arch getArch() {
        return arch;
    }

    /** @return The file name of this artifact */
    public String getFilename() {
        if (file == null) {
            var file = new StringBuilder();
            file.append(this.name);
            if (this.version != null) {
                file.append('-').append(this.version);
                if (this.classifier != null)
                    file.append('-').append(this.classifier);
            }
            file.append('.').append(this.ext);
            this.file = file.toString();
        }
        return file;
    }

    /** @return {@code true} if this artifact is a snapshot version */
    public boolean isSnapshot() {
        if (isSnapshot == null)
            this.isSnapshot = this.version != null && this.version.toLowerCase(Locale.ROOT).endsWith("-snapshot");
        return isSnapshot;
    }

    /**
     * Copies this artifact with a modified version.
     *
     * @param version The new version
     * @return The new artifact
     */
    public Artifact withVersion(String version) {
        return new Artifact(group, name, version, classifier, ext, os, arch);
    }

    public Artifact withClassifier(String classifier) {
        return new Artifact(group, name, version, classifier, ext, os, arch);
    }

    public Artifact withExtension(String ext) {
        return new Artifact(group, name, version, classifier, ext, os, arch);
    }

    public Artifact withOS(OS os) {
        return new Artifact(group, name, version, classifier, ext, os, arch);
    }

    public Artifact withArch(Arch arch) {
        return new Artifact(group, name, version, classifier, ext, os, arch);
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
            this.comparableVersion = this.version == null ? MISSING_VERSION : new ComparableVersion(this.version);
        return comparableVersion;
    }

    @Override
    public int compareTo(Artifact o) {
        if (o == this) return 0;
        if (o == null) return 1;

        return Objects.equals(this.group, o.group)
            && Objects.equals(this.name, o.name)
            && Objects.equals(this.classifier, o.classifier)
            && Objects.equals(this.ext, o.ext)
            ? Util.compare(this.getComparableVersion(), o.getComparableVersion())
            : Util.compare(this.getDescriptor(), o.getDescriptor());
    }
}
