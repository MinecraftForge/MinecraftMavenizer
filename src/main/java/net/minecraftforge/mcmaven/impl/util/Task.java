/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.io.File;
import java.util.Set;
import java.util.function.Supplier;

import static net.minecraftforge.mcmaven.impl.util.Constants.LOGGER;

/** Represents a task that can be executed. Tasks in this tool <strong>will always</strong> provide a file. */
public sealed interface Task extends Supplier<File> permits Task.Simple {
    static Task named(String name, Supplier<File> supplier) {
        return named(name, Set.of(), supplier);
    }

    static Task named(String name, Set<? extends Task> deps, Supplier<File> supplier) {
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

    /** @see #execute() */
    @Override
    default File get() {
        return this.execute();
    }

    final class Simple implements Task {
        private final String name;
        private final Set<? extends Task> deps;
        private final Supplier<File> supplier;
        private File file;

        private Simple(String name, Set<? extends Task> deps, Supplier<File> supplier) {
            this.name = name;
            this.deps = deps;
            this.supplier = supplier;
        }

        @Override
        public File execute() {
            if (this.file == null) {
                for (var dep : deps)
                    dep.execute();
                LOGGER.info(name);
                LOGGER.push();
                this.file = supplier.get();
                LOGGER.debug("-> " + this.file.getAbsolutePath());
                LOGGER.pop();
            }

            return this.file;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public String toString() {
            return "Task[" + this.name + ']';
        }
    }
}
