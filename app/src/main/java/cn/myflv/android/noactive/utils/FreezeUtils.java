//
// Decompiled by Jadx - 917ms
//
package cn.myflv.android.noactive.utils;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import cn.myflv.android.noactive.entity.ClassEnum;
import cn.myflv.android.noactive.entity.MethodEnum;
import cn.myflv.android.noactive.server.ProcessRecord;
import de.robv.android.xposed.XposedHelpers;

public class FreezeUtils {

    private final static int CONT = 18;

    private static final int FREEZE_ACTION = 1;
    private static final int UNFREEZE_ACTION = 0;

    private static final String V1_FREEZER_FROZEN_PORCS = "/sys/fs/cgroup/freezer/perf/frozen/cgroup.procs";
    private static final String V1_FREEZER_THAWED_PORCS = "/sys/fs/cgroup/freezer/perf/thawed/cgroup.procs";

    // v6: V2 路径候选（apps 子路径优先，原路径 fallback），适配 HyperOS 3.0 cgroup 挂载变化
    private static final String V2_FREEZER_BASE_APPS = "/sys/fs/cgroup/apps";
    private static final String V2_FREEZER_BASE_DEFAULT = "/sys/fs/cgroup";

    // v6: 静态持有 classLoader 用于 fallbackToApi（V2 失败时调用系统 API）
    private static ClassLoader sClassLoader;

    private final boolean freezerApi;
    private final int freezerVersion;
    private final int stopSignal;
    private final boolean useKill;
    private final ClassLoader classLoader;


    public FreezeUtils(ClassLoader classLoader) {
        this.classLoader = classLoader;
        FreezeUtils.sClassLoader = classLoader;
        String freezerVersion = FreezerConfig.getFreezerVersion(classLoader);
        switch (freezerVersion) {
            case FreezerConfig.API:
                this.freezerApi = true;
                this.freezerVersion = 2;
                break;
            case FreezerConfig.V2:
                this.freezerApi = false;
                this.freezerVersion = 2;
                break;
            case FreezerConfig.V1:
            default:
                this.freezerApi = false;
                this.freezerVersion = 1;
        }
        this.stopSignal = FreezerConfig.getKillSignal();
        this.useKill = FreezerConfig.isUseKill();
        if (useKill) {
            Log.i("Kill -" + stopSignal);
        } else {
            Log.i("Freezer " + freezerVersion);
        }
    }


    public static List<Integer> getFrozenPids() {
        List<Integer> pids = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(V1_FREEZER_FROZEN_PORCS));
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                try {
                    pids.add(Integer.parseInt(line));
                } catch (NumberFormatException ignored) {
                }
            }
            reader.close();
        } catch (IOException ignored) {
        }
        return pids;
    }

    public void freezer(ProcessRecord processRecord) {
        if (useKill) {
            Process.sendSignal(processRecord.getPid(), stopSignal);
        } else {
            if (freezerVersion == 2) {
                if (freezerApi) {
                    setProcessFrozen(processRecord.getPid(), processRecord.getUid(), true);
                } else {
                    freezePid(processRecord.getPid(), processRecord.getUid());
                }
            } else {
                freezePid(processRecord.getPid());
            }
        }
    }

    public void unFreezer(ProcessRecord processRecord) {
        if (useKill) {
            Process.sendSignal(processRecord.getPid(), CONT);
        } else {
            if (freezerVersion == 2) {
                if (freezerApi) {
                    setProcessFrozen(processRecord.getPid(), processRecord.getUid(), false);
                } else {
                    thawPid(processRecord.getPid(), processRecord.getUid());
                }
            } else {
                thawPid(processRecord.getPid());
            }
        }
    }

    public static boolean isFrozonPid(int pid) {
        return getFrozenPids().contains(pid);
    }


    public static void freezePid(int pid) {
        writeNode(V1_FREEZER_FROZEN_PORCS, pid);
    }


    public static void thawPid(int pid) {
        writeNode(V1_FREEZER_THAWED_PORCS, pid);
    }


    private static void writeNode(String path, int val) {
        try {
            PrintWriter writer = new PrintWriter(path);
            writer.write(Integer.toString(val));
            writer.close();
        } catch (IOException e) {
            Log.e("Freezer V1 failed: " + e.getMessage());
        }
    }


    // v6: 双路径探测 + V2 失败时 fallback 到系统 API
    // - 路径 0 (apps): /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze  (HyperOS 3.0)
    // - 路径 1 (默认): /sys/fs/cgroup/uid_<uid>/pid_<pid>/cgroup.freeze         (旧版)
    // 探测失败时默认用路径 0 并报错，V2 写入失败时调用 fallbackToApi
    private static void setFreezeAction(int pid, int uid, boolean action) {
        String suffix = "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze";
        String[] candidates = new String[]{
                V2_FREEZER_BASE_APPS + suffix,
                V2_FREEZER_BASE_DEFAULT + suffix
        };
        String path = null;
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                path = candidate;
                break;
            }
        }
        if (path == null) {
            path = candidates[0];
            Log.e("Freezer V2 path probe failed, fallback to: " + path);
        }
        try {
            PrintWriter writer = new PrintWriter(path);
            if (action) {
                writer.write(Integer.toString(FREEZE_ACTION));
            } else {
                writer.write(Integer.toString(UNFREEZE_ACTION));
            }
            writer.close();
        } catch (IOException e) {
            Log.e("Freezer V2 failed: " + e.getMessage());
            fallbackToApi(pid, uid, action);
        }
    }

    // v6: V2 写入失败时回退到系统 API (Process.setProcessFrozen)
    private static void fallbackToApi(int pid, int uid, boolean frozen) {
        if (sClassLoader == null) {
            Log.e("Freezer API fallback skipped: classLoader not initialized");
            return;
        }
        try {
            Class<?> Process = XposedHelpers.findClass(ClassEnum.Process, sClassLoader);
            XposedHelpers.callStaticMethod(Process, MethodEnum.setProcessFrozen, pid, uid, frozen);
            Log.i("Freezer V2 fallback to API succeeded: pid=" + pid + " uid=" + uid + " frozen=" + frozen);
        } catch (Throwable e) {
            Log.e("Freezer API fallback failed: " + e.getMessage());
        }
    }

    public static void thawPid(int pid, int uid) {
        setFreezeAction(pid, uid, false);
    }


    public static void freezePid(int pid, int uid) {
        setFreezeAction(pid, uid, true);
    }

    public static void kill(int pid) {
        Process.killProcess(pid);
    }

    public void setProcessFrozen(int pid, int uid, boolean frozen) {
        Class<?> Process = XposedHelpers.findClass(ClassEnum.Process, classLoader);
        XposedHelpers.callStaticMethod(Process, MethodEnum.setProcessFrozen, pid, uid, frozen);
    }
}
