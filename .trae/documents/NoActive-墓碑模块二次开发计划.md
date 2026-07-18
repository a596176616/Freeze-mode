# NoActive 墓碑模块二次开发计划

## 状态：部分完成 / 待用户确认后执行

---

## 一、目标与非目标

### 目标
基于 NoActive Mod（Nep-Timeline/NoA-Mod）开源 LSPosed 模块进行二次开发，为小米/HyperOS 设备补充 PRO 版缺失的核心功能，实现：
1. 后台应用自动冻结，停止 CPU 活动，节省电量
2. 切回前台时快速解冻，恢复冻结前的界面状态
3. 智能状态识别，避免误冻结正在使用的应用

### 非目标
- 不实现 iOS 式「真墓碑」（保存状态到磁盘再杀进程）—— 技术复杂度极高，且 Android 冻结在 RAM 中的方案已足够满足省电需求
- 不实现内核模块（ReKernel）—— 需要内核开发经验，超出当前范围
- 不实现网络解冻（Binder 通知）—— 需要内核模块配合
- 不做 UI 应用（NoActive 开源版无 UI，后续可单独规划）

---

## 二、当前状态分析

### 2.1 NoActive 开源版架构（已克隆分析）

**项目结构：**
```
NoActive/
├── app/
│   ├── build.gradle          # AGP 7.1.3, Java 8, compileSdk 32, minSdk 23
│   └── src/main/
│       ├── AndroidManifest.xml  # Xposed 模块声明
│       ├── assets/xposed_init   # 入口: cn.myflv.android.noactive.Hook
│       └── java/cn/myflv/android/noactive/
│           ├── Hook.java                    # 入口，实现 IXposedHookLoadPackage
│           ├── app/
│           │   ├── Android.java             # 主 Hook 编排器（系统框架）
│           │   └── PowerKeeper.java         # MIUI 电量性能 Hook
│           ├── entity/
│           │   ├── ClassEnum.java           # 系统类名常量
│           │   ├── MethodEnum.java          # 方法名常量
│           │   ├── FieldEnum.java           # 字段名常量
│           │   └── MemData.java             # 内存配置数据
│           ├── hook/
│           │   ├── AppSwitchHook.java       # 核心：应用切换 → 冻结/解冻
│           │   ├── BroadcastDeliverHook.java # 阻止冻结应用接收广播
│           │   ├── ANRHook.java             # 防止 ANR 误杀冻结应用
│           │   ├── OomAdjHook.java          # 修改 oom_adj 防 LMK 杀
│           │   ├── CacheFreezerHook.java    # 禁用系统自带冻结器
│           │   └── MilletHook.java          # 禁用 MIUI Millet
│           ├── server/                      # Android 内部类反射封装
│           └── utils/
│               ├── FreezeUtils.java         # 4 种冻结方式实现
│               ├── FreezerConfig.java       # 配置文件管理
│               ├── ConfigFileObserver.java  # 配置文件热加载
│               └── Log.java / ThreadUtil.java
```

**核心数据流：**
```
AppSwitchHook (Hook updateActivityUsageStats)
  ├── ACTIVITY_PAUSED: 等 3 秒 → 确认仍在后台 → 冻结所有进程
  └── ACTIVITY_RESUMED: 解冻所有进程
       ↓
BroadcastDeliverHook: 拦截冻结应用的广播 → 防止 ANR
ANRHook: 吞掉冻结应用的 ANR → 防止系统杀进程
OomAdjHook: 修改 oom_adj → 防止 LMK 杀进程
```

**冻结方式：**
- Kill -19 (SIGSTOP): 通过 `Process.sendSignal(pid, 19)`
- Kill -20 (SIGTSTP): 通过 `Process.sendSignal(pid, 20)`
- Cgroup Freezer V1: 写 `/sys/fs/cgroup/freezer/perf/frozen/cgroup.procs`
- Cgroup Freezer V2: 写 `/sys/fs/cgroup/uid_{uid}/pid_{pid}/cgroup.freeze`
- API: 调用 `Process.setProcessFrozen(pid, uid, frozen)`

**配置系统：**
- 目录：`/data/system/NoActive/`
- 即时生效：`whiteApp.conf`, `blackSystemApp.conf`, `whiteProcess.conf`, `killProcess.conf`
- 重启生效：`debug`, `disable.oom`, `kill.19/20`, `freezer.v1/v2/api`, `color.os`

### 2.2 NoActive Mod 新增功能（基于文档分析）

相比开源版，NoActive Mod 增加了：
- MiPush 通知唤醒
- FCM 推送临时解冻
- 网络解冻 (MIUI)
- 音视频播放检测解冻
- Android 14 兼容
- 多开/工作资料支持
- 息屏 Doze 优化
- Binder 解冻开关
- 通知栏交互
- 兼容模式

### 2.3 关键缺口（PRO 版有，Mod 版仍缺）

| 功能 | 说明 | 优先级 |
|------|------|:---:|
| 智能状态识别 | 通话/录音/定位/相机/VPN 等场景免冻结 | P0 |
| 网速识别 | 上传/下载时免冻结 | P1 |
| 常驻通知保护 | 通知栏有常驻通知时不冻结 | P1 |
| 后台播放识别 | 播放音乐/视频时免冻结 | P1 |

---

## 三、提议修改

### 3.1 总体策略

1. 克隆 NoActive Mod 仓库到本地作为基础
2. 升级构建配置：compileSdk 34 → targetSdk 34，适配 Android 14+
3. 渐进式添加功能，每次一个功能点，独立可测试

### 3.2 第一阶段：升级构建环境 + 基础适配

**修改文件：** `app/build.gradle`

**What：** 升级 compileSdk 到 34、targetSdk 到 34，升级 AGP 和依赖

**Why：** 原项目 compileSdk 32，无法使用 Android 13+ 的 API（如 `TelephonyManager` 通话状态监听、`MediaProjection` 等）

**How：**
```groovy
compileSdk 34
targetSdk 34
// 升级 AGP 7.1.3 → 8.x
// 升级 Xposed API 82 → 100+
```

**修改文件：** `app/src/main/java/cn/myflv/android/noactive/entity/ClassEnum.java`

**What：** 补充 Android 13/14 新增或变更的系统类名和方法名

**Why：** 系统内部类在不同 Android 版本间可能变化，需要版本适配

---

### 3.3 第二阶段：智能状态识别（P0）

**目标：** 在以下场景自动跳过冻结，避免影响用户体验

#### 3.3.1 通话状态检测

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/TelephonyHook.java`

**What：** 监听 `TelephonyManager` 通话状态，在 `CALL_STATE_OFFHOOK`（通话中）和 `CALL_STATE_RINGING`（来电响铃）时将对应应用标记为「免冻结」

**Why：** 通话中冻结会导致通话中断或录音异常

**How：**
- 通过 `ITelephonyRegistry` 监听通话状态变化
- 在 `MemData` 中新增 `Set<String> activeCallApps` 集合
- 通话状态变为 IDLE 时清除标记

**修改文件：** `app/src/main/java/cn/myflv/android/noactive/entity/MemData.java`

**What：** 新增 `activeCallApps` 字段

**修改文件：** `app/src/main/java/cn/myflv/android/noactive/hook/AppSwitchHook.java`

**What：** 在 `onPause` 方法中增加检查：如果应用在 `activeCallApps` 中，跳过冻结

#### 3.3.2 录音状态检测

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/AudioRecordHook.java`

**What：** Hook `AudioRecord.startRecording()` 和 `stop()`，记录正在录音的应用

**Why：** 录音中冻结会导致录音中断

**How：**
- Hook `android.media.AudioRecord` 的 `startRecording()` 和 `stop()`
- 从 `ActivityManagerService` 获取当前前台/后台应用信息
- 在 `MemData` 中新增 `Set<String> activeRecordingApps` 集合

#### 3.3.3 定位状态检测

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/LocationHook.java`

**What：** Hook `LocationManagerService` 的定位请求注册/注销，记录正在使用定位的应用

**Why：** 导航/运动记录等应用冻结后 GPS 会中断

**How：**
- Hook `LocationManagerService.requestLocationUpdatesLocked()` 和 `removeUpdatesLocked()`
- 在 `MemData` 中新增 `Set<String> activeLocationApps` 集合

#### 3.3.4 相机状态检测

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/CameraHook.java`

**What：** Hook `CameraService` 的相机连接/断开，记录正在使用相机的应用

**Why：** 拍照/录像/视频通话中冻结会导致相机异常

**How：**
- Hook `CameraService.connectDevice()` 和 `disconnectDevice()`
- 在 `MemData` 中新增 `Set<String> activeCameraApps` 集合

#### 3.3.5 VPN 状态检测

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/VpnHook.java`

**What：** Hook `VpnManagerService` 的 VPN 连接/断开，记录使用 VPN 的应用

**Why：** VPN 应用冻结后网络连接会断开

**How：**
- Hook `VpnManagerService` 的 VPN 建立/断开方法
- 在 `MemData` 中新增 `Set<String> activeVpnApps` 集合

#### 3.3.6 统一免冻结检查

**修改文件：** `app/src/main/java/cn/myflv/android/noactive/hook/AppSwitchHook.java`

**What：** 在 `onPause` 冻结前统一检查所有状态

**How：**
```java
private boolean shouldSkipFreeze(String packageName) {
    return memData.getActiveCallApps().contains(packageName)
        || memData.getActiveRecordingApps().contains(packageName)
        || memData.getActiveLocationApps().contains(packageName)
        || memData.getActiveCameraApps().contains(packageName)
        || memData.getActiveVpnApps().contains(packageName);
}
```

---

### 3.4 第三阶段：网速识别（P1）

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/NetworkSpeedHook.java`

**What：** Hook `NetworkStatsService` 或通过 `/proc/net/xt_qtaguid/stats` 读取网络流量，当应用后台有显著网络活动（上传≥100KB/s 或下载≥300KB/s）时跳过冻结

**Why：** 下载文件、上传照片等场景冻结会中断传输

**How：**
- 定时采样（每 2 秒）应用网络流量
- 维护每个应用最近 10 秒内的流量窗口
- 当流量超过阈值时标记为 `activeNetworkApps`
- 在 `AppSwitchHook.onPause` 中检查

---

### 3.5 第四阶段：常驻通知保护（P1）

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/NotificationHook.java`

**What：** Hook `NotificationManagerService` 的通知发送/取消，检测 `FLAG_ONGOING_EVENT` 或 `FLAG_NO_CLEAR` 通知

**Why：** 有常驻通知的应用（如音乐播放器、VPN、下载管理器）冻结后通知消失，用户困惑

**How：**
- Hook `NotificationManagerService.enqueueNotificationInternal()`
- 检查通知 flags 是否包含 `FLAG_ONGOING_EVENT` 或 `FLAG_NO_CLEAR`
- 维护 `Set<String> activeNotificationApps` 集合
- 当通知被取消时从集合中移除

---

### 3.6 第五阶段：后台播放识别（P1）

**新增文件：** `app/src/main/java/cn/myflv/android/noactive/hook/AudioPlaybackHook.java`

**What：** Hook `AudioManager` 或 `AudioService` 的音频焦点变化，检测后台音乐/视频播放

**Why：** NoActive Mod 已有音视频播放解冻，但需要确认小米设备上是否正常工作，可能需要补充适配

**How：**
- Hook `AudioService` 的音频焦点请求/放弃
- 维护 `Set<String> activeAudioApps` 集合
- 在 `AppSwitchHook.onPause` 中检查

---

## 四、假设与决策

### 已确认决策
| # | 决策 | 理由 |
|---|------|------|
| 1 | 基于 NoActive Mod 二次开发 | 起点最高，已有推送/播放解冻等 Mod 增强 |
| 2 | 目标设备：小米/HyperOS | 用户使用小米设备 |
| 3 | 平台：KernelSU + LSPosed | 用户指定技术栈 |
| 4 | 全部功能渐进实现 | 用户要求先记录所有功能，再分多次实现 |
| 5 | 不做 UI 应用 | 开源版无 UI，后续单独规划 |

### Trae 自主采用的假设
| # | 假设 | 理由 |
|---|------|------|
| 1 | NoActive Mod 代码结构与原版一致 | Mod 是基于 v2 的 fork，架构不变 |
| 2 | 小米设备使用 cgroup freezer v2 | HyperOS 内核 5.10+，默认支持 v2 |
| 3 | 用户已安装 KSU + ZygiskNext + LSPosed | 这是使用模块的前提 |
| 4 | 开发语言保持 Java | 原项目为 Java，渐进修改不切换语言 |

### 待用户决策
| # | 问题 | 状态 |
|---|------|:---:|
| 1 | 需要安装 Android Studio 开发环境，是否已有？ | 待确认 |
| 2 | 需要一台已 ROOT 的小米手机进行真机测试，是否已有？ | 待确认 |
| 3 | 开发优先级是否需要调整？ | 待确认 |

---

## 五、验收标准

### 第一阶段：环境搭建
- [ ] 项目可在 Android Studio 中成功编译
- [ ] 生成的 APK 可在小米/HyperOS 设备上安装
- [ ] LSPosed 中可识别并启用模块

### 第二阶段：智能状态识别
- [ ] 通话中 → 应用不被冻结
- [ ] 通话结束 → 应用正常冻结
- [ ] 录音中 → 应用不被冻结
- [ ] 定位中 → 应用不被冻结
- [ ] 相机使用中 → 应用不被冻结
- [ ] VPN 连接中 → 应用不被冻结
- [ ] 正常后台应用 → 3 秒后冻结

### 第三阶段：网速识别
- [ ] 后台下载速度 ≥300KB/s → 不冻结
- [ ] 后台上传速度 ≥100KB/s → 不冻结
- [ ] 网络活动停止 → 3 秒后冻结

### 第四阶段：常驻通知保护
- [ ] 有常驻通知的应用 → 不冻结
- [ ] 通知移除 → 3 秒后冻结

### 第五阶段：后台播放识别
- [ ] 后台播放音乐 → 不冻结
- [ ] 播放停止 → 3 秒后冻结

---

## 六、风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| NoActive Mod 仓库无法访问（GitHub 认证） | 无法获取 Mod 代码 | 从原版 NoActive 开始，手动移植 Mod 功能 |
| 小米 HyperOS 系统类名变更 | Hook 失败 | 通过日志定位，版本适配 |
| 无 Android 开发经验 | 开发进度慢 | 分阶段渐进，每阶段产出可测试 APK |
| 真机测试环境缺失 | 无法验证功能 | 先完成编译，后续寻找测试设备 |
| 冻结后应用状态丢失 | 用户体验差 | 仅冻结（不杀进程），保持 RAM 中状态 |

---

## 七、下一技能建议

1. **功能实现**：开始第一阶段代码修改
2. **Android开发环境搭建**：如果用户需要安装 Android Studio
3. **领域建模**：如果后续需要维护 CONTEXT.md 和 ADR