package com.floattime.app;

import android.content.Context;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shizuku 特权服务实现 v3
 *
 * 通过 Shizuku 特权通道，使用 Android 防火墙 API 断开指定 UID 的网络。
 * 用于在发送焦点通知时临时断开 xmsf (小米推送) 网络，
 * 阻止系统向小米服务器校验白名单，从而绕过白名单限制。
 *
 * v3 修复：
 * - 防火墙链从 9(STANDBY) 改为 1(WIFI) + 2(MOBILE)，真正断开数据网络
 * - 增加方法签名精确匹配，避免调用错误的重载
 * - 优先尝试 NetworkPolicyManager.setUidPolicy 方案
 */
public class PrivilegedServiceImpl extends IPrivilegedService.Stub {

    private static final String TAG = "PrivilegedServiceImpl";
    private static final long OP_TIMEOUT_MS = 3000L;

    // 防火墙链 ID（与 Android 源码一致）
    private static final int CHAIN_WIFI = 1;
    private static final int CHAIN_MOBILE = 2;

    // UID 防火墙规则
    private static final int RULE_ALLOW = 0;  // FIREWALL_RULE_ALLOW
    private static final int RULE_DENY = 2;   // FIREWALL_RULE_DENY

    private static final HandlerThread sWorkerThread;
    private static final Handler sWorkerHandler;
    static {
        sWorkerThread = new HandlerThread("PrivilegedSvcWorker");
        sWorkerThread.start();
        sWorkerHandler = new Handler(sWorkerThread.getLooper());
    }

    @Override
    public boolean setPackageNetworkingEnabled(int uid, boolean enabled) {
        Log.d(TAG, "setPackageNetworkingEnabled(uid=" + uid + ", enabled=" + enabled + ")");

        try {
            AtomicReference<Boolean> resultRef = new AtomicReference<>(null);
            CountDownLatch latch = new CountDownLatch(1);

            sWorkerHandler.post(() -> {
                try {
                    // 方案 1（优先）：使用 NetworkPolicyManager.setUidPolicy
                    boolean ok = tryNetworkPolicyManager(uid, enabled);
                    if (ok) {
                        Log.d(TAG, "✅ NetworkPolicyManager succeeded: uid=" + uid + ", enabled=" + enabled);
                        resultRef.set(true);
                        return;
                    }

                    // 方案 2（回退）：使用防火墙链 WIFI(1) + MOBILE(2)
                    Log.d(TAG, "NetworkPolicyManager failed, falling back to firewall chains");
                    ok = tryFirewallChains(uid, enabled);
                    if (ok) {
                        Log.d(TAG, "✅ Firewall chains succeeded: uid=" + uid + ", enabled=" + enabled);
                        resultRef.set(true);
                        return;
                    }

                    Log.e(TAG, "❌ All methods failed for uid=" + uid);
                    resultRef.set(false);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Operation failed: " + e.getMessage(), e);
                    resultRef.set(false);
                } finally {
                    latch.countDown();
                }
            });

            if (latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return resultRef.get() != null && resultRef.get();
            } else {
                Log.e(TAG, "❌ Operation timed out after " + OP_TIMEOUT_MS + "ms");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean ping() {
        return true;
    }

    // ================================================================
    //  方案 1：NetworkPolicyManager（推荐，Capsulyric 参考实现）
    // ================================================================

    private static final int POLICY_NONE = 0;
    private static final int POLICY_REJECT_METERED_BACKGROUND = 1;
    private static final int POLICY_REJECT_ALL = 2;

    /**
     * 尝试通过 NetworkPolicyManager 设置 UID 网络策略
     *
     * setUidPolicy(int uid, int policy)
     * policy = 0: POLICY_NONE (允许)
     * policy = 2: POLICY_REJECT_ALL (拒绝所有)
     *
     * 这是更可靠的方案，直接作用于 UID 而非防火墙链。
     */
    private boolean tryNetworkPolicyManager(int uid, boolean enabled) {
        try {
            // 获取 NetworkPolicyManager 服务
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "netpolicy");
            if (binder == null) {
                Log.w(TAG, "NetworkPolicyManager binder is null");
                return false;
            }

            Class<?> stubClass = Class.forName("android.net.INetworkPolicyManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object npm = asInterface.invoke(null, binder);

            // 设置 UID 策略
            int policy = enabled ? POLICY_NONE : POLICY_REJECT_ALL;
            Method setUidPolicy = findMethodExact(npm, "setUidPolicy", int.class, int.class);
            if (setUidPolicy != null) {
                setUidPolicy.invoke(npm, uid, policy);
                Log.d(TAG, "setUidPolicy(uid=" + uid + ", policy=" + policy + ") OK");
                return true;
            }

            // 某些版本用 setUidPolicyForegroundRules / setUidPolicyBackgroundRules
            Method setFg = findMethodExact(npm, "setUidPolicyForegroundRules", int.class, int.class);
            if (setFg != null) {
                int fgPolicy = enabled ? POLICY_NONE : POLICY_REJECT_ALL;
                setFg.invoke(npm, uid, fgPolicy);
                Log.d(TAG, "setUidPolicyForegroundRules(uid=" + uid + ", policy=" + fgPolicy + ") OK");
                return true;
            }

            Log.w(TAG, "No suitable NetworkPolicyManager method found");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "tryNetworkPolicyManager failed: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  方案 2：防火墙链 WIFI(1) + MOBILE(2)
    // ================================================================

    /**
     * 通过 IConnectivityManager 防火墙 API 同时断开 WIFI 和 MOBILE
     *
     * setFirewallChainEnabled(int chain, boolean enable) — 启用链
     * setUidFirewallRule(int chain, int uid, int rule) — 设置 UID 规则
     *
     * 必须同时断两个链，否则只断一个另一个还能通。
     */
    private boolean tryFirewallChains(int uid, boolean enabled) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "connectivity");
            if (binder == null) {
                Log.w(TAG, "ConnectivityService binder is null");
                return false;
            }

            Class<?> stubClass = Class.forName("android.net.IConnectivityManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object cm = asInterface.invoke(null, binder);

            int rule = enabled ? RULE_ALLOW : RULE_DENY;
            boolean anySuccess = false;

            // 同时操作 WIFI 和 MOBILE 链
            for (int chain : new int[]{CHAIN_WIFI, CHAIN_MOBILE}) {
                try {
                    Method setEnabled = findMethodExact(cm, "setFirewallChainEnabled", int.class, boolean.class);
                    if (setEnabled != null) {
                        setEnabled.invoke(cm, chain, true);
                    }

                    Method setRule = findMethodExact(cm, "setUidFirewallRule", int.class, int.class, int.class);
                    if (setRule != null) {
                        setRule.invoke(cm, chain, uid, rule);
                        Log.d(TAG, "setUidFirewallRule(chain=" + chain + ", uid=" + uid + ", rule=" + rule + ") OK");
                        anySuccess = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Firewall chain " + chain + " failed: " + e.getMessage());
                }
            }

            return anySuccess;
        } catch (Exception e) {
            Log.w(TAG, "tryFirewallChains failed: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  精确方法查找（避免匹配到错误的重载）
    // ================================================================

    /**
     * 按方法名和参数类型精确查找（含父类继承搜索）
     */
    private Method findMethodExact(Object obj, String name, Class<?>... paramTypes) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Method m = clazz.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            }
        }

        // 回退：遍历所有方法做模糊匹配（处理 int→long 等隐式转换）
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                Class<?>[] actualParams = m.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!isCompatible(actualParams[i], paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 检查参数类型兼容性（处理 int↔long 等）
     */
    private boolean isCompatible(Class<?> actual, Class<?> expected) {
        if (actual.equals(expected)) return true;
        // int 可匹配 int, long, Integer
        if (expected == int.class) {
            return actual == int.class || actual == long.class || actual == Integer.class;
        }
        if (expected == boolean.class) {
            return actual == boolean.class || actual == Boolean.class;
        }
        return false;
    }
}
