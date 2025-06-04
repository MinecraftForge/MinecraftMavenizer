package net.minecraftforge.mcmaven.impl.util;

// TODO Always warn on unknown OS

import java.util.Locale;

/**
 * Enum representing the operating system.
 */
public enum Arch {
    X86    ("x86",    "i386", "i686"),
    X86_64 ("x86_64", "x64", "amd64"),
    ARM    ("arm",    "aarch32", "aarch_32"),
    ARM64  ("arm64",  "aarch64", "aarch_64"),
    UNKNOWN("unknown");

    private static final Arch[] $values = values();
    /** The operating system that this tool is being run on. */
    public static final Arch CURRENT = getCurrent();

    private final String key;
    private final String[] names;

    Arch(String... names) {
        this.key = names[0];
        this.names = names;
    }

    /**
     * The primary name, and enum key, of the operating system.
     *
     * @return The primary name
     */
    public String key() {
        return this.key;
    }

    /**
     * Returns the OS enum for the given key.
     *
     * @param key The key to search for
     * @return The OS enum, or null if not found
     */
    public static Arch byKey(String key) {
        for (Arch value : $values) {
            if (value.key.equals(key))
                return value;
        }

        return UNKNOWN;
    }

    public static Arch byName(String name) {
        for (Arch value : $values) {
            for (String n : value.names) {
                if (n.equals(name))
                    return value;
            }
        }

        return UNKNOWN;
    }

    private static Arch getCurrent() {
        String prop = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
        for (Arch os : $values) {
            for (String key : os.names) {
                if (prop.contains(key)) return os;
            }
        }

        return UNKNOWN;
    }
}
