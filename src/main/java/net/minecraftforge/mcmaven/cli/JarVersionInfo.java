/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import java.util.Objects;
import java.util.function.Consumer;

record JarVersionInfo(
    String specificationTitle,
    String specificationVendor,
    String specificationVersion,
    String implementationTitle,
    String implementationVendor,
    String implementationVersion
) {
    void hello(Consumer<String> consumer, boolean vendor, boolean newLine) {
        consumer.accept(this.getHello(vendor) + (newLine ? "\n" : ""));
    }

    String getHello(boolean vendor) {
        var ret = "%s %s".formatted(this.implementationTitle, this.implementationVersion);
        return vendor ? ret + " by %s".formatted(this.implementationVendor) : ret;
    }

    @SuppressWarnings("deprecation")
    static JarVersionInfo of(String packageName) {
        return of(Package.getPackage(packageName));
    }

    static JarVersionInfo of(Class<?> clazz) {
        return of(clazz.getPackage());
    }

    static JarVersionInfo of(Package pkg) {
        return new JarVersionInfo(
            Objects.requireNonNullElse(pkg.getSpecificationTitle(), ""),
            Objects.requireNonNullElse(pkg.getSpecificationVendor(), ""),
            Objects.requireNonNullElse(pkg.getSpecificationVersion(), ""),
            Objects.requireNonNullElse(pkg.getImplementationTitle(), ""),
            Objects.requireNonNullElse(pkg.getImplementationVendor(), ""),
            Objects.requireNonNullElse(pkg.getImplementationVersion(), "")
        );
    }
}
