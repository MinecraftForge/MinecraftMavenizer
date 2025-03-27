package net.minecraftforge.mcmaven.impl.data;

import java.util.List;
import java.util.Map;

public class RunConfig {
    public String name;
    public String main;
    public List<String> parents;
    public List<String> args;
    public List<String> jvmArgs;
    public boolean client;
    public boolean buildAllProjects;
    public Map<String, String> env;
    public Map<String, String> props;
}
