package net.minecraftforge.mcmaven.util;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

public interface Task {
    public static Task named(String name, Supplier<File> supplier) {
        return named(name, Collections.emptySet(), supplier);
    }

    public static Task named(String name, Set<Task> deps, Supplier<File> supplier) {
        return new Simple(name, deps, supplier);
    }

    File execute();
    String name();

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
                this.file = supplier.get();
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
