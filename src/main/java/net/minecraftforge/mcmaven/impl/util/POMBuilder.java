/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class POMBuilder {
    public static final String GRADLE_MAGIC_COMMENT = " do_not_remove: published-with-gradle-metadata ";
    private final String group, name, version;
    private final Dependencies dependencies = new Dependencies();
    private @Nullable String description;
    private boolean preferGradleModule;

    public POMBuilder(Artifact artifact) {
        this(artifact.getGroup(), artifact.getName(), artifact.getVersion());
    }

    public POMBuilder(String group, String name, String version) {
        this.group = group;
        this.name = name;
        this.version = version;
    }

    public POMBuilder description(String description) {
        this.description = description;
        return this;
    }

    public POMBuilder dependencies(Consumer<? super Dependencies> configurator) {
        configurator.accept(dependencies);
        return this;
    }

    public POMBuilder preferGradleModule() {
        this.preferGradleModule = true;
        return this;
    }

    /**
     * Builds the POM file.
     *
     * @return The POM file as a string
     * @throws ParserConfigurationException If something goes horribly wrong
     * @throws TransformerException         If the POM file cannot be transformed
     */
    public String build() throws ParserConfigurationException, TransformerException {
        var docFactory = DocumentBuilderFactory.newInstance();
        var docBuilder = docFactory.newDocumentBuilder();
        var doc = docBuilder.newDocument();

        var project = doc.createElement("project");
        project.setAttribute("xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd");
        project.setAttribute("xmlns", "http://maven.apache.org/POM/4.0.0");
        project.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        doc.appendChild(project);

        if (this.preferGradleModule)
            project.appendChild(doc.createComment(GRADLE_MAGIC_COMMENT));

        set(doc, project, "modelVersion", "4.0.0");
        set(doc, project, "groupId", this.group);
        set(doc, project, "artifactId", this.name);
        set(doc, project, "version", this.version);
        set(doc, project, "name", this.name);
        if (this.description != null) {
            set(doc, project, "description", this.description);
        }

        if (!this.dependencies.dependencies.isEmpty()) {
            var dependencies = doc.createElement("dependencies");
            for (var dependency : this.dependencies.dependencies) {
                var dep = doc.createElement("dependency");
                set(doc, dep, "groupId", dependency.artifact.getGroup());
                set(doc, dep, "artifactId", dependency.artifact.getName());
                set(doc, dep, "version", dependency.artifact.getVersion());

                var classifier = dependency.artifact.getClassifier();
                var extension = dependency.artifact.getExtension();
                if (classifier != null)
                    set(doc, dep, "classifier", classifier);

                if (extension != null && !"jar".equals(extension))
                    set(doc, dep, "type", extension);

                if (dependency.scope != null)
                    set(doc, dep, "scope", dependency.scope.toString());

                dependencies.appendChild(dep);
            }
            project.appendChild(dependencies);
        }

        doc.normalizeDocument();

        var transformerFactory = TransformerFactory.newInstance();
        var transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //Make it pretty
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        var output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(output));

        return output.toString();
    }

    private static void set(Document doc, Element parent, String name, String value) {
        var description = doc.createElement(name);
        description.appendChild(doc.createTextNode(value));
        parent.appendChild(description);
    }

    public static final class Dependencies {
        private final Set<Dependency> dependencies = new LinkedHashSet<>();

        private Dependencies() { }

        // short-hand to use Dependencies::add. this sets a default scope of "compile"
        // to omit a scope, use add(Artifact, Dependency.Scope) with a null scope
        public Dependency add(Artifact artifact) {
            return this.add(artifact, Dependency.Scope.COMPILE);
        }

        public Dependency add(Artifact artifact, @Nullable Dependency.Scope scope) {
            var dep = new Dependency(artifact, scope);
            this.dependencies.add(dep);
            return dep;
        }

        // https://maven.apache.org/pom.html#POM_Relationships
        public record Dependency(Artifact artifact, @Nullable Scope scope) implements Comparable<Dependency> {
            @Override
            public boolean equals(Object obj) {
                return obj instanceof Dependency(Artifact artifact, Scope scope) &&
                    Objects.equals(this.artifact, artifact) &&
                    Objects.equals(this.scope, scope);
            }

            @Override
            public int compareTo(Dependency o) {
                var ret = Util.compare(this.artifact, o.artifact);
                return ret != 0 ? ret : Util.compare(this.scope, o.scope);
            }

            public enum Scope {
                COMPILE, PROVIDED, RUNTIME, TEST, SYSTEM;

                @Override
                public String toString() {
                    return super.toString().toLowerCase(Locale.ENGLISH);
                }
            }
        }
    }

}
