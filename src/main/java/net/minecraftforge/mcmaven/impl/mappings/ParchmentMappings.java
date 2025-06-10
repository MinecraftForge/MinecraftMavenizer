/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.mappings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;

public class ParchmentMappings extends Mappings {
    private Task downloadTask;

    public ParchmentMappings(String version) {
        super("parchment", version);
        if (version.contains("-SNAPSHOT"))
            throw new IllegalArgumentException("Parchment snapshots are not supported: " + version);
    }

    @Override
    public boolean isPrimary() {
        return false;
    }

    // Maybe download the maven-metadata.xml for the MC version and pick the latest one?
    @Override
    public Mappings withMCVersion(String mcVer) {
        if (this.version().indexOf('-') != -1) // assume our version specifies the MC version
            return this;
        return new ParchmentMappings(mcVer + '-' + this.version());
    }

    @Override
    public Task getCsvZip(MCPSide side) {
        var ret = tasks.get(side);
        if (ret != null)
            return ret;

        var mc = side.getMCP().getMinecraftTasks();
        var srg = side.getTasks().getMappings();

        var client = mc.versionFile("client_mappings", "txt");
        var server = mc.versionFile("server_mappings", "txt");
        var data = downloadTask(side.getMCP());

        ret = Task.named("srg2names[" + this + ']',
            Set.of(srg, client, server, data).stream().filter(e -> e != null).toList(),
            () -> getMappings(side.getMCP(), srg, client, server, data)
        );
        tasks.put(side, ret);

        return ret;
    }

    private Task downloadTask(MCP mcp) {
        if (downloadTask == null) {
            downloadTask = Task.named("download[" + version() + "][parchment]",
                Set.of(),
                () -> download(mcp.getCache())
            );
        }
        return downloadTask;
    }

    private File download(Cache cache) {
        var maven = new MavenCache("parchment", Constants.PARCHMENT_MAVEN, cache.root());
        var idx = version().indexOf('-');
        if (idx == -1)
            throw new IllegalStateException("Unknown Parchment version: " + version());
        var mcversion = version().substring(0, idx);
        var ver = version().substring(idx + 1);
        var artifact = Artifact.from(Constants.PARCHMENT_GROUP, "parchment-" + mcversion, ver, "checked").withExtension("zip");
        return maven.download(artifact);
    }

    private File getMappings(MCP mcp, Task srgTask, Task clientTask, Task serverTask, Task dataTask) throws IOException {
        var srg = srgTask.execute();
        var client = clientTask.execute();
        var server = serverTask.execute();
        var data = dataTask.execute();

        var root = getFolder(new File(mcp.getBuildFolder(), "data/mapings"));
        var output = new File(root, "parchment" + version() + ".zip");
        var cache = HashStore.fromFile(output)
            .add("srg", srg)
            .add("client", client)
            .add("server", server)
            .add("data", data);


        if (output.exists() && cache.isSame())
            return output;

        ParchmentData json = null;
        try (var zip = new ZipFile(data)) {
            var entry = zip.getEntry("parchment.json");
            if (entry == null)
                throw new IllegalStateException("Invalid parchment data archive, missing parchment.json: " + data.getAbsolutePath());
            json = ParchmentData.load(zip.getInputStream(entry));
            json.bake();
        } catch (IOException e) {
            Util.sneak(e);
        }

        var obf2mojClient = IMappingFile.load(client).reverse();
        var obf2mojServer = IMappingFile.load(server).reverse();

        var obf2srg = IMappingFile.load(srg);

        var clientData = gather(obf2srg, obf2mojClient, json, true);
        var serverData = gather(obf2srg, obf2mojServer, json, false);

        record Type(String file, Function<SideData, Map<String, Info>> data) {}
        var types = new Type[] {
            new Type("packages.csv", SideData::packages),
            new Type("classes.csv", SideData::classes),
            new Type("fields.csv", SideData::fields),
            new Type("methods.csv", SideData::methods),
            new Type("params.csv", SideData::params)
        };


        FileUtils.ensureParent(output);
        try (var fos = new FileOutputStream(output);
             var out = new ZipOutputStream(fos)) {
            out.setLevel(Deflater.NO_COMPRESSION); // Don't compress in case the system has custom zlib library, which will case hash differences

            var uncloseable = new OutputStreamWriter(out, StandardCharsets.UTF_8) {
                public void close() throws IOException {
                    this.flush();
                }
            };

            for (var type : types) {
                var entries = getEntries(type.file, type.data.apply(clientData), type.data.apply(serverData));
                if (entries.size() <= 1)
                    continue;

                out.putNextEntry(Util.getStableEntry(type.file));

                try (var writer = CsvWriter.builder()
                        .lineDelimiter(LineDelimiter.LF)
                        .build(uncloseable)) {
                    for (var row : entries)
                        writer.writeRecord(row);
                }

                out.closeEntry();
            }
        }

        cache.save();
        return output;
    }

    private static List<String[]> getEntries(String file, Map<String, Info> cData, Map<String, Info> sData) {
        var header = new String[] {"searge", "name", "side", "desc"};
        if ("params.csv".equals(file))
            header[0] = "param";
        var ret = new ArrayList<String[]>();
        ret.add(header);

        for (var key : cData.keySet()) {
            var c = cData.get(key);
            var s = sData.get(key);
            if (c.equals(s)) {
                ret.add(new String[] { key, c.name, "2", c.desc });
                sData.remove(key);
            } else
                ret.add(new String[] { key, c.name, "2", c.desc });
        }

        for (var entry : sData.entrySet()) {
            var value = entry.getValue();
            ret.add(new String[] { entry.getKey(), value.name, "1", value.desc });
        }

        return ret;
    }

    private record Info(String name, String desc) {}
    private record SideData(
        Map<String, Info> packages,
        Map<String, Info> classes,
        Map<String, Info> fields,
        Map<String, Info> methods,
        Map<String, Info> params
    ) {
        SideData(){
            this(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        }
    }

    private static SideData gather(IMappingFile obf2srg, IMappingFile obf2moj, ParchmentData parchment, boolean limited) {
        var ret = new SideData();
        if (parchment.packages != null) {
            for (var pkg : parchment.packages) {
                var name = pkg.name + '/';
                var found = false;
                for (var cls : obf2moj.getClasses()) {
                    if (cls.getMapped().startsWith(name)) {
                        found = true;
                        break;
                    }
                }
                if (found)
                    ret.packages.put(pkg.name, new Info(pkg.name, getJavadocs(pkg.javadoc)));
            }
        }

        for (var mojCls : obf2moj.getClasses()) {
            var srgCls = obf2srg.getClass(mojCls.getOriginal());
            var parchCls = parchment.classMap.get(mojCls.getMapped());
            var srgClsName = srgCls == null ? mojCls.getMapped() : srgCls.getMapped();
            add(ret.classes, srgClsName, mojCls.getMapped(), parchCls);

            for (var mojFld : mojCls.getFields()) {
                var srgFld     = srgCls   == null ? null : srgCls.getField(mojFld.getOriginal());
                var parchFld   = parchCls == null ? null : parchCls.fieldMap.get(mojFld.getMapped());
                var srgFldName = srgFld   == null ? mojFld.getMapped() : srgFld.getMapped();
                if (srgFldName.startsWith("field_") || srgFldName.startsWith("f_"))
                    add(ret.fields, srgFldName, mojFld.getMapped(), parchFld);
            }

            for (var mojMtd : mojCls.getMethods()) {
                var srgMtd     = srgCls   == null ? null : srgCls.getMethod(mojMtd.getOriginal(), mojMtd.getDescriptor());
                var parchMtd   = parchCls == null ? null : parchCls.methodMap.get(mojMtd.getMapped() + mojMtd.getMappedDescriptor());
                var srgMtdName = srgMtd   == null ? mojMtd.getMapped() : srgMtd.getMapped();

                if (srgMtdName.startsWith("func_") || srgMtdName.startsWith("m_"))
                    add(ret.methods, srgMtdName, mojMtd.getMapped(), parchMtd);

                if (srgMtd == null || srgMtd.getParameters().isEmpty() || parchMtd == null)
                    continue;

                var isStatic = srgMtd.getMetadata().containsKey("is_static");
                var types = getParameters(mojMtd.getDescriptor());
                for (var srgParam : srgMtd.getParameters()) {
                    var jvmIndex = isStatic ? 0 : 1;
                    int idx = 0;
                    while (idx < srgParam.getIndex()) {
                        var type = types.get(idx++);
                        if (type.equals("J") || type.equals("D"))
                            jvmIndex += 2;
                        else
                            jvmIndex++;
                    }

                    var parchParam = parchMtd.paramMap.get(jvmIndex);
                    if (parchParam != null && srgParam.getMapped().startsWith("p_"))
                        add(ret.params, srgParam.getMapped(), parchParam.name, parchParam);
                }
            }
        }

        return ret;
    }

    private static void add(Map<String, Info> map, String orig, String mapped, ParchmentData.Element element) {
        var desc = element == null ? null : getJavadocs(element.javadoc);
        if (!orig.equals(mapped) || desc != null)
            map.put(orig, new Info(mapped, desc));
    }

    private static String getJavadocs(List<String> javadoc) {
        if (javadoc == null || javadoc.isEmpty())
            return null;

        var sb = new StringBuilder();
        int x = 0;
        for (var line : javadoc) {
            sb.append(line);
            if (x++ != javadoc.size() - 1)
                sb.append("\\n");
        }
        return sb.toString();
    }

    private static List<String> getParameters(String desc) {
        var ret = new ArrayList<String>();
        int idx = 1;
        while (desc.charAt(idx) != ')') {
            int current = idx;
            while (desc.charAt(idx) == '[')
                idx++;
            if (desc.charAt(idx++) == 'L') {
                int end = desc.indexOf(';', idx);
                if (end != -1)
                    idx = end;
            }
            ret.add(desc.substring(current, idx));

        }
        return ret;
    }
}
