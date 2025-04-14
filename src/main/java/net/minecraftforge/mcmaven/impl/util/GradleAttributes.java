package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.data.OS;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface GradleAttributes {
    record NativeDescriptor(@Nullable String os) {
        public static NativeDescriptor from(Artifact artifact) {
            return from(artifact.getOs());
        }

        public static NativeDescriptor from(OS os) {
            return new NativeDescriptor(OperatingSystemFamily.fromOS(os));
        }

        public String variantName() {
            return "natives" + (this.os != null ? '-' + this.os : "");
        }

        public Map<String, Object> toMap(Map<String, ?> existing) {
            return existing != null ? toMapInternal(new HashMap<>(existing)) : this.toMap();
        }

        public Map<String, Object> toMap() {
            return toMapInternal(new HashMap<>());
        }

        private Map<String, Object> toMapInternal(Map<String, Object> ret) {
            if (this.os != null)
                ret.put(OperatingSystemFamily.NAME, this.os);
            return ret;
        }
    }

    interface OperatingSystemFamily {
        String NAME = "net.minecraftforge.native.operatingSystem",
            WINDOWS = "windows", MACOS = "macos", LINUX = "linux";

        List<String> ALL = List.of(WINDOWS, MACOS, LINUX);

        static @Nullable String fromOS(OS os) {
            return switch (os) {
                case WINDOWS -> WINDOWS;
                case MACOS -> MACOS;
                case LINUX, ALPINE, MUSL -> LINUX;
                case null, default -> null;
            };
        }
    }
}
