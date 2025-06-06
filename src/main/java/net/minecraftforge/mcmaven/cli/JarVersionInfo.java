/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import org.jetbrains.annotations.UnknownNullability;

record JarVersionInfo(
    String specificationTitle,
    String specificationVendor,
    String specificationVersion,
    String implementationTitle,
    String implementationVendor,
    String implementationVersion
) {
    String implementation() {
        var ret = new StringBuilder().append(this.implementationTitle);
        if (!this.implementationVersion.isEmpty())
            ret.append(' ').append(this.implementationVersion);
        if (!this.implementationVendor.isEmpty())
            ret.append(" by ").append(this.implementationVendor);
        return ret.toString();
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
        if (pkg == null)
            return new JarVersionInfo(null, null, null, fallbackTitle, null, null);

        return new JarVersionInfo(
            notNullOrEmpty(pkg.getSpecificationTitle(), ""),
            notNullOrEmpty(pkg.getSpecificationVendor(), ""),
            notNullOrEmpty(pkg.getSpecificationVersion(), "Undefined"),
            notNullOrEmpty(pkg.getImplementationTitle(), fallbackTitle),
            notNullOrEmpty(pkg.getImplementationVendor(), ""),
            notNullOrEmpty(pkg.getImplementationVersion(), "Undefined")
        );
    }

    private static String notNullOrEmpty(String value, String defaultValue) {
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
