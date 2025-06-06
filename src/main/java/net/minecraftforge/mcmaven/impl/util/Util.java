/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import java.util.function.Consumer;
import java.util.function.Function;

// TODO [MCMaven][Documentation] Document
public class Util {
    public static <S extends Comparable<S>> int compare(S a, S b) {
        if (a == null)
            return b == null ? 0 : -1;
        else if (b == null)
            return 1;
        return a.compareTo(b);
    }

    /**
     * Allows the given {@link Throwable} to be thrown without needing to declare it in the method signature or
     * arbitrarily checked at compile time.
     *
     * @param t   The throwable
     * @param <R> The type of the fake return if used as a return statement
     * @param <E> The type of the throwable
     * @throws E Unconditionally thrown
     */
    @SuppressWarnings("unchecked")
    public static <R, E extends Throwable> R sneak(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Mimic's Mojang's {@code Util.make(...)} in Minecraft. This is a utility method that allows you to modify an input
     * object in-line with the given action.
     *
     * @param obj    The object to modify
     * @param action The action to apply to the object
     * @param <T>    The type of the object
     * @return The object
     */
    public static <T> T make(T obj, Consumer<? super T> action) {
        action.accept(obj);
        return obj;
    }

    /**
     * Mimic's Mojang's {@code Util.make(...)} in Minecraft. This is a utility method that allows you to modify an input
     * object in-line with the given action. This version of the method allows you to return a new object instead of the
     * original, which can be useful for in-line {@link String} modifications that would otherwise cause variable
     * re-assignment (bad if you want to use them as lambda parameters).
     *
     * @param obj    The object to modify
     * @param action The action to apply to the object
     * @param <T>    The type of the object
     * @return The object
     */
    public static <T, R> R replace(T obj, Function<T, R> action) {
        return action.apply(obj);
    }
}
