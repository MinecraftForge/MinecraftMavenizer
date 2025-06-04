/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.data;

import net.minecraftforge.mcmaven.impl.util.Arch;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.GradleAttributes;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.util.data.OS;
import net.minecraftforge.util.hash.HashFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @see <a
 * href="https://github.com/gradle/gradle/blob/master/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md">Gradle
 * Module Metadata 1.1 Spec</a>
 */
public class GradleModule {
    /** must be present and the first value of the JSON object. Its value must be {@code "1.1"} */
    public String formatVersion = "1.1";
    /** optional. */
    public Component component;
    /** optional. */
    public CreatedBy createdBy;
    /** optional. */
    public List<Variant> variants;

    public static GradleModule of(Artifact artifact) {
        return of(artifact.getGroup(), artifact.getName(), artifact.getVersion());
    }

    public static GradleModule of(String group, String name, String version) {
        var module = new GradleModule();
        module.component = new Component(group, name, version);
        return module;
    }

    public Variant variant() {
        return this.variant(new Variant());
    }

    public Variant variant(Consumer<? super Variant> action) {
        return Util.make(this.variant(), action);
    }

    public Variant variant(Variant variant) {
        if (variants == null)
            variants = new ArrayList<>();

        variants.add(variant);
        return variant;
    }

    public Map<GradleAttributes.NativeDescriptor, Variant> nativeVariants(String prefix) {
        if (variants == null)
            variants = new ArrayList<>();

        var ret = new HashMap<GradleAttributes.NativeDescriptor, Variant>();

        for (var os : GradleAttributes.OperatingSystemFamily.ALL) {
            var natives = new GradleAttributes.NativeDescriptor(os);
            var variant = Variant.ofNative(prefix, natives);
            variants.add(variant);
            ret.put(natives, variant);
        }

        return ret;
    }

    /** Describes the identity of the component contained in the module. */
    public static class Component {
        /** The group of this component. */
        public String group;
        /** The module name of this component. */
        public String module;
        /** The version of this component. */
        public String version;
        /**
         * optional. When present, indicates where the metadata for the component may be found. When missing, indicates
         * that this metadata file defines the metadata for the whole component.
         */
        public String url;

        private Component(String group, String module, String version) {
            this.group = group;
            this.module = module;
            this.version = version;
        }
    }

    /** Describes the producer of this metadata file and the contents of the module. */
    public static class CreatedBy {
        /** optional. */
        public Gradle gradle;

        /** Describes the Gradle instance that produced the contents of the module. */
        public static class Gradle {
            /** The version of Gradle. */
            public String version;
            /** optional. The buildId for the Gradle instance. */
            public String buildId;
        }
    }

    /** Describes a variant of the component. */
    public static class Variant {
        /** The name of the variant. */
        public String name;
        /** The attributes of the variant. */
        public Map<String, Object> attributes;
        /** The files of the variant. */
        public List<File> files;
        /** The dependencies of the variant. */
        public List<Dependency> dependencies;
        /** The dependency constraints of the variant. */
        public List<DependencyConstraint> dependencyConstraints;
        /** The capabilities of the variant. */
        public List<Capability> capabilities;
        /** Information about where the metadata and files of this variant are available. */
        public AvailableAt availableAt;

        public void addDependency(Dependency dependency) {
            if (this.dependencies == null)
                this.dependencies = new ArrayList<>();

            this.dependencies.remove(dependency);
            this.dependencies.add(dependency);
        }

        public void addDependencies(Iterable<? extends Dependency> dependencies) {
            dependencies.forEach(this::addDependency);
        }

        public static Variant ofNative(String prefix, GradleAttributes.NativeDescriptor natives) {
            var ret = new Variant();
            ret.name = prefix + "-" + natives.variantName();
            ret.dependencies = new ArrayList<>();
            ret.attributes = new HashMap<>(natives.toMap());
            return ret;
        }

        public record NativeDescriptor(OS os, Arch arch) { }

        /** Describes a file of a variant. */
        public static class File {
            /** The name of the file. */
            public String name;
            /** The URL of the file. */
            public String url;
            /** The size of the file. */
            public Long size;
            /** The SHA-1 checksum of the file. */
            public String sha1;
            /** The SHA-256 checksum of the file. */
            public String sha256;
            /** The SHA-512 checksum of the file. */
            public String sha512;
            /** The MD5 checksum of the file. */
            public String md5;

            public File(java.io.File file) {
                this(file.getName(), file);
            }

            public File(String name, java.io.File file) {
                this.name = this.url = name;
                this.size = file.length();
                this.sha1 = HashFunction.SHA1.sneakyHash(file);
                this.sha256 = HashFunction.SHA256.sneakyHash(file);
                this.sha512 = HashFunction.SHA512.sneakyHash(file);
                this.md5 = HashFunction.MD5.sneakyHash(file);
            }
        }

        /** Describes a dependency of a variant. */
        public static class Dependency {
            /** The group of the dependency. */
            public String group;
            /** The module name of the dependency. */
            public String module;
            /** The version of the dependency. */
            public Version version;
            /** The exclusions that apply to this dependency. */
            public List<Exclude> excludes;
            /** A explanation why the dependency is used. */
            public String reason;
            /**
             * Attributes that will override the consumer attributes during dependency resolution for this specific
             * dependency.
             */
            public Map<String, Object> attributes;
            /** Declares the capabilities that the dependency must provide in order to be selected. */
            public List<Capability> requestedCapabilities;
            /**
             * If set to true, all strict versions of the target module will be treated as if they were defined on the
             * variant defining this dependency.
             */
            public Boolean endorseStrictVersions;
            /**
             * Includes additional information to be used if the dependency points at a module that did not publish
             * Gradle module metadata.
             */
            public ThirdPartyCompatibility thirdPartyCompatibility;

            @Override
            public String toString() {
                return "Dependency{" +
                    "group='" + group + '\'' +
                    ", module='" + module + '\'' +
                    ", version=" + version +
                    ", attributes=" + attributes +
                    ", requestedCapabilities=" + requestedCapabilities +
                    ", thirdPartyCompatibility=" + thirdPartyCompatibility +
                    '}';
            }

            @Override
            public boolean equals(Object obj) {
                return this == obj || obj instanceof Dependency o &&
                    Objects.equals(this.group, o.group) &&
                    Objects.equals(this.module, o.module) &&
                    Objects.equals(this.attributes, o.attributes) &&
                    Objects.equals(this.thirdPartyCompatibility, o.thirdPartyCompatibility);
            }

            public void setArtifactSelector(ThirdPartyCompatibility.ArtifactSelector selector) {
                if (this.thirdPartyCompatibility == null)
                    this.thirdPartyCompatibility = new ThirdPartyCompatibility();

                thirdPartyCompatibility.artifactSelector = selector;
            }

            public static Dependency of(Artifact artifact) {
                var dependency = new Dependency();
                dependency.group = artifact.getGroup();
                dependency.module = artifact.getName();
                dependency.version = new Dependency.Version();
                dependency.version.requires = artifact.getVersion();

                if (artifact.getClassifier() != null || !artifact.getExtension().equals("jar"))
                    dependency.setArtifactSelector(ThirdPartyCompatibility.ArtifactSelector.of(artifact));

                return dependency;
            }

            /** Describes the version of a dependency. */
            public static class Version {
                /** The required version for this dependency. */
                public String requires;
                /** The preferred version for this dependency. */
                public String prefers;
                /** A strictly enforced version requirement for this dependency. */
                public String strictly;
                /** An array of rejected versions for this dependency. */
                public List<String> rejects;
            }

            /** Defines the exclusions that apply to this dependency. */
            public static class Exclude {
                /** The group to exclude from transitive dependencies, or wildcard '*' if any group may be excluded. */
                public String group;
                /** The module to exclude from transitive dependencies, or wildcard '*' if any module may be excluded. */
                public String module;
            }

            /**
             * Includes additional information to be used if the dependency points at a module that did not publish
             * Gradle module metadata.
             */
            public static class ThirdPartyCompatibility {
                /**
                 * Information to select a specific artifact of the dependency that is not mentioned in the dependency's
                 * metadata.
                 */
                public ArtifactSelector artifactSelector;

                @Override
                public boolean equals(Object obj) {
                    return this == obj || obj instanceof ThirdPartyCompatibility o &&
                        Objects.equals(this.artifactSelector, o.artifactSelector);
                }

                /** Information to select a specific artifact. */
                public static class ArtifactSelector {
                    /** The name of the artifact. */
                    public String name;
                    /** The type of the artifact. */
                    public String type;
                    /** The extension of the artifact. */
                    public String extension;
                    /** The classifier of the artifact. */
                    public String classifier;

                    public static ArtifactSelector of(Artifact artifact) {
                        return Util.make(new ArtifactSelector(), selector -> {
                            selector.name = artifact.getName();
                            selector.type = artifact.getExtension();
                            selector.extension = artifact.getExtension();
                            selector.classifier = artifact.getClassifier();
                        });
                    }

                    @Override
                    public boolean equals(Object obj) {
                        return this == obj || obj instanceof ArtifactSelector o &&
                            Objects.equals(this.name, o.name) &&
                            Objects.equals(this.type, o.type) &&
                            Objects.equals(this.extension, o.extension) &&
                            Objects.equals(this.classifier, o.classifier);
                    }
                }
            }
        }

        /** Describes a dependency constraint of a variant. */
        public static class DependencyConstraint {
            /** The group of the dependency constraint. */
            public String group;
            /** The module name of the dependency constraint. */
            public String module;
            /** The version constraint of the dependency constraint. */
            public Version version;
            /** A explanation why the constraint is used. */
            public String reason;
            /**
             * Attributes that will override the consumer attributes during dependency resolution for this specific
             * dependency.
             */
            public Map<String, Object> attributes;

            /** Describes the version of a dependency constraint. */
            public static class Version {
                /** The required version for this dependency constraint. */
                public String requires;
                /** The preferred version for this dependency constraint. */
                public String prefers;
                /** A strictly enforced version requirement for this dependency constraint. */
                public String strictly;
                /** An array of rejected versions for this dependency constraint. */
                public List<String> rejects;
            }
        }

        /** Describes a capability of a variant. */
        public static class Capability {
            /** The group of the capability. */
            public String group;
            /** The name of the capability. */
            public String name;
            /** The version of the capability. */
            public String version;
        }

        /** Information about where the metadata and files of this variant are available. */
        public static class AvailableAt {
            /** The location of the metadata file that describes the variant. */
            public String url;
            /** The group of the module. */
            public String group;
            /** The name of the module. */
            public String module;
            /** The version of the module. */
            public String version;
        }
    }
}