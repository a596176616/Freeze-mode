package cn.myflv.android.noactive.hook;

import cn.myflv.android.noactive.entity.FieldEnum;
import cn.myflv.android.noactive.entity.MemData;
import cn.myflv.android.noactive.server.ActivityManagerService;
import cn.myflv.android.noactive.server.ApplicationInfo;
import cn.myflv.android.noactive.server.BroadcastFilter;
import cn.myflv.android.noactive.server.BroadcastRecord;
import cn.myflv.android.noactive.server.ProcessRecord;
import cn.myflv.android.noactive.server.ReceiverList;
import cn.myflv.android.noactive.utils.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class BroadcastDeliverHook extends XC_MethodHook {

    // v5: SDK 34+ BroadcastQueueImpl.dispatchReceivers 使用新模式 (从 BroadcastRecord 取第 N 个 receiver)
    public static final int MODE_DISPATCH = 1;
    public static final int MODE_LEGACY = 0;

    private final MemData memData;
    private final int mode;

    public BroadcastDeliverHook(MemData memData) {
        this(memData, MODE_LEGACY);
    }

    // v5: 新增带 mode 参数的构造器，SDK 34+ 用 MODE_DISPATCH
    public BroadcastDeliverHook(MemData memData, int mode) {
        this.memData = memData;
        this.mode = mode;
    }

    // v5: 根据 mode 从 args 中提取 BroadcastFilter
    //   MODE_LEGACY  → args[1] 直接是 BroadcastFilter
    //   MODE_DISPATCH → args[1] 是 BroadcastRecord, args[2] 是 int receiverIndex,
    //                   需要从 BroadcastRecord.getReceiver(index) 取出 BroadcastFilter
    private Object extractBroadcastFilter(Object[] args) {
        if (args == null) return null;
        if (mode != MODE_DISPATCH) {
            if (args.length >= 2) {
                return args[1];
            }
            return null;
        }
        // MODE_DISPATCH
        if (args.length < 3 || args[1] == null) return null;
        Object receiver = new BroadcastRecord(args[1]).getReceiver(((Integer) args[2]).intValue());
        if (receiver == null) return null;
        if (!"com.android.server.am.BroadcastFilter".equals(receiver.getClass().getName())) {
            return null;
        }
        return receiver;
    }

    @Override
    public void beforeHookedMethod(MethodHookParam param) throws Throwable {
        Object filter = extractBroadcastFilter(param.args);
        if (filter == null) {
            return;
        }
        BroadcastFilter broadcastFilter = new BroadcastFilter(filter);
        ReceiverList receiverList = broadcastFilter.getReceiverList();
        // 如果广播为空就不处理
        if (receiverList == null) {
            return;
        }
        ProcessRecord processRecord = receiverList.getProcessRecord();
        // 如果进程或者应用信息为空就不处理
        if (processRecord == null || processRecord.getApplicationInfo() == null) {
            return;
        }
        if (processRecord.getUserId() != ActivityManagerService.MAIN_USER) {
            return;
        }
        ApplicationInfo applicationInfo = processRecord.getApplicationInfo();
        String packageName = processRecord.getApplicationInfo().getPackageName();
        // 如果包名为空就不处理(猜测系统进程可能为空)
        if (packageName == null) {
            return;
        }
        String processName = processRecord.getProcessName();
        // 如果进程名称不是包名开头就跳过
        if (!processName.startsWith(packageName)) {
            return;
        }
        // 如果是系统应用并且不是系统黑名单就不处理
        if (applicationInfo.getUid() < 10000 || (applicationInfo.isSystem() && !memData.getBlackSystemApps().contains(packageName))) {
            return;
        }
        // 如果是前台应用就不处理
        if (!memData.getAppBackgroundSet().contains(packageName)) {
            return;
        }
        // 如果白名单应用或者进程就不处理
        if (memData.getWhiteApps().contains(packageName) || memData.getWhiteProcessList().contains(processName)) {
            return;
        }
        // 暂存
        Object app = processRecord.getProcessRecord();
        param.setObjectExtra(FieldEnum.app, app);
        Log.d(processRecord.getProcessName() + " clear broadcast");
        // 清除广播
        receiverList.clear();
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);

        // 获取进程
        Object app = param.getObjectExtra(FieldEnum.app);
        if (app == null) {
            return;
        }

        Object filter = extractBroadcastFilter(param.args);
        if (filter == null) {
            return;
        }
        Object receiverList = XposedHelpers.getObjectField(filter, FieldEnum.receiverList);
        if (receiverList == null) {
            return;
        }
        // 还原修改
        XposedHelpers.setObjectField(receiverList, FieldEnum.app, app);
    }
}
