/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSpecBuilder;
import net.minecraftforge.util.logging.Log;

import java.time.Duration;
import java.util.ArrayList;

public class Main {
    private static final String DISPLAY_NAME = "Minecraft Mavenizer";
    public static void main(String[] args) throws Exception {
        var start = System.nanoTime();
        try {
            Log.capture();
            Log.info(JarVersionInfo.of(DISPLAY_NAME, Main.class).implementation());
            run(args);
        } catch (Throwable e) {
            Log.release();
            throw e;
        }

        var time = Duration.ofNanos(System.nanoTime() - start);
        if (Log.isCapturing()) {
            Log.drop();
            Log.INFO.print("Minecraft Maven is up-to-date");
        } else {
            Log.INFO.print("Minecraft Maven has finished");
        }
        Log.INFO.println(String.format(", took %d:%02d.%03d", time.toMinutesPart(), time.toSecondsPart(), time.toMillisPart()));
    }

    private static void run(String[] args) throws Exception {
        var parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        var tasks = Tasks.values();
        var opts = new ArrayList<OptionSpecBuilder>();

        for (var task : tasks)
            opts.add(parser.accepts(task.key, task.description));

        for (var opt : opts) {
            for (var o : opts) {
                if (o != opt)
                    opt.availableUnless(o);
            }
        }

        var options = parser.parse(args);

        for (var task : tasks) {
            if (options.has(task.key)) {
                task.callback.run(args);
                return;
            }
        }

        parser.printHelpOn(Log.INFO);
        Log.release();
    }
}
