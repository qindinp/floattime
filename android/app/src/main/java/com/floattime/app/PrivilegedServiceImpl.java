package com.floattime.app;

import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shizuku 特权服务实现
 *
 * 通过 Shizuku 特权通道，使用 Android 防火墙 API 断开指定 UID 的网络。
 * 用于在发送焦点通知时临时断开 xmsf (小米推送) 网络，
 * 阻止系统向小米服务器校验白名单，从而绕过白名单限制。
 *
 * 参考: Capsulyric PrivilegedServiceImpl
 */
public class PrivilegedServiceImpl extends IPrivilegedService.Stub {

    private static final String TAG = "PrivilegedServiceImpl";
    private static final long OP_TIMEOUT_MS = 3000L;

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
                    // 通过 ServiceManager 获取 ConnectivityManager AIDL 接口
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    IBinder binder = (IBinder) smClass.getMethod("getService", String.class)
                            .invoke(null, "connectivity");

                    Class<?> stubClass = Class.forName("android.net.IConnectivityManager\$Stub");
                    Object cm = stubClass.getMethod("asInterface", IBinder.class)
                            .invoke(null, binder);

                    // 启用防火墙链 (chain 9 = 最常用)
                    callMethod(cm, "setFirewallChainEnabled", 9, true);

                    // 设置 UID 规则: 0=允许, 2=拒绝
                    int rule = enabled ? 0 : 2;
                    callMethod(cm, "setUidFirewallRule", 9, uid, rule);

                    Log.d(TAG, "✅ Firewall rule set: uid=" + uid + ", rule=" + rule);
                    resultRef.set(true);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Firewall operation failed: " + e.getMessage());
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
            Log.e(TAG, "❌ Exception: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean ping() {
        return true;
    }

    private void callMethod(Object obj, String methodName, Object... args) throws Exception {
        Class<?> clazz = obj.getClass();
        for (var method : clazz.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                // 处理基本类型转换
                Object[] finalArgs = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    Class<?> paramType = method.getParameterTypes()[i];
                    if (paramType == int.class && args[i] instanceof Integer) {
                        finalArgs[i] = args[i];
                    } else if (paramType == boolean.class && args[i] instanceof Boolean) {
                        finalArgs[i] = args[i];
                    } else {
                        finalArgs[i] = args[i];
                    }
                }
                try {
                    method.invoke(obj, finalArgs);
                    return;
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getTargetException() != null ? e.getTargetException() : e.getCause();
                    if (cause != null) {
                        throw new Exception(cause);
                    }
                    throw e;
                }
            }
        }
        throw new NoSuchMethodException("No method " + methodName + " with " + args.length + " params");
    }
}
