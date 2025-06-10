/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Supplier;

/** Represents a task that can be executed. Tasks in this tool <strong>will always</strong> provide a file. */
public sealed interface Task extends Supplier<File> permits Task.Simple {
    static Task named(String name, ThrowingSupplier<File> supplier) {
        return named(name, Set.of(), supplier);
    }

    static Task named(String name, Iterable<? extends Task> deps, ThrowingSupplier<File> supplier) {
        return new Simple(name, deps, supplier);
    }

    static Iterable<? extends Task> collect(Object... deps) {
        return () -> new Iterator<>() {
            private final Iterator<?> itor = Arrays.asList(deps).iterator();

            @Override
            public boolean hasNext() {
                return this.itor.hasNext();
            }

            @Override
            public Task next() {
                var obj = this.itor.next();
                if (!(obj instanceof Task) && obj instanceof Supplier<?> supplier) {
                    obj = supplier.get();
                }

                try {
                    return (Task) obj;
                } catch (ClassCastException e) {
                    throw new IllegalArgumentException("Invalid task dependency. Expected Task or Supplier<Task>, Found: %s".formatted(obj), e);
                }
            }
        };
    }

    /**
     * Executes the task. If it has already been executed, the work will be skipped and the file will be provided
     * instantly.
     *
     * @return The file provided by this task
     */
    File execute();

    boolean resolved();

    /** @return The name of this task */
    String name();

    /** @see #execute() */
    @Override
    default File get() {
        return this.execute();
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    final class Simple implements Task {
        private final String name;
        private final Iterable<? extends Task> deps;
        private final ThrowingSupplier<File> supplier;
        private File file;

        private Simple(String name, Iterable<? extends Task> deps, ThrowingSupplier<File> supplier) {
            this.name = name;
            this.deps = deps;
            this.supplier = supplier;
        }

        @Override
        public File execute() {
            if (this.file == null) {
                for (var dep : deps)
                    dep.execute();
                Log.info(name);
                Log.push();
                try {
                    this.file = supplier.get();
                } catch (Exception e) {
                    Util.sneak(e);
                }
                Log.debug("-> " + this.file.getAbsolutePath());
                Log.pop();
            }

            return this.file;
        }

        @Override
        public boolean resolved() {
            return this.file != null;
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
