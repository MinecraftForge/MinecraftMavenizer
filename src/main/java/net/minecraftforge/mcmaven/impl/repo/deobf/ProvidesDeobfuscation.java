package net.minecraftforge.mcmaven.impl.repo.deobf;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public interface ProvidesDeobfuscation {
    DeobfuscatingRepo getDeobfuscatingRepo() throws IllegalStateException;
}
