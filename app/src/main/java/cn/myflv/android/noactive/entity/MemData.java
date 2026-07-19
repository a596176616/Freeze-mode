package cn.myflv.android.noactive.entity;

import android.os.FileObserver;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import cn.myflv.android.noactive.utils.ConfigFileObserver;
import cn.myflv.android.noactive.utils.FreezerConfig;
import cn.myflv.android.noactive.utils.Log;
import lombok.Data;

@Data
public class MemData {
    private Set<String> whiteApps = new HashSet<>();
    private Set<String> blackSystemApps = new HashSet<>();
    private Set<String> whiteProcessList = new HashSet<>();
    private Set<String> killProcessList = new HashSet<>();
    private Set<String> appBackgroundSet = Collections.synchronizedSet(new LinkedHashSet<>());
    private final FileObserver fileObserver = new ConfigFileObserver(this);

    public MemData() {
        fileObserver.startWatching();
        // v6: 从 background.conf 恢复持久化的后台应用列表，确保重启后 R4 refreeze 仍能恢复
        Set<String> savedBackground = FreezerConfig.loadBackground();
        if (!savedBackground.isEmpty()) {
            appBackgroundSet.addAll(savedBackground);
            Log.i("R4: restored " + savedBackground.size() + " background apps from background.conf");
        }
    }

    public int getBackgroundIndex(String packageName) {
        int total = appBackgroundSet.size();
        for (String pkg : appBackgroundSet) {
            if (whiteApps.contains(pkg)) {
                continue;
            }
            if (packageName.equals(pkg)) {
                return total;
            } else {
                total -= 1;
            }
        }
        return total;
    }

}
