package com.floattime.app

import android.os.Build
import android.util.Log
import androidx.annotation.Keep

/**
 * Live Island 兼容性检测器
 *
 * 通过反射检测系统是否提供 androidx.window.extensions.area API，
 * 不在编译期依赖系统私有类，避免 ClassNotFound 崩溃。
 *
 * 检测策略：
 * 1. 检查 ExtensionWindowAreaPresentation 类是否存在
 * 2. 检查小米/红米/POCO 设备
 * 3. 检查系统 Extensions 版本
 *
 * 与现有方案的关系：
 * - SuperIslandManager (miui.focus.param) → 通知栏 + 超级岛，当前主力方案
 * - ClockTileService (ExtensionWindowArea) → TileService + 直接渲染，增强体验
 * - 两者不冲突，ExtensionWindowArea 可用时走更原生的渲染通路
 */
@Keep
object LiveIslandCompat {

    private const val TAG = "LiveIslandCompat"

    // window.extensions area 相关类
    private const val CLASS_EXTENSION_PRESENTATION =
        "androidx.window.extensions.area.ExtensionWindowAreaPresentation"
    private const val CLASS_EXTENSION_STATUS =
        "androidx.window.extensions.area.ExtensionWindowAreaStatus"
    private const val CLASS_EXTENSION_PROVIDER =
        "androidx.window.extensions.ExtensionProvider"
    private const val CLASS_WINDOW_EXTENSIONS =
        "androidx.window.extensions.WindowExtensions"

    // 缓存
    @Volatile
    private var sIsAvailable: Boolean? = null

    @Volatile
    private var sCachedVersion = -1

    /**
     * 系统是否提供 ExtensionWindowArea API
     */
    fun isAvailable(): Boolean {
        sIsAvailable?.let { return it }
        val result = checkAvailability()
        sIsAvailable = result
        Log.d(TAG, "ExtensionWindowArea available: $result")
        return result
    }

    /**
     * 获取 Extensions 版本号
     * 返回 -1 表示不可用
     */
    fun getExtensionVersion(): Int {
        if (sCachedVersion >= 0) return sCachedVersion

        if (!isAvailable()) {
            sCachedVersion = -1
            return sCachedVersion
        }

        try {
            val providerClass = Class.forName(CLASS_EXTENSION_PROVIDER)
            val provider = providerClass.getMethod("getWindowExtensions").invoke(null)

            if (provider != null) {
                val extensionsClass = Class.forName(CLASS_WINDOW_EXTENSIONS)
                val version = extensionsClass.getMethod("getExtensionVersion").invoke(provider)
                if (version is Int) {
                    sCachedVersion = version
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getExtensionVersion reflection failed: ${e.message}")
        }

        Log.d(TAG, "Extension version: $sCachedVersion")
        return sCachedVersion
    }

    /**
     * 检查 ExtensionWindowAreaStatus 是否激活
     */
    fun isAreaActive(status: Any?): Boolean {
        if (status == null) return false
        return try {
            status.javaClass.getMethod("isActive").invoke(status) as? Boolean == true
        } catch (e: Exception) {
            Log.d(TAG, "isAreaActive failed: ${e.message}")
            false
        }
    }

    /**
     * 获取 WindowArea 显示尺寸
     *
     * @return int[]{width, height}，失败返回 null
     */
    fun getDisplayMetrics(extensions: Any?): IntArray? {
        if (extensions == null) return null
        return try {
            val metrics = extensions.javaClass
                .getMethod("getWindowAreaDisplayMetrics")
                .invoke(extensions) ?: return null
            val w = metrics.javaClass.getMethod("getWidthPixels").invoke(metrics) as? Int ?: 0
            val h = metrics.javaClass.getMethod("getHeightPixels").invoke(metrics) as? Int ?: 0
            intArrayOf(w, h)
        } catch (e: Exception) {
            Log.d(TAG, "getDisplayMetrics failed: ${e.message}")
            null
        }
    }

    /**
     * 获取 ExtensionWindowAreaPresentation 实例
     * 这是将 Compose 内容渲染到岛屿区域的核心接口
     *
     * @return presentation 实例，失败返回 null
     */
    fun getPresentation(extensions: Any?): Any? {
        if (extensions == null) return null
        return try {
            extensions.javaClass
                .getMethod("getWindowAreaPresentation")
                .invoke(extensions)
        } catch (e: Exception) {
            Log.d(TAG, "getPresentation failed: ${e.message}")
            null
        }
    }

    /**
     * 将 ContentView 设置到岛屿区域
     *
     * @param presentation ExtensionWindowAreaPresentation 实例
     * @param contentView  android.view.View (ComposeView)
     * @return 是否成功
     */
    fun setContent(presentation: Any?, contentView: android.view.View?): Boolean {
        if (presentation == null || contentView == null) return false
        return try {
            presentation.javaClass
                .getMethod("setContentView", android.view.View::class.java)
                .invoke(presentation, contentView)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setContent failed: ${e.message}")
            false
        }
    }

    /**
     * 移除岛屿区域内容
     */
    fun removeContent(presentation: Any?): Boolean {
        if (presentation == null) return false
        return try {
            presentation.javaClass.getMethod("dismiss").invoke(presentation)
            true
        } catch (e: Exception) {
            // 备选方法
            try {
                presentation.javaClass.getMethod("close").invoke(presentation)
                true
            } catch (e2: Exception) {
                Log.d(TAG, "removeContent failed: ${e2.message}")
                false
            }
        }
    }

    // =============================================
    //  内部实现
    // =============================================

    private fun checkAvailability(): Boolean {
        // 1. 检查核心类是否存在
        val hasPresentation = classExists(CLASS_EXTENSION_PRESENTATION)
        val hasStatus = classExists(CLASS_EXTENSION_STATUS)
        val hasProvider = classExists(CLASS_EXTENSION_PROVIDER)

        if (!hasPresentation || !hasStatus) {
            Log.d(TAG, "Core classes missing: presentation=$hasPresentation, status=$hasStatus")
            return false
        }

        // 2. 检查是否小米设备
        if (!isXiaomiDevice()) {
            Log.d(TAG, "Not a Xiaomi device")
            return false
        }

        // 3. Provider 可选（有些设备可能直接通过其他方式获取）
        Log.d(TAG, "Availability check passed: provider=$hasProvider")
        return true
    }

    private fun classExists(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi")
            || manufacturer.contains("redmi")
            || manufacturer.contains("poco")
    }
}
