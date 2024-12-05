/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// TODO: [MCMaven][Documentation] Document from MinecraftForge/MinecraftForge
public class PromosSlim {
    private String homepage;
    private Map<String, String> promos;
    private transient List<String> versions;

    public Map<String, String> promos() {
        return promos == null ? Collections.emptyMap() : promos;
    }

    public String homepage() {
        return this.homepage;
    }

    public List<String> versions() {
        if (this.versions == null) {
            if (this.promos == null) {
                this.versions = Collections.emptyList();
            } else {
                List<String> tmp = new ArrayList<>();
                for (String key : promos.keySet()) {
                    int idx = key.indexOf('-');
                    if (idx == -1)
                        continue;
                    String ver = key.substring(0, idx) + '-' + promos.get(key);
                    tmp.add(ver);
                }
                this.versions = tmp;
            }
        }
        return this.versions;
    }
}
