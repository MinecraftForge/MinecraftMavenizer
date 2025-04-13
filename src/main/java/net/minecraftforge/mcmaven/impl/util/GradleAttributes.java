package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.util.data.OS;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface GradleAttributes {
    record NativeDescriptor(@Nullable String os, @Nullable String arch) {
        public static NativeDescriptor from(Artifact artifact) {
            return from(artifact.getOs(), artifact.getArch());
        }

        public static NativeDescriptor from(OS os, Arch arch) {
            return new NativeDescriptor(OperatingSystemFamily.fromOS(os), MachineArchitecture.fromArch(arch));
        }

        public boolean hasAny() {
            return this.os != null || this.arch != null;
        }

        public String variantName() {
            return "natives" + (this.os != null ? '-' + this.os : "") + (this.arch != null ? '-' + this.arch : "");
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
            if (this.arch != null)
                ret.put(MachineArchitecture.NAME, this.arch);
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

    interface MachineArchitecture {
        String NAME = "net.minecraftforge.native.architecture",
            X86 = "x86", X86_64 = "x86-64", ARM64 = "aarch64";

        List<String> ALL = List.of(X86, X86_64, ARM64);

        static @Nullable String fromArch(Arch arcg) {
            return switch (arcg) {
                case X86 -> X86;
                case X86_64 -> X86_64;
                case ARM64 -> ARM64;
                case null, default -> null;
            };
        }
    }
}
