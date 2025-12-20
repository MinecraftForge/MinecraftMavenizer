/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSpecBuilder;
import net.minecraftforge.util.logging.Logger;

import static net.minecraftforge.mcmaven.impl.Mavenizer.LOGGER;

import java.time.Duration;
import java.util.ArrayList;

public class Main {
    private static final String DISPLAY_NAME = "Minecraft Mavenizer";
    public static void main(String[] args) throws Exception {
        var start = System.nanoTime();
        try {
            LOGGER.capture();
            LOGGER.info(JarVersionInfo.of(DISPLAY_NAME, Main.class).implementation());
            run(args);
        } catch (Throwable e) {
            LOGGER.release();
            throw e;
        }

        var time = Duration.ofNanos(System.nanoTime() - start);
        if (LOGGER.isCapturing()) {
            LOGGER.drop();
            LOGGER.getInfo().print("Minecraft Maven is up-to-date");
        } else {
            LOGGER.getInfo().print("Minecraft Maven has finished");
        }
        LOGGER.getInfo().printf(", took %d:%02d.%03d%n", time.toMinutesPart(), time.toSecondsPart(), time.toMillisPart());
    }

    private static void run(String[] args) throws Exception {
        var parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        var tasks = Tasks.values();
        var opts = new ArrayList<OptionSpecBuilder>();

        var helpO = parser.accepts("help",
            "Displays this help message and exits")
            .forHelp();

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
                task.callback.run(args, false);
                return;
            }
        }

        if (options.has(helpO)) {
            LOGGER.setEnabled(Logger.Level.INFO);
            parser.printHelpOn(LOGGER.getInfo());
            for (var task : tasks) {
                LOGGER.info();
                LOGGER.info(task.key + " Task:");
                LOGGER.push();
                var taskParser = task.callback.run(new String[0], true);
                taskParser.printHelpOn(LOGGER.getInfo());
                LOGGER.pop();
            }
            LOGGER.release();
        } else { // Default to --maven as that is the main usecase.
            Tasks.MAVEN.callback.run(args, false);
        }
    }
}
