package com.floattime.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

/**
 * xmsf (小米推送) 网络控制助手 v3
 *
 * 通过 Shizuku 特权通道临时断开 xmsf 网络，
 * 阻止系统向小米服务器校验焦点通知白名单。
 *
 * 参考: Capsulyric XmsfNetworkHelper
 *
 * v3 改进：
 * - UserServiceArgs: daemon(true) + version(2)，与 Capsulyric 一致
 * - 重试机制: MAX_RETRIES=2, RETRY_DELAY=500ms
 * - getOrBindService 超时从 2s 降到 500ms，避免阻塞通知线程
 * - 更完善的错误处理
 */
object XmsfNetworkHelper {

    private const val TAG = "XmsfNetworkHelper"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 500L

    // getOrBindService 最大等待时间（从 2000ms 降到 500ms）
    private const val BIND_WAIT_TOTAL_MS = 500L
    private const val BIND_WAIT_INTERVAL_MS = 50L

    private var sPrivilegedService: IPrivilegedService? = null
    private var sUserServiceArgs: UserServiceArgs? = null

    private val sConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sPrivilegedService = IPrivilegedService.Stub.asInterface(service)
            Log.d(TAG, "Shizuku UserService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sPrivilegedService = null
            Log.d(TAG, "Shizuku UserService disconnected")
        }
    }

    /**
     * 绑定 Shizuku UserService
     */
    @JvmStatic
    fun bindService(context: Context) {
        try {
            sUserServiceArgs = UserServiceArgs(
                ComponentName(context.packageName, PrivilegedServiceImpl::class.java.name)
            ).daemon(true)
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(2)
            Shizuku.bindUserService(sUserServiceArgs!!, sConnection)
            Log.d(TAG, "Binding Shizuku UserService (daemon=true, version=2)...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UserService: ${e.message}")
        }
    }

    /**
     * 解绑 Shizuku UserService
     */
    @JvmStatic
    fun unbindService() {
        try {
            sUserServiceArgs?.let { args ->
                Shizuku.unbindUserService(args, sConnection, true)
            }
            sPrivilegedService = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind UserService: ${e.message}")
        }
    }

    /**
     * 设置 xmsf 网络开关
     */
    @JvmStatic
    fun setXmsfNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        try {
            val uid = try {
                context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "xmsf package not found (UID lookup failed)")
                return false
            }

            Log.d(TAG, "setXmsfNetworkingEnabled: enabled=$enabled, uid=$uid")

            var lastError: Exception? = null
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val service = getOrBindService(context)
                    if (service == null) {
                        Log.w(TAG, "Attempt ${attempt + 1}: PrivilegedService not connected")
                        if (attempt + 1 < MAX_RETRIES) {
                            Thread.sleep(RETRY_DELAY_MS)
                            continue
                        }
                        return false
                    }

                    val result = service.setPackageNetworkingEnabled(uid, enabled)
                    if (result) {
                        Log.d(TAG, "OK: xmsf networking set to $enabled")
                        return true
                    } else {
                        Log.e(TAG, "Privileged service returned failure for uid=$uid")
                        return false
                    }
                } catch (e: android.os.DeadObjectException) {
                    lastError = e
                    Log.w(TAG, "DeadObjectException on attempt ${attempt + 1}")
                    sPrivilegedService = null
                    if (attempt + 1 < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "Error on attempt ${attempt + 1}: ${e.message}")
                    return false
                }
            }

            Log.e(TAG, "All $MAX_RETRIES attempts failed. Last error: ${lastError?.message ?: "unknown"}")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "setXmsfNetworkingEnabled failed: ${e.message}")
            return false
        }
    }

    /**
     * 获取已绑定的 service，如果没有则尝试绑定并等待（最多 500ms）
     *
     * v3 改进：从 2000ms 降到 500ms，避免阻塞通知发送线程。
     * 如果 500ms 内没连上就快速返回 null，让调用方走降级路径。
     */
    private fun getOrBindService(context: Context): IPrivilegedService? {
        sPrivilegedService?.let { service ->
            try {
                if (service.asBinder().pingBinder()) {
                    return service
                }
            } catch (_: Exception) {}
            sPrivilegedService = null
        }

        // 尝试绑定
        Log.d(TAG, "PrivilegedService not connected, attempting bind...")
        bindService(context)

        // 等待连接（最多 500ms）
        var waited = 0L
        while (waited < BIND_WAIT_TOTAL_MS) {
            try {
                Thread.sleep(BIND_WAIT_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            waited += BIND_WAIT_INTERVAL_MS

            sPrivilegedService?.let { service ->
                try {
                    if (service.asBinder().pingBinder()) {
                        Log.d(TAG, "Service connected after ${waited}ms")
                        return service
                    }
                } catch (_: Exception) {}
            }
        }

        Log.w(TAG, "Still not connected after ${BIND_WAIT_TOTAL_MS}ms, giving up")
        return null
    }
}
