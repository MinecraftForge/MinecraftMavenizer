/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.mcmaven.impl.util.ComparableVersion;
import net.minecraftforge.mcmaven.impl.util.StupidHacks;

class PythonForgeSourceTests {
    @TempDir
    Path temp;

    @Test
    void pre16VersionsUseTheStandalonePipelineAndBundledMappings() {
        assertTrue(ForgeRepo.isSupported("1.5.2-7.8.1.738"));
        assertTrue(ForgeRepo.isSupported("1.6.2-9.10.0.776"));
        StupidHacks.mappings("1.5.2-7.8.1.738", "snapshot:20130617");
        assertEquals("snapshot-20130617", StupidHacks.getDefaultMappings(new ComparableVersion("1.5.2-7.8.1.738")).toString());
    }

    @Test
    void preparesPythonMcpConfigurationAndBundledMappings() throws Exception {
        var source = new File(this.temp.toFile(), "forge-src.zip");
        var mcp = new File(this.temp.toFile(), "mcp.zip");
        var srg = new File(this.temp.toFile(), "mcp-1.5.2-srg.zip");
        var assets = new File(this.temp.toFile(), "assets.zip");
        var preparedMcp = new File(this.temp.toFile(), "prepared-mcp.zip");

        writeZip(source, Map.ofEntries(
            Map.entry("forge/fml/conf/version.cfg", "[VERSION]\nMCPVersion = 7.51\nClientVersion = 1.5.2\n"),
            Map.entry("forge/fml/mc_versions.cfg", "[1.5.2]\nmcp_ver = 7.51\nmcp_url = http://example.invalid/mcp751.zip\nmcp_md5 = a8c68db8736227c9e26cbcb0995e5b71\n"),
            Map.entry("forge/fml/conf/mcp.cfg", "[DEFAULT]\n"),
            Map.entry("forge/fml/conf/joined.srg", "CL: a net/minecraft/Test\n"),
            Map.entry("forge/fml/conf/joined.exc", "net/minecraft/src/Item.<init>(I)V=|wrongName\n"),
            Map.entry("forge/fml/conf/fields.csv", "searge,name,side,desc\nfield_1_test,test,0,\n"),
            Map.entry("forge/fml/conf/methods.csv", "searge,name,side,desc\nfunc_1_test,test,0,\n"),
            Map.entry("forge/fml/conf/params.csv", "param,name,side\np_1_0_,value,0\n"),
            Map.entry("forge/fml/common/fml_at.cfg", "public net.minecraft.Test\n"),
            Map.entry("forge/common/forge_at.cfg", "public net.minecraft.Test\n"),
            Map.entry("forge/fml/mcp_merge.cfg", "merge config\n"),
            Map.entry("forge/fml/commands.patch", "MCP command patch\n"),
            Map.entry("forge/fml/common/example/Example.java", "package example;\n"),
            Map.entry("forge/common/example/ForgeExample.java", "package example;\n")
        ));
        assertEquals("20130617-1.5.2", PythonForgePre16Source.getSnapshotVersion(source));
        writeZip(mcp, Map.of(
            "conf/mcp.cfg", "MCP defaults\n",
            "conf/client.srg", "client mappings\n",
            "runtime/commands.py", "MCP runtime\n"
        ));
        writeZip(srg, Map.of(
            "joined.srg", "CL: a net/minecraft/item/Item\n",
            "joined.exc", "net/minecraft/item/Item.<init>(I)V=|p_i3659_1_\n"
        ));

        var distribution = PythonForgePre16Source.readMcpDistribution(source, "1.5.2");
        assertEquals("7.51", distribution.version());
        assertEquals("mcp751.zip", distribution.filename());
        assertEquals("a8c68db8736227c9e26cbcb0995e5b71", distribution.md5());

        PythonForgePre16Source.prepare(assets, preparedMcp, source, mcp, srg);

        try (var zip = new ZipFile(preparedMcp)) {
            assertTrue(zip.getEntry("runtime/commands.py") != null);
            assertTrue(zip.getEntry("conf/joined.srg") != null);
            assertTrue(zip.getEntry("conf/fields.csv") != null);
            try (var input = zip.getInputStream(zip.getEntry("conf/joined.srg"))) {
                assertEquals("CL: a net/minecraft/item/Item\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var input = zip.getInputStream(zip.getEntry("conf/joined.exc"))) {
                assertEquals("net/minecraft/item/Item.<init>(I)V=|p_i3659_1_\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        try (var zip = new ZipFile(assets)) {
            assertTrue(zip.getEntry("fmlpatches.zip") != null);
            assertTrue(zip.getEntry("forgepatches.zip") != null);
            assertTrue(zip.getEntry("overlays/fml/example/Example.java") != null);
            assertTrue(zip.getEntry("overlays/forge/example/ForgeExample.java") != null);
        }
    }

    @Test
    void keepsThePythonUserdevConversionSourceArchive() throws Exception {
        var source = new File(this.temp.toFile(), "python-src.zip");
        var output = new File(this.temp.toFile(), "userdev.jar");
        writeZip(source, Map.ofEntries(
            Map.entry("forge/fml/fml.json", "{}"),
            Map.entry("forge/fml/conf/fields.csv", "fields"),
            Map.entry("forge/fml/conf/methods.csv", "methods"),
            Map.entry("forge/fml/conf/params.csv", "params"),
            Map.entry("forge/fml/common/fml_at.cfg", "fml at"),
            Map.entry("forge/common/forge_at.cfg", "forge at"),
            Map.entry("forge/fml/patches/minecraft/example.patch", "fml patch"),
            Map.entry("forge/patches/minecraft/example.patch", "forge patch"),
            Map.entry("forge/fml/common/example/Fml.java", "class Fml {}"),
            Map.entry("forge/common/example/Forge.java", "class Forge {}")
        ));

        PythonForgeSource.prepare(output, source);

        try (var zip = new ZipFile(output)) {
            assertTrue(zip.getEntry("dev.json") != null);
            assertTrue(zip.getEntry("mappings/fields.csv") != null);
            assertTrue(zip.getEntry("src/main/resources/fml_at.cfg") != null);
            assertTrue(zip.getEntry("fmlpatches.zip") != null);
            assertTrue(zip.getEntry("forgepatches.zip") != null);
            assertTrue(zip.getEntry("src/main/java/example/Fml.java") != null);
            assertTrue(zip.getEntry("src/main/java/example/Forge.java") != null);
        }
    }

    @Test
    void appliesLegacyPatchAndFailsWhenItDoesNotApply() throws Exception {
        var patches = new File(this.temp.toFile(), "patches.zip");
        var contents = "--- ../src-base/minecraft/example/Test.java\n"
            + "+++ ../src-work/minecraft/example/Test.java\n"
            + "@@ -1,1 +1,2 @@\n"
            + " package example;\n"
            + "+class Test {}\n";
        writeZip(patches, Map.of("example/Test.java.patch", contents));

        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("example/Test.java", "package example;\n".getBytes(StandardCharsets.UTF_8));
        PythonForgePipeline.applyPatches(sources, patches);
        assertEquals("package example;\nclass Test {}\n", new String(sources.get("example/Test.java"), StandardCharsets.UTF_8));

        var badPatch = new File(this.temp.toFile(), "bad-patches.zip");
        writeZip(badPatch, Map.of(
            "example/Absent.java.patch",
            "--- ../src-base/minecraft/example/Absent.java\n"
                + "+++ ../src-work/minecraft/example/Absent.java\n"
                + "@@ -1 +0,0 @@\n"
                + "-package example;\n"
        ));
        assertThrows(IllegalStateException.class, () -> PythonForgePipeline.applyPatches(new LinkedHashMap<>(), badPatch));
    }

    @Test
    void appliesPre16FmlPatchToFernflowerStageSource() throws Exception {
        var patches = new File(this.temp.toFile(), "fml-item-map-patches.zip");
        writeZip(patches, Map.of(
            "net/minecraft/item/ItemMap.java.patch",
            "--- ../src-base/minecraft/net/minecraft/item/ItemMap.java\n"
                + "+++ ../src-work/minecraft/net/minecraft/item/ItemMap.java\n"
                + "@@ -1,6 +1,6 @@\n"
                + "             int var5 = 128 * (1 << var4.field_76197_d);\n"
                + "             var4.field_76201_a = Math.round((float)p_77873_2_.func_72912_H().func_76079_c() / (float)var5) * var5;\n"
                + "             var4.field_76199_b = Math.round((float)(p_77873_2_.func_72912_H().func_76074_e() / var5)) * var5;\n"
                + "-            var4.field_76200_c = (byte)p_77873_2_.field_73011_w.field_76574_g;\n"
                + "+            var4.field_76200_c = p_77873_2_.field_73011_w.field_76574_g;\n"
                + "             var4.func_76185_a();\n"
                + "             p_77873_2_.func_72823_a(var3, var4);\n"
        ));

        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("net/minecraft/item/ItemMap.java",
            ("         int var5 = 128 * (1 << var4.field_76197_d);\n"
                + "         var4.field_76201_a = Math.round((float)p_77873_2_.func_72912_H().func_76079_c() / (float)var5) * var5;\n"
                + "         var4.field_76199_b = Math.round((float)(p_77873_2_.func_72912_H().func_76074_e() / var5)) * var5;\n"
                + "         var4.field_76200_c = (byte)p_77873_2_.field_73011_w.field_76574_g;\n"
                + "         var4.func_76185_a();\n"
                + "         p_77873_2_.func_72823_a(var3, var4);\n").getBytes(StandardCharsets.UTF_8));

        PythonForgePipeline.applyPatches(sources, patches);
        assertTrue(new String(sources.get("net/minecraft/item/ItemMap.java"), StandardCharsets.UTF_8)
            .contains("var4.field_76200_c = p_77873_2_.field_73011_w.field_76574_g;"));
    }

    @Test
    void renamesMcpParametersBeforeApplyingForgePatches() throws Exception {
        var mappings = new File(this.temp.toFile(), "mappings.zip");
        writeZip(mappings, Map.of(
            "methods.csv", "searge,name,side,desc\nfunc_77659_a,onItemRightClick,2,\n",
            "params.csv", "param,name,side\np_77659_1_,par1ItemStack,2\np_77659_2_,par2World,2\np_77659_3_,par3EntityPlayer,2\n"
        ));

        var input = new File(this.temp.toFile(), "fml-sources.jar");
        writeZip(input, Map.of(
            "net/minecraft/item/ItemBucket.java",
            "public ItemStack func_77659_a(ItemStack p_77659_1_, World p_77659_2_, EntityPlayer p_77659_3_)\n"
                + "{\n"
                + "    return p_77659_1_;\n"
                + "}\n"
        ));

        var renamed = new File(this.temp.toFile(), "renamed-fml-sources.jar");
        LegacyRenamer.rename(input, mappings, renamed, false);
        var sources = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipFile(renamed)) {
            var entry = zip.getEntry("net/minecraft/item/ItemBucket.java");
            try (var source = zip.getInputStream(entry)) {
                sources.put(entry.getName(), source.readAllBytes());
            }
        }
        var forgePatches = new File(this.temp.toFile(), "forge-patches.zip");
        writeZip(forgePatches, Map.of(
            "net/minecraft/item/ItemBucket.java.patch",
            "--- ../src_base/minecraft/net/minecraft/item/ItemBucket.java\n"
                + "+++ ../src_work/minecraft/net/minecraft/item/ItemBucket.java\n"
                + "@@ -1,4 +1,4 @@\n"
                + " public ItemStack onItemRightClick(ItemStack par1ItemStack, World par2World, EntityPlayer par3EntityPlayer)\n"
                + " {\n"
                + "-    return par1ItemStack;\n"
                + "+    return par2World == null ? par1ItemStack : par1ItemStack;\n"
                + " }\n"
        ));

        PythonForgePipeline.applyPatches(sources, forgePatches);
        assertEquals(
            "public ItemStack onItemRightClick(ItemStack par1ItemStack, World par2World, EntityPlayer par3EntityPlayer)\n"
                + "{\n"
                + "    return par2World == null ? par1ItemStack : par1ItemStack;\n"
                + "}\n",
            new String(sources.get("net/minecraft/item/ItemBucket.java"), StandardCharsets.UTF_8)
        );
    }

    @Test
    void preservesAnnotationOrderForForgePatchAfterMcpJavadocsAreAdded() throws Exception {
        var mappings = new File(this.temp.toFile(), "mappings.zip");
        writeZip(mappings, Map.of(
            "methods.csv", "searge,name,side,desc\nfunc_74743_b,removeTag,2,Removes a tag at the given index.\n"
        ));

        var input = new File(this.temp.toFile(), "fml-sources.jar");
        writeZip(input, Map.of(
            "net/minecraft/nbt/NBTTagList.java",
            "package net.minecraft.nbt;\n"
                + "\n"
                + "import cpw.mods.fml.relauncher.Side;\n"
                + "import cpw.mods.fml.relauncher.SideOnly;\n"
                + "import java.io.DataInput;\n"
                + "import java.io.DataOutput;\n"
                + "import java.io.IOException;\n"
                + "\n"
                + "    public void appendTag(NBTBase par1NBTBase)\n"
                + "    {\n"
                + "        this.tagList.add(par1NBTBase);\n"
                + "    }\n"
                + "\n"
                + "    @SideOnly(Side.CLIENT)\n"
                + "    public NBTBase func_74743_b(int p_74743_1_)\n"
                + "    {\n"
                + "        return (NBTBase)this.tagList.remove(p_74743_1_);\n"
                + "    }\n"
        ));

        var renamed = new File(this.temp.toFile(), "renamed-fml-sources.jar");
        LegacyRenamer.renameFG_1_0(input, mappings, renamed);
        var sources = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipFile(renamed)) {
            var entry = zip.getEntry("net/minecraft/nbt/NBTTagList.java");
            try (var source = zip.getInputStream(entry)) {
                sources.put(entry.getName(), source.readAllBytes());
            }
        }

        var forgePatches = new File(this.temp.toFile(), "forge-patches.zip");
        writeZip(forgePatches, Map.of(
            "net/minecraft/nbt/NBTTagList.java.patch",
            "--- ../src_base/minecraft/net/minecraft/nbt/NBTTagList.java\n"
                + "+++ ../src_work/minecraft/net/minecraft/nbt/NBTTagList.java\n"
                + "@@ -1,7 +1,5 @@\n"
                + " package net.minecraft.nbt;\n"
                + " \n"
                + "-import cpw.mods.fml.relauncher.Side;\n"
                + "-import cpw.mods.fml.relauncher.SideOnly;\n"
                + " import java.io.DataInput;\n"
                + " import java.io.DataOutput;\n"
                + " import java.io.IOException;\n"
                + "@@ -92,8 +90,6 @@\n"
                + "         this.tagList.add(par1NBTBase);\n"
                + "     }\n"
                + " \n"
                + "-    @SideOnly(Side.CLIENT)\n"
                + "-\n"
                + "     /**\n"
                + "      * Removes a tag at the given index.\n"
                + "      */\n"
        ));

        PythonForgePipeline.applyPatches(sources, forgePatches);
        var patched = new String(sources.get("net/minecraft/nbt/NBTTagList.java"), StandardCharsets.UTF_8);
        assertTrue(patched.contains("     * Removes a tag at the given index.\n     */\n    public NBTBase removeTag"));
        assertTrue(!patched.contains("@SideOnly(Side.CLIENT)"));
    }

    @Test
    void appliesForgeClassFieldPatchWithFg1RenamerFormatting() throws Exception {
        var mappings = new File(this.temp.toFile(), "item-in-world-mappings.zip");
        writeZip(mappings, Map.of(
            "fields.csv", "searge,name,side,desc\nfield_73092_a,theWorld,2,The world object that this object is connected to.\n"
        ));
        var input = new File(this.temp.toFile(), "item-in-world-fml.jar");
        writeZip(input, Map.of(
            "net/minecraft/item/ItemInWorldManager.java",
            "package net.minecraft.item;\n"
                + "\n"
                + "import net.minecraft.world.World;\n"
                + "\n"
                + "public class ItemInWorldManager\n"
                + "{\n"
                + "    public World field_73092_a;\n"
        ));

        var renamed = new File(this.temp.toFile(), "item-in-world-renamed.jar");
        LegacyRenamer.renameFG_1_0(input, mappings, renamed);
        var sources = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipFile(renamed)) {
            var entry = zip.getEntry("net/minecraft/item/ItemInWorldManager.java");
            try (var source = zip.getInputStream(entry)) {
                sources.put(entry.getName(), source.readAllBytes());
            }
        }

        var patches = new File(this.temp.toFile(), "item-in-world-patches.zip");
        writeZip(patches, Map.of(
            "net/minecraft/item/ItemInWorldManager.java.patch",
            "--- ../src_base/minecraft/net/minecraft/item/ItemInWorldManager.java\n"
                + "+++ ../src_work/minecraft/net/minecraft/item/ItemInWorldManager.java\n"
                + "@@ -1,4 +1,7 @@\n"
                + " public class ItemInWorldManager\n"
                + " {\n"
                + "+    /** Forge reach distance */\n"
                + "+    private double blockReachDistance = 5.0d;\n"
                + "+\n"
                + "     /** The world object that this object is connected to. */\n"
                + "     public World theWorld;\n"
        ));

        PythonForgePipeline.applyPatches(sources, patches);
        assertTrue(new String(sources.get("net/minecraft/item/ItemInWorldManager.java"), StandardCharsets.UTF_8)
            .contains("{\n    /** Forge reach distance */\n    private double blockReachDistance = 5.0d;\n\n"
                + "    /** The world object that this object is connected to. */"));
    }

    @Test
    void restoresEmbeddedAudioClassesInsteadOfCompilingTheirDecompiledSources() throws Exception {
        var client = new File(this.temp.toFile(), "client.jar");
        var jorbisClass = new byte[]{1, 2, 3};
        var soundClass = new byte[]{4, 5, 6};
        writeZip(client, Map.of(
            "com/jcraft/jorbis/Codec.class", new String(jorbisClass, StandardCharsets.ISO_8859_1),
            "paulscode/sound/SoundSystem.class", new String(soundClass, StandardCharsets.ISO_8859_1),
            "net/minecraft/Test.class", "not copied"
        ));
        var sources = new LinkedHashMap<String, byte[]>();
        sources.put("com/jcraft/jorbis/Codec.java", "decompiled".getBytes(StandardCharsets.UTF_8));
        sources.put("paulscode/sound/SoundSystem.java", "decompiled".getBytes(StandardCharsets.UTF_8));
        sources.put("org/bouncycastle/crypto/io/CipherInputStream.java", "decompiled".getBytes(StandardCharsets.UTF_8));
        sources.put("net/minecraft/Test.java", "class Test {}".getBytes(StandardCharsets.UTF_8));

        PythonForgePipeline.restoreEmbeddedLibraries(sources, client);

        assertTrue(!sources.containsKey("com/jcraft/jorbis/Codec.java"));
        assertTrue(!sources.containsKey("paulscode/sound/SoundSystem.java"));
        assertTrue(!sources.containsKey("org/bouncycastle/crypto/io/CipherInputStream.java"));
        assertArrayEquals(jorbisClass, sources.get("com/jcraft/jorbis/Codec.class"));
        assertArrayEquals(soundClass, sources.get("paulscode/sound/SoundSystem.class"));
        assertTrue(!sources.containsKey("net/minecraft/Test.class"));
        assertTrue(sources.containsKey("net/minecraft/Test.java"));
    }

    @Test
    void fixesLegacyDecompilerAndForgePatchSourceErrors() {
        var sources = new LinkedHashMap<String, byte[]>();
        putSource(sources, "net/minecraft/client/renderer/RenderGlobal.java", "Object object = null;\n");
        putSource(sources, "net/minecraft/server/integrated/IntegratedServer.java", "protected void tick()\n");
        putSource(sources, "net/minecraft/server/dedicated/DedicatedServer.java", "protected void updateTimeLightAndEntities()\n");
        putSource(sources, "net/minecraft/world/gen/structure/ComponentStrongholdRightTurn.java",
            "public class ComponentStrongholdRightTurn extends ComponentStrongholdLeftTurn\n{\n");
        putSource(sources, "net/minecraft/client/audio/CodecMus.java",
            "import java.io.InputStream;\n"
                + "class CodecMus {\n"
                + "    protected InputStream openInputStream()\n"
                + "    {\n"
                + "        return new MusInputStream(this, this.url, this.urlConnection.getInputStream());\n"
                + "    }\n"
                + "}\n");
        putSource(sources, "net/minecraft/crash/CallableSuspiciousClasses.java",
            "class CallableSuspiciousClasses {\n"
                + "    public String callSuspiciousClasses()\n"
                + "    {\n"
                + "        StringBuilder stringbuilder = new StringBuilder();\n"
                + "        Field field = ClassLoader.class.getDeclaredField(\"classes\");\n"
                + "        field.setAccessible(true);\n"
                + "        ArrayList arraylist = new ArrayList((Vector)field.get(CrashReport.class.getClassLoader()));\n"
                + "        return \"\";\n"
                + "    }\n"
                + "}\n");

        PythonForgePipeline.fixPythonCompileErrors(sources);

        assertSourceContains(sources, "net/minecraft/client/renderer/RenderGlobal.java", "EntityFX object = null;");
        assertSourceContains(sources, "net/minecraft/server/integrated/IntegratedServer.java", "public void tick()");
        assertSourceContains(sources, "net/minecraft/server/dedicated/DedicatedServer.java", "public void updateTimeLightAndEntities()");
        assertSourceContains(sources, "net/minecraft/world/gen/structure/ComponentStrongholdRightTurn.java",
            "super(par1, par2Random, par3StructureBoundingBox, par4);");
        assertSourceContains(sources, "net/minecraft/client/audio/CodecMus.java",
            "import java.io.IOException;\nimport java.io.InputStream;");
        assertSourceContains(sources, "net/minecraft/client/audio/CodecMus.java",
            "catch (IOException e)");
        assertSourceContains(sources, "net/minecraft/crash/CallableSuspiciousClasses.java",
            "catch (Exception e)");
        assertSourceContains(sources, "net/minecraft/crash/CallableSuspiciousClasses.java",
            "return \"\";");
    }

    private static void putSource(Map<String, byte[]> sources, String path, String source) {
        sources.put(path, source.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSourceContains(Map<String, byte[]> sources, String path, String text) {
        assertTrue(new String(sources.get(path), StandardCharsets.UTF_8).contains(text));
    }

    private static void writeZip(File file, Map<String, String> entries) throws Exception {
        try (var zip = new ZipOutputStream(new java.io.FileOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(Instant.parse("2013-06-17T12:00:00Z").toEpochMilli());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
