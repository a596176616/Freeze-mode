package cn.myflv.android.noactive.utils;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.myflv.android.noactive.entity.ClassEnum;
import cn.myflv.android.noactive.entity.MethodEnum;
import de.robv.android.xposed.XposedHelpers;

public class FreezerConfig {


    public final static String ConfigDir = "/data/system/NoActive";
    public final static String whiteAppConfig = "whiteApp.conf";
    public final static String blackSystemAppConfig = "blackSystemApp.conf";
    public final static String whiteProcessConfig = "whiteProcess.conf";
    public final static String killProcessConfig = "killProcess.conf";
    public final static String disableOOM = "disable.oom";
    public final static String kill19 = "kill.19";
    public final static String kill20 = "kill.20";
    public final static String freezerV1 = "freezer.v1";
    public final static String freezerV2 = "freezer.v2";
    public final static String freezerApi = "freezer.api";
    public final static String colorOs = "color.os";
    // v6: 后台应用持久化文件，记录已冻结的后台应用包名
    public final static String backgroundConf = "background.conf";
    public final static String API = "Api";
    public final static String V2 = "V2";
    public final static String V1 = "V1";


    public final static String[] listenConfig = {whiteAppConfig, whiteProcessConfig,
            killProcessConfig, blackSystemAppConfig};


    public static boolean isConfigOn(String configName) {
        File config = new File(ConfigDir, configName);
        return config.exists();
    }

    public static int getKillSignal() {
        if (isConfigOn(kill19)) {
            return 19;
        }
        if (isConfigOn(kill20)) {
            return 20;
        }
        return 19;
    }


    public static String getFreezerVersion(ClassLoader classLoader) {
        if (isConfigOn(freezerV2)) {
            return V2;
        }
        if (isConfigOn(freezerV1)) {
            return V1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isConfigOn(freezerApi)) {
                return API;
            }
            // v5: SDK 34+ 上 isFreezerSupported 可能不存在或抛异常，失败时默认 V2
            try {
                Class<?> CachedAppOptimizer = XposedHelpers.findClass(ClassEnum.CachedAppOptimizer, classLoader);
                boolean isSupportV2 = (boolean) XposedHelpers.callStaticMethod(CachedAppOptimizer, MethodEnum.isFreezerSupported);
                if (isSupportV2) {
                    return V2;
                }
            } catch (Throwable e) {
                Log.i("isFreezerSupported not available on SDK=" + Build.VERSION.SDK_INT + ", default to V2 (cgroup v2 freezer)");
                return V2;
            }
        }
        return V1;
    }


    public static boolean isUseKill() {
        return isConfigOn(kill19) || isConfigOn(kill20);
    }

    // v6: 追加后台应用包名到 background.conf（持久化后台列表）
    public static synchronized void appendBackground(String packageName) {
        appendBackground(ConfigDir, packageName);
    }

    // v6: 追加后台应用包名到指定目录的 background.conf
    public static synchronized void appendBackground(String dir, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        File configDir = new File(dir);
        File backgroundFile = new File(dir, backgroundConf);
        PrintWriter writer = null;
        try {
            if (!configDir.exists()) {
                configDir.mkdir();
            }
            if (!backgroundFile.exists()) {
                backgroundFile.createNewFile();
            }
            writer = new PrintWriter(new FileWriter(backgroundFile, true));
            writer.println(packageName);
        } catch (IOException e) {
            Log.e("background.conf append failed: " + e.getMessage());
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    // v6: 从 background.conf 移除指定后台应用包名（应用切回前台时调用）
    public static synchronized void removeBackground(String packageName) {
        removeBackground(ConfigDir, packageName);
    }

    // v6: 从指定目录的 background.conf 移除指定后台应用包名
    public static synchronized void removeBackground(String dir, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        File backgroundFile = new File(dir, backgroundConf);
        if (!backgroundFile.exists()) {
            return;
        }
        List<String> remaining = new ArrayList<>();
        BufferedReader reader = null;
        PrintWriter writer = null;
        try {
            reader = new BufferedReader(new FileReader(backgroundFile));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().equals(packageName)) {
                    remaining.add(line);
                }
            }
            reader.close();
            reader = null;
            writer = new PrintWriter(new FileWriter(backgroundFile, false));
            for (String entry : remaining) {
                writer.println(entry);
            }
        } catch (IOException e) {
            Log.e("background.conf remove failed: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (writer != null) {
                writer.close();
            }
        }
    }

    // v6: 加载后台应用包名集合（启动时恢复持久化后台列表）
    public static Set<String> loadBackground() {
        return loadBackground(ConfigDir);
    }

    // v6: 从指定目录的 background.conf 加载后台应用包名集合（保留插入顺序）
    public static Set<String> loadBackground(String dir) {
        Set<String> backgroundSet = new LinkedHashSet<>();
        File backgroundFile = new File(dir, backgroundConf);
        if (!backgroundFile.exists()) {
            return backgroundSet;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(backgroundFile));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                backgroundSet.add(trimmed);
            }
        } catch (IOException e) {
            Log.e("background.conf read failed: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return backgroundSet;
    }

    public static boolean isColorOs() {
        return isConfigOn(colorOs);
    }


    public static void checkAndInit() {
        File configDir = new File(ConfigDir);
        if (!configDir.exists()) {
            boolean mkdir = configDir.mkdir();
            if (!mkdir) return;
            Log.i("Init config dir");
        }
        for (String configName : listenConfig) {
            File config = new File(configDir, configName);
            if (!config.exists()) {
                createFile(config);
                Log.i("Init " + configName);
            }
        }
    }


    public static Set<String> get(String name) {
        Set<String> set = new HashSet<>();
        try {
            File file = new File(ConfigDir, name);
            BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if ("".equals(line.trim())) continue;
                if (line.startsWith("#")) continue;
                set.add(line.trim());
                Log.i(name.replace(".conf", "") + " add " + line);
            }
            bufferedReader.close();
        } catch (FileNotFoundException fileNotFoundException) {
            Log.e(name + " file not found");
        } catch (IOException ioException) {
            Log.e(name + " file read filed");
        }
        return set;
    }

    public static void createFile(File file) {
        try {
            boolean newFile = file.createNewFile();
            if (!newFile) {
                throw new IOException();
            }
        } catch (IOException e) {
            Log.e(file.getName() + " file create filed");
        }
    }
}
