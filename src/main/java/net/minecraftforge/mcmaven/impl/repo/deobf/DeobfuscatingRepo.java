package net.minecraftforge.mcmaven.impl.repo.deobf;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.repo.Repo;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;

@ApiStatus.Experimental
public class DeobfuscatingRepo extends Repo {
    public DeobfuscatingRepo(Cache cache, File output) {
        super(cache, output);
    }

    @Override
    public void process(String module, String version) {
        throw new UnsupportedOperationException("DeobfuscatingRepo is not implemented yet");
    }
}
