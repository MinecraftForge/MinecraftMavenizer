/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl;

import org.jetbrains.annotations.UnknownNullability;

import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings("SameParameterValue")
record JarVersionInfo(
    String fallbackTitle,
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
        var ret = "%s %s".formatted(!this.implementationTitle.isEmpty() ? this.implementationTitle : this.fallbackTitle, this.implementationVersion);
        return vendor && !this.implementationVendor.isEmpty() ? ret + " by %s".formatted(this.implementationVendor) : ret;
    }

    @SuppressWarnings("deprecation")
    static JarVersionInfo of(String fallbackTitle, String packageName) {
        return of(fallbackTitle, Package.getPackage(packageName));
    }

    static JarVersionInfo of(String fallbackTitle, Object object) {
        return of(fallbackTitle, object.getClass());
    }

    static JarVersionInfo of(String fallbackTitle, Class<?> clazz) {
        return of(fallbackTitle, clazz.getPackage());
    }

    static JarVersionInfo of(String fallbackTitle, @UnknownNullability Package pkg) {
        return new JarVersionInfo(
            fallbackTitle,
            pkg != null ? Objects.requireNonNullElse(pkg.getSpecificationTitle(), "") : "",
            pkg != null ? Objects.requireNonNullElse(pkg.getSpecificationVendor(), "") : "",
            pkg != null ? Objects.requireNonNullElse(pkg.getSpecificationVersion(), "") : "",
            pkg != null ? Objects.requireNonNullElse(pkg.getImplementationTitle(), "") : "",
            pkg != null ? Objects.requireNonNullElse(pkg.getImplementationVendor(), "") : "",
            pkg != null ? Objects.requireNonNullElse(pkg.getImplementationVersion(), "") : ""
        );
    }
}
