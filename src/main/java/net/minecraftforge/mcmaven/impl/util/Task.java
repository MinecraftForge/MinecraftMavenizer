/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/** Represents a task that can be executed. Tasks in this tool <strong>will always</strong> provide a file. */
public interface Task {
    /**
     * Executes the task and returns the output file.
     * <p>It is not always guaranteed to run the work required.
     *
     * @return The output file for this task
     */
    File execute();

    /**
     * Whether this task has had its work executed and the output file has been resolved.
     *
     * @return If this task's output has been resolved
     */
    boolean resolved();

    /**
     * The unique name for this task.
     *
     * @return The name for this task
     * @implNote The uniqueness of the task name is not enforced, but is required in order to track and debug the
     * execution process.
     */
    String name();

    static Task named(String name, Callable<File> supplier) {
        return named(name, List.of(), supplier);
    }

    static Task named(String name, SequencedCollection<? extends Supplier<? extends Task>> deps, Callable<File> supplier) {
        return new Simple(name, deps, supplier);
    }

    static SequencedCollection<Supplier<Task>> deps(Task... tasks) {
        return Arrays.stream(tasks).map(Util::supplyingSelf).toList();
    }

    @SafeVarargs
    static SequencedCollection<? extends Supplier<? extends Task>> deps(Supplier<? extends Task>... tasks) {
        return List.of(tasks);
    }

    static SequencedCollection<Supplier<Task>> deps(Iterable<?> tasks) {
        return StreamSupport.stream(tasks.spliterator(), false).map(Task::cast).toList();
    }

    private static Supplier<Task> cast(Object obj) {
        return () -> {
            var ret = obj instanceof Supplier<?> supplier ? supplier.get() : obj;
            try {
                return (Task) Objects.requireNonNull(ret);
            } catch (ClassCastException | NullPointerException e) {
                throw new IllegalArgumentException("Expected Task or Supplier<Task>, found %s".formatted(ret != null ? ret.getClass() : "null"));
            }
        };
    }

    final class Simple implements Task {
        private final String name;
        private final SequencedCollection<? extends Supplier<? extends Task>> deps;
        private final Callable<File> supplier;
        private File file;

        private Simple(String name, SequencedCollection<? extends Supplier<? extends Task>> deps, Callable<File> supplier) {
            this.name = name;
            this.deps = deps;
            this.supplier = supplier;
        }

        @Override
        public File execute() {
            // immediately stop if result is already calculated
            if (this.file == null) {
                // run all task dependencies
                for (var dep : deps) {
                    var task = dep.get();
                    if (task == null) continue; // Some automated task generators may have a null parent, which is fine.

                    try {
                        task.execute();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to execute task `%s` which is required by task `%s`".formatted(task.name(), this.name()), e);
                    }
                }

                LOGGER.info(name);
                var indent = LOGGER.push();
                var start = System.nanoTime();
                try {
                    this.file = supplier.call();

                    var time = Duration.ofNanos(System.nanoTime() - start);
                    LOGGER.debug(String.format("-> took %d:%02d.%03d", time.toMinutesPart(), time.toSecondsPart(), time.toMillisPart()));
                    LOGGER.debug("-> " + this.file.getAbsolutePath());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to execute task `%s`".formatted(this.name()), e);
                } finally {
                    LOGGER.pop(indent);
                }
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

    record Existing(String name, File execute) implements Task {
        @Override
        public boolean resolved() {
            return true;
        }

        @Override
        public String toString() {
            return "Task[" + this.name + ']';
        }
    }
}
