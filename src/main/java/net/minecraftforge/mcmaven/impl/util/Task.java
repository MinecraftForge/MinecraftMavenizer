/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/** Represents a task that can be executed. Tasks in this tool <strong>will always</strong> provide a file. */
public interface Task extends Supplier<File> {
    public static Task named(String name, Supplier<File> supplier) {
        return named(name, Collections.emptySet(), supplier);
    }

    public static Task named(String name, Set<Task> deps, Supplier<File> supplier) {
        return new Simple(name, deps, supplier);
    }

    /**
     * Executes the task. If it has already been executed, the work will be skipped and the file will be provided
     * instantly.
     *
     * @return The file provided by this task
     */
    File execute();

    /** @return The name of this task */
    String name();

    @Override
    default File get() {
        return this.execute();
    }

    public static class Simple implements Task {
        private final String name;
        private final Set<Task> deps;
        private final Supplier<File> supplier;
        private File file;

        private Simple(String name, Set<Task> deps, Supplier<File> supplier) {
            this.name = name;
            this.deps = deps;
            this.supplier = supplier;
        }

        @Override
        public File execute() {
            if (this.file == null) {
                for (var dep : deps)
                    dep.execute();
                Log.log(name);
                Log.push();
                this.file = supplier.get();
                Log.debug("-> " + this.file.toString());
                Log.pop();
            }
            return this.file;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "Task[" + name + ']';
        }
    }
}
