package net.minecraftforge.mcmaven.forge;

import java.io.File;

import net.minecraftforge.mcmaven.cache.Cache;
import net.minecraftforge.mcmaven.mcpconfig.MCPConfigRepo;
import net.minecraftforge.mcmaven.util.Artifact;
import net.minecraftforge.mcmaven.util.ComparableVersion;
import net.minecraftforge.mcmaven.util.Constants;
import net.minecraftforge.mcmaven.util.Log;

public class ForgeRepo {
    private static final ComparableVersion USERDEV3_START = new ComparableVersion("1.12.2-14.23.5.2851");
    private static final ComparableVersion USERDEV3_END   = new ComparableVersion("1.12.2-14.23.5.2860");

    final Cache cache;
    final File output;
    final MCPConfigRepo mcpconfig;

    public ForgeRepo(Cache cache, File output) {
        this.cache = cache;
        this.output = output;
        this.mcpconfig = new MCPConfigRepo(cache, output);
    }

    private static void log(String msg) {
        Log.log(msg);
    }

    public void process(String version) {
        var fg = FGVersion.fromForge(version);
        log("Processing Forge:");
        try {
            Log.push();
            log("Version: " + version);

            if (fg == null) {
                log("Python version unsupported!");
                return;
            }

            if (fg.ordinal() < FGVersion.v3.ordinal()) {
                log("Only FG 3+ supported");
            } else {
                processV3(version, "official", null);
            }
        } finally {
            Log.pop();
        }
    }

    @SuppressWarnings("unused")
    private static String forgeToMcVersion(String version) {
        // Save for a few april-fools versions, Minecraft doesn't use _ in their version names.
        // So when Forge needs to reference a version of Minecraft that uses - in the name, it replaces
        // it with _
        // This could cause issues if we ever support a version with _ in it, but fuck it I don't care right now.
        int idx = version.indexOf('-');
        return version.substring(0, idx).replace('_', '-');
    }

    private void processV3(String forge, String channel, String mapping) {
        var forgever = new ComparableVersion(forge);
        // userdev3, old attempt to make 1.12.2 FG3 compatible
        var userdev3 = forgever.compareTo(USERDEV3_START) >= 0 && forgever.compareTo(USERDEV3_END) <= 0;
        var userdev = Artifact.from(Constants.FORGE_GROUP, Constants.FORGE_NAME, forge, userdev3 ? "userdev3" : "userdev", "jar");

        var patcher = new Patcher(this, userdev);
        var sources = patcher.getUnnamedSources();
        try {
            Log.log("Generating Sources");
            Log.push();
            sources.execute();
        } finally {
            Log.pop();
        }
    }
}
