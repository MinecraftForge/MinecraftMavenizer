package net.minecraftforge.mcmaven.util;

public class Log {
    private static int indent = 0;

    public static void push() {
        indent++;
    }

    public static void pop() {
        indent--;
    }

    private static void indent() {
        for (int x = 0; x < indent; x++)
            System.out.print("  ");
    }

    public static void log(String message) {
        indent();
        System.out.println(message);
    }

    public static void error(String message) {
        indent();
        System.out.println(message);
    }

    public static void debug(String message) {
        indent();
        System.out.println(message);
    }
}
