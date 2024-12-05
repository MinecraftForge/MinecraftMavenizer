module net.minecraftforge.mcmaven {
    exports net.minecraftforge.mcmaven.cache;
    exports net.minecraftforge.mcmaven.data;
    exports net.minecraftforge.mcmaven.forge;
    exports net.minecraftforge.mcmaven.mcpconfig;
    exports net.minecraftforge.mcmaven.util;

    requires static org.jspecify;

    requires com.google.gson;
    requires de.siegmar.fastcsv;
    requires io.codechicken.diffpatch;
    requires net.minecraftforge.java_version;
    requires java.xml;
    requires joptsimple;
    requires org.apache.commons.io;
    requires org.apache.commons.lang3;
    requires net.minecraftforge.srgutils;
}