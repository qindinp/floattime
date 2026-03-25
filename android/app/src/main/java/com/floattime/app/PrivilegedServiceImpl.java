package com.floattime.app;

import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
 * - ServiceManager 改回反射方式（隐藏 API 不能直接 import）
 */
public class PrivilegedServiceImpl extends IPrivilegedService.Stub {

    private static final String TAG = "PrivilegedServiceImpl";
    private static final long OP_TIMEOUT_MS = 3000L;

    // 防火墙链 ID
    private static final int CHAIN_WIFI = 1;
    private static final int CHAIN_MOBILE = 2;

    // UID 防火墙规则
    private static final int RULE_ALLOW = 0;  // FIREWALL_RULE_ALLOW
    private static final int RULE_DENY = 2;   // FIREWALL_RULE_DENY

    // NetworkPolicyManager UID 策略
    private static final int POLICY_NONE = 0;
    private static final int POLICY_REJECT_ALL = 2;

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
                    // 方案 1（优先）：NetworkPolicyManager.setUidPolicy
                    Log.d(TAG, "[DEBUG] Trying NPM method for uid=" + uid + ", enabled=" + enabled);
                    boolean ok = tryNetworkPolicyManager(uid, enabled);
                    if (ok) {
                        Log.d(TAG, "[DEBUG] ✅ NPM succeeded: uid=" + uid + ", enabled=" + enabled);
                        resultRef.set(true);
                        return;
                    }
                    Log.w(TAG, "[DEBUG] ❌ NPM failed, falling back to firewall chains");

                    // 方案 2（回退）：防火墙链 WIFI(1) + MOBILE(2)
                    ok = tryFirewallChains(uid, enabled);
                    if (ok) {
                        Log.d(TAG, "[DEBUG] ✅ Firewall chains succeeded: uid=" + uid + ", enabled=" + enabled);
                        resultRef.set(true);
                        return;
                    }
                    Log.e(TAG, "[DEBUG] ❌ ALL methods failed for uid=" + uid
                            + ". Check if setUidPolicy or setUidFirewallRule exists on this device.");
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
    //  通过反射获取系统 ServiceBinder
    // ================================================================

    private static IBinder getServiceBinder(String serviceName) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, serviceName);
        } catch (Exception e) {
            Log.w(TAG, "ServiceManager.getService(\"" + serviceName + "\") failed: " + e.getMessage());
            return null;
        }
    }

    // ================================================================
    //  方案 1：NetworkPolicyManager（推荐）
    // ================================================================

    private boolean tryNetworkPolicyManager(int uid, boolean enabled) {
        try {
            IBinder binder = getServiceBinder("netpolicy");
            if (binder == null) {
                Log.w(TAG, "[DEBUG] NPM: NetworkPolicyManager binder is null — system service unavailable");
                return false;
            }
            Log.d(TAG, "[DEBUG] NPM: got netpolicy binder");

            Class<?> stubClass = Class.forName("android.net.INetworkPolicyManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object npm = asInterface.invoke(null, binder);

            int policy = enabled ? POLICY_NONE : POLICY_REJECT_ALL;

            // 尝试 setUidPolicy(int uid, int policy)
            Method setUidPolicy = findMethodExact(npm, "setUidPolicy", int.class, int.class);
            if (setUidPolicy != null) {
                setUidPolicy.invoke(npm, uid, policy);
                Log.d(TAG, "setUidPolicy(uid=" + uid + ", policy=" + policy + ") OK");
                return true;
            }

            // 回退：某些版本用 setUidPolicyForegroundRules
            Method setFg = findMethodExact(npm, "setUidPolicyForegroundRules", int.class, int.class);
            if (setFg != null) {
                setFg.invoke(npm, uid, policy);
                Log.d(TAG, "setUidPolicyForegroundRules(uid=" + uid + ", policy=" + policy + ") OK");
                return true;
            }

            Log.w(TAG, "No suitable NPM method found");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "tryNetworkPolicyManager failed: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  方案 2：防火墙链 WIFI(1) + MOBILE(2)
    // ================================================================

    private boolean tryFirewallChains(int uid, boolean enabled) {
        try {
            IBinder binder = getServiceBinder("connectivity");
            if (binder == null) {
                Log.w(TAG, "[DEBUG] FW: ConnectivityService binder is null");
                return false;
            }
            Log.d(TAG, "[DEBUG] FW: got connectivity binder");

            Class<?> stubClass = Class.forName("android.net.IConnectivityManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object cm = asInterface.invoke(null, binder);

            int rule = enabled ? RULE_ALLOW : RULE_DENY;
            boolean anySuccess = false;

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
    //  精确方法查找
    // ================================================================

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

        // 回退：模糊匹配
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

    private boolean isCompatible(Class<?> actual, Class<?> expected) {
        if (actual.equals(expected)) return true;
        if (expected == int.class) return actual == int.class || actual == long.class || actual == Integer.class;
        if (expected == boolean.class) return actual == boolean.class || actual == Boolean.class;
        return false;
    }
}
