/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.util;

// TODO: [MCMaven][Logging] Refactor to use Log4J or some other logging framework. Or, add our own implementation of log levels.
/**
 * Simple logging utility. Covers logging for the entire program.
 */
public final class Log {
    private static int indent = 0;

    /** Increase the indent level. */
    public static void push() {
        indent++;
    }

    /** Decreases the indent level. */
    public static void pop() {
        indent--;
    }

    private static void indent() {
        for (int x = 0; x < indent; x++)
            System.out.print("  ");
    }

    // TODO: [MCMaven][Logging] Use logging levels
    /** Logs a message, presumably at the {@code INFO} level. */
    public static void log(String message) {
        indent();
        System.out.println(message);
    }

    // TODO: [MCMaven][Logging] Use logging levels
    /** Logs a message at the {@code ERROR} level. */
    public static void error(String message) {
        indent();
        System.out.println(message);
    }

    // TODO: [MCMaven][Logging] Use logging levels
    /** Logs a message at the {@code DEBUG} level. */
    public static void debug(String message) {
        indent();
        System.out.println(message);
    }
}
