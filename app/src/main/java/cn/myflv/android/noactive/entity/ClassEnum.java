package cn.myflv.android.noactive.entity;

public class ClassEnum {
    // v5: updateActivityUsageStats SDK 34+ 新增 ActivityId 参数
    public final static String ActivityId = "android.app.assist.ActivityId";
    public final static String ActivityManagerService = "com.android.server.am.ActivityManagerService";
    public final static String ComponentName = "android.content.ComponentName";
    // v5: ANR SDK 36 新增 ExecutorService 参数
    public final static String ExecutorService = "java.util.concurrent.ExecutorService";
    public final static String IBinder = "android.os.IBinder";
    // v5: dispatchReceivers SDK 34+ 新增 BroadcastProcessQueue 参数
    public final static String BroadcastProcessQueue = "com.android.server.am.BroadcastProcessQueue";
    public final static String BroadcastQueue = "com.android.server.am.BroadcastQueue";
    // v5: SDK 34+ 使用 BroadcastQueueImpl 替代 BroadcastQueue
    public final static String BroadcastQueueImpl = "com.android.server.am.BroadcastQueueImpl";
    public final static String BroadcastQueueModernImpl = "com.android.server.am.BroadcastQueueModernImpl";
    public final static String BroadcastRecord = "com.android.server.am.BroadcastRecord";
    public final static String BroadcastFilter = "com.android.server.am.BroadcastFilter";
    public final static String AnrHelper = "com.android.server.am.AnrHelper";
    public final static String ProcessRecord = "com.android.server.am.ProcessRecord";
    public final static String ApplicationInfo = "android.content.pm.ApplicationInfo";
    public final static String WindowProcessController = "com.android.server.wm.WindowProcessController";
    public final static String AnrRecord = AnrHelper + "$AnrRecord";
    public final static String AppErrors = "com.android.server.am.AppErrors";
    public final static String ActivityRecord = "com.android.server.am.ActivityRecord";
    public final static String ProcessStateRecord = "com.android.server.am.ProcessStateRecord";
    // v5: ANR SDK 36 新增 TimeoutRecord 参数
    public final static String TimeoutRecord = "com.android.internal.os.TimeoutRecord";
    public final static String OomAdjuster = "com.android.server.am.OomAdjuster";

    public final static String MilletConfig = "com.miui.powerkeeper.millet.MilletConfig";
    public final static String CachedAppOptimizer = "com.android.server.am.CachedAppOptimizer";
    public final static String ProcessList = "com.android.server.am.ProcessList";
    public final static String PowerStateMachine = "com.miui.powerkeeper.statemachine.PowerStateMachine";
    public final static String Process = "android.os.Process";
}
