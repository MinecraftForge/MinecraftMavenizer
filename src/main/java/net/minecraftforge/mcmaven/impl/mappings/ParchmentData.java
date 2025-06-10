/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraftforge.util.data.json.JsonData;

class ParchmentData {
    public static ParchmentData load(InputStream stream) {
        return JsonData.fromJson(stream, ParchmentData.class);
    }

    String version;
    List<Package> packages;
    List<Clazz> classes;

    transient Map<String, Package> packageMap;
    transient Map<String, Clazz> classMap;

    void bake() {
        if (packages == null)
            packageMap = Collections.emptyMap();
        else
            packageMap = packages.stream().collect(Collectors.toMap(p -> p.name, Function.identity()));

        if (classes == null)
            classMap = Collections.emptyMap();
        else {
            classMap = new HashMap<>();
            for (var cls : classes) {
                classMap.put(cls.name, cls);
                cls.bake();
            }
        }
    }

    static class Element {
        String name;
        List<String> javadoc;

        public Element() { }
        private Element(String name, List<String> javadoc) {
            this.name = name;
            this.javadoc = javadoc;
        }
    }

    static class Package extends Element {
    }

    static class Clazz extends Element {
        List<Field> fields;
        List<Method> methods;

        transient Map<String, Field> fieldMap;
        transient Map<String, Method> methodMap;

        void bake() {
            if (fields == null)
                fieldMap = Collections.emptyMap();
            else
                fieldMap = fields.stream().collect(Collectors.toMap(p -> p.name, Function.identity()));

            if (methods == null)
                methodMap = Collections.emptyMap();
            else {
                methodMap = new HashMap<>();
                for (var mtd : methods) {
                    methodMap.put(mtd.name + mtd.descriptor, mtd);
                    mtd.bake();
                }
            }
        }
    }

    static class Field extends Element {
        String descriptor;
    }

    static class Method extends Element {
        String descriptor;
        List<Parameter> parameters;

        transient Map<Integer, Element> paramMap;

        void bake() {
            if (parameters == null)
                paramMap = Collections.emptyMap();
            else {
                paramMap = new HashMap<>();
                for (var param : parameters) {
                    paramMap.put(param.index, new Element(param.name, param.javadoc == null ? null : List.of(param.javadoc)));
                }
            }
        }
    }

    static class Parameter {
        int index;
        String name;
        String javadoc;
    }
}
