package cn.myflv.android.noactive.app;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;

import cn.myflv.android.noactive.entity.ClassEnum;
import cn.myflv.android.noactive.entity.MemData;
import cn.myflv.android.noactive.entity.MethodEnum;
import cn.myflv.android.noactive.hook.ANRHook;
import cn.myflv.android.noactive.hook.AppSwitchHook;
import cn.myflv.android.noactive.hook.BroadcastDeliverHook;
import cn.myflv.android.noactive.hook.CacheFreezerHook;
import cn.myflv.android.noactive.hook.OomAdjHook;
import cn.myflv.android.noactive.utils.FreezerConfig;
import cn.myflv.android.noactive.utils.Log;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Android implements IAppHook {

    @Override
    public void hook(XC_LoadPackage.LoadPackageParam packageParam) {
        // 打印版本与设备信息
        Log.i("NoActive v0.9.10(1009) starting on " + Build.MANUFACTURER + "/" + Build.MODEL + " SDK=" + Build.VERSION.SDK_INT);
        // 类加载器
        ClassLoader classLoader = packageParam.classLoader;
        // 加载内存配置
        MemData memData = new MemData();
        // 禁用暂停执行已缓存
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            try {
                XposedHelpers.findAndHookMethod(ClassEnum.CachedAppOptimizer, classLoader, MethodEnum.useFreezer, new CacheFreezerHook());
                Log.i("Disable cache freezer");
            } catch (Throwable e) {
                // v5: hook 失败时打印候选方法签名便于排查
                Log.e("Failed to hook cache freezer", e);
                dumpMethodSignatures(classLoader, ClassEnum.CachedAppOptimizer, MethodEnum.useFreezer);
            }
        }
        // Hook 切换事件
        hookAppSwitch(packageParam, classLoader, memData);
        // Hook 广播分发
        hookBroadcast(classLoader, memData);
        // Hook oom_adj
        hookOomAdj(classLoader, memData);
        // Hook ANR
        hookANR(classLoader, memData);
        Log.i("Load success");
    }

    // Hook APP 切换事件，v6: 适配 SDK 33/34 updateActivityUsageStats 多种签名，依次尝试
    private void hookAppSwitch(XC_LoadPackage.LoadPackageParam packageParam, ClassLoader classLoader, MemData memData) {
        AppSwitchHook appSwitchHook = new AppSwitchHook(classLoader, memData, AppSwitchHook.DIFFICULT);
        boolean success = false;
        Throwable lastError = null;
        // v5: SDK 34+ 新增 ActivityId 参数
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.U) {
            try {
                XposedHelpers.findAndHookMethod(ClassEnum.ActivityManagerService, classLoader, MethodEnum.updateActivityUsageStats,
                        ClassEnum.ComponentName, int.class, int.class, ClassEnum.IBinder, ClassEnum.ComponentName, ClassEnum.ActivityId, appSwitchHook);
                success = true; Log.i("Hooked updateActivityUsageStats (6 args + ActivityId, SDK >= 34)");
            } catch (Throwable e) {
                lastError = e;
            }
        }
        // v6: SDK 33 fallback，使用 Intent 替代 ActivityId
        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                XposedHelpers.findAndHookMethod(ClassEnum.ActivityManagerService, classLoader, MethodEnum.updateActivityUsageStats,
                        ClassEnum.ComponentName, int.class, int.class, ClassEnum.IBinder, ClassEnum.ComponentName, Intent.class, appSwitchHook);
                success = true; Log.i("Hooked updateActivityUsageStats (6 args + Intent, SDK >= 33)");
            } catch (Throwable e) {
                lastError = e;
            }
        }
        // SDK <= 32 使用 5 args 签名
        if (!success) {
            try {
                XposedHelpers.findAndHookMethod(ClassEnum.ActivityManagerService, classLoader, MethodEnum.updateActivityUsageStats,
                        ClassEnum.ComponentName, int.class, int.class, ClassEnum.IBinder, ClassEnum.ComponentName, appSwitchHook);
                success = true; Log.i("Hooked updateActivityUsageStats (5 args, SDK <= 32)");
            } catch (Throwable e) {
                lastError = e;
            }
        }
        if (!success) {
            Log.e("Failed to hook updateActivityUsageStats on SDK=" + Build.VERSION.SDK_INT + ", app switch detection will not work. Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
            dumpMethodSignatures(classLoader, ClassEnum.ActivityManagerService, MethodEnum.updateActivityUsageStats);
        }
    }

    // Hook 广播分发，v5: SDK 34+ BroadcastQueueImpl 使用 dispatchReceivers 替代 deliverToRegisteredReceiverLocked
    private void hookBroadcast(ClassLoader classLoader, MemData memData) {
        // 候选类数组，依次尝试
        String[] candidateClasses = {ClassEnum.BroadcastQueueImpl, ClassEnum.BroadcastQueueModernImpl, ClassEnum.BroadcastQueue};
        boolean success = false;
        Throwable lastError = null;
        String hookedClassName = null;
        String hookedMethodName = null;
        // v5: SDK 34+ 使用 dispatchReceivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.U) {
            for (String className : candidateClasses) {
                try {
                    XposedHelpers.findAndHookMethod(className, classLoader, MethodEnum.dispatchReceivers,
                            ClassEnum.BroadcastProcessQueue, ClassEnum.BroadcastRecord, int.class, new BroadcastDeliverHook(memData, BroadcastDeliverHook.MODE_DISPATCH));
                    success = true; hookedClassName = className; hookedMethodName = MethodEnum.dispatchReceivers;
                    break;
                } catch (Throwable e) {
                    lastError = e;
                }
            }
        }
        // SDK < 34 或上一分支失败：使用 deliverToRegisteredReceiverLocked
        if (!success) {
            for (String className : candidateClasses) {
                try {
                    XposedHelpers.findAndHookMethod(className, classLoader, MethodEnum.deliverToRegisteredReceiverLocked,
                            ClassEnum.BroadcastRecord, ClassEnum.BroadcastFilter, boolean.class, int.class, new BroadcastDeliverHook(memData, BroadcastDeliverHook.MODE_LEGACY));
                    success = true; hookedClassName = className; hookedMethodName = MethodEnum.deliverToRegisteredReceiverLocked;
                    break;
                } catch (Throwable e) {
                    lastError = e;
                }
            }
        }
        if (success) {
            Log.i("Hooked " + hookedClassName + "." + hookedMethodName);
        } else {
            Log.e("Failed to hook broadcast dispatch on SDK=" + Build.VERSION.SDK_INT + ", broadcast ANR protection will not work. Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
            for (String className : candidateClasses) {
                dumpMethodSignatures(classLoader, className, MethodEnum.dispatchReceivers);
                dumpMethodSignatures(classLoader, className, MethodEnum.deliverToRegisteredReceiverLocked);
            }
        }
    }

    // Hook oom_adj，v5: ColorOS SDK 34+ computeOomAdjLSP 签名变更为 9 args
    private void hookOomAdj(ClassLoader classLoader, MemData memData) {
        // 配置关闭 OOM 调整则跳过
        if (FreezerConfig.isConfigOn(FreezerConfig.disableOOM)) {
            return;
        }
        boolean colorOs = FreezerConfig.isColorOs();
        if (!colorOs && (Build.MANUFACTURER.equals("OPPO") || Build.MANUFACTURER.equals("OnePlus"))) {
            Log.w("If you are using ColorOS");
            Log.w("You can create file color.os");
        }
        boolean success = false;
        Throwable lastError = null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // SDK == R (30) 或 Q (29)
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    XposedHelpers.findAndHookMethod(ClassEnum.OomAdjuster, classLoader, MethodEnum.applyOomAdjLocked,
                            ClassEnum.ProcessRecord, boolean.class, long.class, long.class, new OomAdjHook(classLoader, memData, OomAdjHook.Android_Q_R));
                    success = true; Log.i("Auto lmk");
                }
            } else {
                // SDK >= S
                if (!colorOs) {
                    XposedHelpers.findAndHookMethod(ClassEnum.ProcessStateRecord, classLoader, MethodEnum.setCurAdj,
                            int.class, new OomAdjHook(classLoader, memData, OomAdjHook.Android_S));
                    success = true; Log.i("Auto lmk");
                } else {
                    // v5: ColorOS SDK 34+ 使用 9 args 签名
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.U) {
                        try {
                            XposedHelpers.findAndHookMethod(ClassEnum.OomAdjuster, classLoader, MethodEnum.computeOomAdjLSP,
                                    ClassEnum.ProcessRecord, int.class, ClassEnum.ProcessRecord, boolean.class, long.class, boolean.class, boolean.class, int.class, boolean.class,
                                    new OomAdjHook(classLoader, memData, OomAdjHook.Color));
                            success = true; Log.i("Hooked computeOomAdjLSP (9 args, SDK >= 34)");
                        } catch (Throwable e) {
                            lastError = e;
                        }
                    }
                    // ColorOS legacy fallback：7 args 签名
                    if (!success) {
                        try {
                            XposedHelpers.findAndHookMethod(ClassEnum.OomAdjuster, classLoader, MethodEnum.computeOomAdjLSP,
                                    ClassEnum.ProcessRecord, int.class, ClassEnum.ProcessRecord, boolean.class, long.class, boolean.class, boolean.class,
                                    new OomAdjHook(classLoader, memData, OomAdjHook.Color));
                            success = true; Log.i("Hooked computeOomAdjLSP (7 args, ColorOS legacy)");
                        } catch (Throwable e) {
                            lastError = e;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            lastError = e;
        }
        if (!success) {
            Log.e("Failed to hook OomAdj on SDK=" + Build.VERSION.SDK_INT + ", OOM adjustment will not work. Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    dumpMethodSignatures(classLoader, ClassEnum.OomAdjuster, MethodEnum.applyOomAdjLocked);
                }
            } else {
                if (!colorOs) {
                    dumpMethodSignatures(classLoader, ClassEnum.ProcessStateRecord, MethodEnum.setCurAdj);
                } else {
                    dumpMethodSignatures(classLoader, ClassEnum.OomAdjuster, MethodEnum.computeOomAdjLSP);
                }
            }
        }
    }

    // Hook ANR，v5: SDK 34+ AnrHelper.appNotResponding 签名多次变更，依次尝试
    private void hookANR(ClassLoader classLoader, MemData memData) {
        boolean success = false;
        Throwable lastError = null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.U) {
                // SDK < 34
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {
                        // SDK < Q (Android N-P)
                        XposedHelpers.findAndHookMethod(ClassEnum.AppErrors, classLoader, MethodEnum.appNotResponding,
                                ClassEnum.ProcessRecord, ClassEnum.ActivityRecord, ClassEnum.ActivityRecord, boolean.class, String.class, XC_MethodReplacement.DO_NOTHING);
                        success = true; Log.i("Android N-P"); Log.i("Force keep process");
                    } else {
                        // SDK == Q
                        XposedHelpers.findAndHookMethod(ClassEnum.ProcessRecord, classLoader, MethodEnum.appNotResponding,
                                String.class, ClassEnum.ApplicationInfo, String.class, ClassEnum.WindowProcessController, boolean.class, String.class, XC_MethodReplacement.DO_NOTHING);
                        success = true; Log.i("Android Q"); Log.i("Force keep process");
                    }
                } else {
                    // SDK R-T (30-33)
                    XposedHelpers.findAndHookMethod(ClassEnum.AnrHelper, classLoader, MethodEnum.appNotResponding,
                            ClassEnum.ProcessRecord, String.class, ClassEnum.ApplicationInfo, String.class, ClassEnum.WindowProcessController, boolean.class, String.class, new ANRHook(classLoader, memData));
                    success = true; Log.i("Auto keep process");
                }
            } else {
                // v5: SDK 34+ ANR 签名多次变更，依次尝试
                // v5: SDK 36 (Android 16) - 9 args + ExecutorService + TimeoutRecord
                try {
                    XposedHelpers.findAndHookMethod(ClassEnum.AnrHelper, classLoader, MethodEnum.appNotResponding,
                            ClassEnum.ProcessRecord, String.class, ClassEnum.ApplicationInfo, String.class, ClassEnum.WindowProcessController, boolean.class,
                            ClassEnum.ExecutorService, ClassEnum.TimeoutRecord, boolean.class, new ANRHook(classLoader, memData));
                    success = true; Log.i("Auto keep process (9 args + ExecutorService + TimeoutRecord, SDK 36)");
                } catch (Throwable e) {
                    lastError = e;
                }
                // fallback 1: 8 args + TimeoutRecord (Android 14-15)
                if (!success) {
                    try {
                        XposedHelpers.findAndHookMethod(ClassEnum.AnrHelper, classLoader, MethodEnum.appNotResponding,
                                ClassEnum.ProcessRecord, String.class, ClassEnum.ApplicationInfo, String.class, ClassEnum.WindowProcessController, boolean.class,
                                ClassEnum.TimeoutRecord, boolean.class, new ANRHook(classLoader, memData));
                        success = true; Log.i("Auto keep process (8 args + TimeoutRecord, SDK 14-15 fallback)");
                    } catch (Throwable e) {
                        lastError = e;
                    }
                }
                // fallback 2: 7 args + String (SDK 30-33 legacy)
                if (!success) {
                    try {
                        XposedHelpers.findAndHookMethod(ClassEnum.AnrHelper, classLoader, MethodEnum.appNotResponding,
                                ClassEnum.ProcessRecord, String.class, ClassEnum.ApplicationInfo, String.class, ClassEnum.WindowProcessController, boolean.class, String.class, new ANRHook(classLoader, memData));
                        success = true; Log.i("Auto keep process (7 args + String, SDK 30-33 fallback)");
                    } catch (Throwable e) {
                        lastError = e;
                    }
                }
            }
        } catch (Throwable e) {
            lastError = e;
        }
        if (!success) {
            Log.e("Failed to hook ANR on SDK=" + Build.VERSION.SDK_INT + ", ANR protection will not work. Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {
                    dumpMethodSignatures(classLoader, ClassEnum.AppErrors, MethodEnum.appNotResponding);
                } else {
                    dumpMethodSignatures(classLoader, ClassEnum.ProcessRecord, MethodEnum.appNotResponding);
                }
            } else {
                dumpMethodSignatures(classLoader, ClassEnum.AnrHelper, MethodEnum.appNotResponding);
            }
        }
    }

    // 打印指定类中指定方法的所有候选签名，便于 hook 失败时排查
    private void dumpMethodSignatures(ClassLoader classLoader, String className, String methodName) {
        Log.i("Dump " + className + "." + methodName + " candidates:");
        try {
            for (Method method : XposedHelpers.findClass(className, classLoader).getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    Log.i("  " + method.toString());
                }
            }
        } catch (Throwable e) {
            Log.e("Dump method signatures failed for " + className + ": " + e.getMessage());
        }
    }
}
