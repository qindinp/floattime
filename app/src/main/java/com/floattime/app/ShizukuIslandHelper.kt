package com.floattime.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 超级岛白名单绕过助手 v3
 */
@Keep
class ShizukuIslandHelper(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuIslandHelper"
        private const val FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val IPC_TIMEOUT_MS = 2000L

        @JvmStatic
        protected val sWorkerThread: HandlerThread = HandlerThread("ShizukuIPCWorker").apply { start() }
        @JvmStatic
        protected val sWorkerHandler: Handler = Handler(sWorkerThread.looper)
    }

    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var mShizukuAvailable = false

    @Volatile
    private var mShizukuPermissionGranted = false

    @Volatile
    private var mWhitelistBypassed = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        mShizukuAvailable = true
        XmsfNetworkHelper.bindService(appContext)
        checkPermissionAsync()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        mShizukuAvailable = false
        mShizukuPermissionGranted = false
        mWhitelistBypassed = false
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            mShizukuPermissionGranted = granted
            Log.d(TAG, "Permission result: $granted")
            if (granted) {
                sWorkerHandler.post {
                    try {
                        val result = XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, false)
                        mWhitelistBypassed = result
                        Log.d(TAG, "Post-permission bypass: ${if (result) "OK" else "FAILED"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Post-permission bypass failed: ${e.message}")
                        mWhitelistBypassed = false
                    }
                }
            }
        }
    }

    fun init() {
        try {
            Sui.init(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Sui.init failed: ${e.message}")
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version too old (< v11)")
            return
        }

        if (Shizuku.getBinder() != null) {
            XmsfNetworkHelper.bindService(appContext)
            checkPermissionAsync()
        }
    }

    fun destroy() {
        if (isReady) {
            try {
                XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore xmsf network on destroy: ${e.message}")
            }
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        XmsfNetworkHelper.unbindService()
    }

    private fun checkPermissionAsync() {
        sWorkerHandler.post {
            try {
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "Shizuku binder not alive")
                    return@post
                }

                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                mShizukuPermissionGranted = granted
                Log.d(TAG, "Permission: $granted")

                if (granted) {
                    XmsfNetworkHelper.bindService(appContext)
                    sWorkerHandler.post {
                        try {
                            val result = XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, false)
                            mWhitelistBypassed = result
                            Log.d(TAG, "Init-time bypass: ${if (result) "OK" else "FAILED"}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Init-time bypass failed: ${e.message}")
                        }
                    }
                } else {
                    mainHandler.post {
                        try {
                            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                        } catch (e: Exception) {
                            Log.e(TAG, "requestPermission failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkPermissionAsync failed: ${e.message}")
            }
        }
    }

    fun tryBypassWhitelist() {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) {
            Log.w(TAG, "Shizuku not ready, skip bypass (available=$mShizukuAvailable, permission=$mShizukuPermissionGranted)")
            return
        }

        sWorkerHandler.post {
            try {
                val result = XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, false)
                mWhitelistBypassed = result
                Log.d(TAG, "Bypass attempt: ${if (result) "OK" else "FAILED"}")
            } catch (e: Exception) {
                Log.e(TAG, "Bypass failed: ${e.message}")
                mWhitelistBypassed = false
            }
        }
    }

    fun setXmsfNetworkingSync(enabled: Boolean): Boolean {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) {
            Log.w(TAG, "Shizuku not ready, cannot set xmsf networking (available=$mShizukuAvailable, permission=$mShizukuPermissionGranted)")
            return false
        }

        val resultRef = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)

        sWorkerHandler.post {
            try {
                val result = XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, enabled)
                resultRef.set(result)
            } catch (e: Exception) {
                Log.e(TAG, "setXmsfNetworkingSync failed: ${e.message}")
                resultRef.set(false)
            } finally {
                latch.countDown()
            }
        }

        return try {
            if (latch.await(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                val result = resultRef.get()
                val ok = result == true
                Log.d(TAG, "setXmsfNetworkingSync($enabled) = $ok")
                ok
            } else {
                Log.e(TAG, "setXmsfNetworkingSync timed out after ${IPC_TIMEOUT_MS}ms")
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun preNotificationHook() {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) return

        sWorkerHandler.post {
            try {
                XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, false)
                sWorkerHandler.postDelayed({
                    try {
                        XmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Restore xmsf network failed: ${e.message}")
                    }
                }, 100)
            } catch (e: Exception) {
                Log.e(TAG, "preNotificationHook failed: ${e.message}")
            }
        }
    }

    val isShizukuAvailable: Boolean get() = mShizukuAvailable
    val isPermissionGranted: Boolean get() = mShizukuPermissionGranted
    val isWhitelistBypassed: Boolean get() = mWhitelistBypassed
    val isReady: Boolean get() = mShizukuAvailable && mShizukuPermissionGranted

    fun checkFocusPermission(): Boolean {
        return try {
            val uri = Uri.parse(FOCUS_PROVIDER_URI)
            val extras = Bundle().apply { putString("package", packageName) }
            val result = appContext.contentResolver.call(uri, "canShowFocus", null, extras)
            result?.getBoolean("canShowFocus", false) == true
        } catch (e: Exception) {
            Log.d(TAG, "checkFocusPermission failed: ${e.message}")
            false
        }
    }

    fun isIslandSupported(): Boolean {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val prop = clazz.getMethod("get", String::class.java)
                .invoke(null, "persist.sys.feature.island") as? String
            if ("true" == prop) return true
        } catch (_: Exception) {}

        try {
            val protocol = android.provider.Settings.System.getInt(
                appContext.contentResolver, "notification_focus_protocol", 0)
            if (protocol > 0) return true
        } catch (_: Exception) {}

        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val version = clazz.getMethod("get", String::class.java)
                .invoke(null, "ro.mi.os.version.incremental") as? String
            if (!version.isNullOrEmpty() && (version.startsWith("OS3.") || version.startsWith("OS2."))) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    fun getFocusProtocolVersion(): Int {
        return try {
            android.provider.Settings.System.getInt(
                appContext.contentResolver, "notification_focus_protocol", 0)
        } catch (e: Exception) {
            0
        }
    }

}
