package com.floattime.app

import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    companion object {
        private const val TAG = "PrivilegedServiceImpl"
        private const val OP_TIMEOUT_MS = 3000L

        // 防火墙链 ID
        private const val CHAIN_WIFI = 1
        private const val CHAIN_MOBILE = 2

        // UID 防火墙规则
        private const val RULE_ALLOW = 0   // FIREWALL_RULE_ALLOW
        private const val RULE_DENY = 2    // FIREWALL_RULE_DENY

        // NetworkPolicyManager UID 策略
        private const val POLICY_NONE = 0
        private const val POLICY_REJECT_ALL = 2

        @JvmStatic
        protected val sWorkerThread: HandlerThread = HandlerThread("PrivilegedSvcWorker").apply { start() }
        @JvmStatic
        protected val sWorkerHandler: Handler = Handler(sWorkerThread.looper)
    }

    override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
        Log.d(TAG, "setPackageNetworkingEnabled(uid=$uid, enabled=$enabled)")

        val resultRef = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)

        sWorkerHandler.post {
            try {
                // 方案 1（优先）：NetworkPolicyManager.setUidPolicy
                var ok = tryNetworkPolicyManager(uid, enabled)
                if (ok) {
                    Log.d(TAG, "✅ NPM succeeded: uid=$uid, enabled=$enabled")
                    resultRef.set(true)
                    latch.countDown()
                    return@post
                }

                // 方案 2（回退）：防火墙链 WIFI(1) + MOBILE(2)
                Log.d(TAG, "NPM failed, falling back to firewall chains")
                ok = tryFirewallChains(uid, enabled)
                if (ok) {
                    Log.d(TAG, "✅ Firewall chains succeeded: uid=$uid, enabled=$enabled")
                    resultRef.set(true)
                    latch.countDown()
                    return@post
                }

                Log.e(TAG, "❌ All methods failed for uid=$uid")
                resultRef.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Operation failed: ${e.message}", e)
                resultRef.set(false)
            } finally {
                latch.countDown()
            }
        }

        return try {
            if (latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                resultRef.get() == true
            } else {
                Log.e(TAG, "❌ Operation timed out after ${OP_TIMEOUT_MS}ms")
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception: ${e.message}", e)
            false
        }
    }

    override fun ping(): Boolean = true

    // ================================================================
    //  通过反射获取系统 ServiceBinder
    // ================================================================

    private fun getServiceBinder(serviceName: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            getService.invoke(null, serviceName) as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "ServiceManager.getService(\"$serviceName\") failed: ${e.message}")
            null
        }
    }

    // ================================================================
    //  方案 1：NetworkPolicyManager（推荐）
    // ================================================================

    private fun tryNetworkPolicyManager(uid: Int, enabled: Boolean): Boolean {
        return try {
            val binder = getServiceBinder("netpolicy") ?: run {
                Log.w(TAG, "NetworkPolicyManager binder is null")
                return false
            }

            val stubClass = Class.forName("android.net.INetworkPolicyManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val npm = asInterface.invoke(null, binder)

            val policy = if (enabled) POLICY_NONE else POLICY_REJECT_ALL

            // 尝试 setUidPolicy(int uid, int policy)
            @Suppress("UNCHECKED_CAST")
            val setUidPolicy = findMethodExact(npm, "setUidPolicy", Int::class.javaPrimitiveType as Class<Any>, Int::class.javaPrimitiveType as Class<Any>)
            if (setUidPolicy != null) {
                setUidPolicy.invoke(npm, uid, policy)
                Log.d(TAG, "setUidPolicy(uid=$uid, policy=$policy) OK")
                return true
            }

            // 回退：某些版本用 setUidPolicyForegroundRules
            @Suppress("UNCHECKED_CAST")
            val setFg = findMethodExact(npm, "setUidPolicyForegroundRules", Int::class.javaPrimitiveType as Class<Any>, Int::class.javaPrimitiveType as Class<Any>)
            if (setFg != null) {
                setFg.invoke(npm, uid, policy)
                Log.d(TAG, "setUidPolicyForegroundRules(uid=$uid, policy=$policy) OK")
                return true
            }

            Log.w(TAG, "No suitable NPM method found")
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryNetworkPolicyManager failed: ${e.message}")
            false
        }
    }

    // ================================================================
    //  方案 2：防火墙链 WIFI(1) + MOBILE(2)
    // ================================================================

    private fun tryFirewallChains(uid: Int, enabled: Boolean): Boolean {
        return try {
            val binder = getServiceBinder("connectivity") ?: run {
                Log.w(TAG, "ConnectivityService binder is null")
                return false
            }

            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val cm = asInterface.invoke(null, binder)

            val rule = if (enabled) RULE_ALLOW else RULE_DENY
            var anySuccess = false

            for (chain in intArrayOf(CHAIN_WIFI, CHAIN_MOBILE)) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val setEnabled = findMethodExact(cm, "setFirewallChainEnabled", Int::class.javaPrimitiveType as Class<Any>, Boolean::class.javaPrimitiveType as Class<Any>)
                    if (setEnabled != null) {
                        setEnabled.invoke(cm, chain, true)
                    }

                    @Suppress("UNCHECKED_CAST")
                    val setRule = findMethodExact(cm, "setUidFirewallRule", Int::class.javaPrimitiveType as Class<Any>, Int::class.javaPrimitiveType as Class<Any>, Int::class.javaPrimitiveType as Class<Any>)
                    if (setRule != null) {
                        setRule.invoke(cm, chain, uid, rule)
                        Log.d(TAG, "setUidFirewallRule(chain=$chain, uid=$uid, rule=$rule) OK")
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Firewall chain $chain failed: ${e.message}")
                }
            }

            anySuccess
        } catch (e: Exception) {
            Log.w(TAG, "tryFirewallChains failed: ${e.message}")
            false
        }
    }

    // ================================================================
    //  精确方法查找
    // ================================================================

    private fun findMethodExact(obj: Any, name: String, vararg paramTypes: Class<*>): Method? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            try {
                val m = clazz.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }

        // 回退：模糊匹配
        for (m in obj.javaClass.methods) {
            if (m.name == name && m.parameterCount == paramTypes.size) {
                val actualParams = m.parameterTypes
                var match = true
                for (i in paramTypes.indices) {
                    if (!isCompatible(actualParams[i], paramTypes[i])) {
                        match = false
                        break
                    }
                }
                if (match) {
                    m.isAccessible = true
                    return m
                }
            }
        }
        return null
    }

    private fun isCompatible(actual: Class<*>, expected: Class<*>): Boolean {
        if (actual == expected) return true
        if (expected == Int::class.javaPrimitiveType) return actual == Int::class.javaPrimitiveType || actual == Long::class.javaPrimitiveType || actual == Int::class.javaObjectType
        if (expected == Boolean::class.javaPrimitiveType) return actual == Boolean::class.javaPrimitiveType || actual == Boolean::class.javaObjectType
        return false
    }
}
