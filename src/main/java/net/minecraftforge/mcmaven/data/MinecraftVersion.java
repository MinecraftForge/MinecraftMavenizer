package net.minecraftforge.mcmaven.data;

import java.net.URL;
import java.util.Map;

public class MinecraftVersion {
    public String id;
    public Map<String, Download> downloads;
    public Library[] libraries;

    //TODO: [MCMaven][MinecraftVersion] Add function to filter libraries based on current operating systems
    public static class Library {
        public String name;
        public Downloads downloads;
    }

    public static class Downloads {
        public Map<String, LibraryDownload> classifiers;
        public LibraryDownload artifact;
    }

    public static class Download {
        public String sha1;
        public URL url;
        public int size;
    }

    public static class LibraryDownload extends Download {
        public String path;
    }

    public Download getDownload(String key) {
        return this.downloads == null ? null : this.downloads.get(key);
    }
}
