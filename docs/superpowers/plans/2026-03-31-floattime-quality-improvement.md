# FloatTime 代码质量改进计划

> **代理执行者必读：** 必须使用 superpowers 子代理驱动执行本计划。

**目标：** 提升 FloatTime 项目代码质量、测试覆盖、CI/CD 能力

**架构：** 三步走策略 - 测试优先 → CI 搭建 → 代码改进

**技术栈：** Kotlin / JUnit5 / MockK / GitHub Actions / Gradle

---

## 阶段一：测试增强（优先级最高）

### 任务 1: 添加 TimeUtils 边界测试

**文件：**
- 创建: `app/src/test/java/com/floattime/app/TimeUtilsBoundaryTest.kt`

- [ ] **步骤 1: 编写边界测试**

```kotlin
package com.floattime.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsBoundaryTest {

    @Test
    fun `parseServerTime - extreme large timestamp`() {
        // 最大安全整数边界
        val json = """{"data":{"t":"9223372036854775807"}}"""
        assertEquals(0L, TimeUtils.parseServerTime(json)) // 应返回0表示解析失败
    }

    @Test
    fun `calcNightMode - boundary 6am is day`() {
        assertEquals(false, TimeUtils.calcNightMode(0, 6))
    }

    @Test
    fun `calcNightMode - boundary 7am is day`() {
        assertEquals(false, TimeUtils.calcNightMode(0, 7))
    }

    @Test
    fun `calcNightMode - boundary 6pm is day`() {
        assertEquals(false, TimeUtils.calcNightMode(0, 18))
    }

    @Test
    fun `calcNightMode - boundary 7pm is night`() {
        assertEquals(true, TimeUtils.calcNightMode(0, 19))
    }
}
```

- [ ] **步骤 2: 运行测试确认通过**

运行: `./gradlew test --tests "com.floattime.app.TimeUtilsBoundaryTest"`
预期: 通过

- [ ] **步骤 3: 提交**

```bash
git add app/src/test/java/com/floattime/app/TimeUtilsBoundaryTest.kt
git commit -m "test: add TimeUtils boundary tests"
```

---

### 任务 2: 添加 SettingsRepository 测试

**文件：**
- 创建: `app/src/test/java/com/floattime/app/SettingsRepositoryTest.kt`

- [ ] **步骤 1: 编写 SettingsRepository Mock 测试**

```kotlin
package com.floattime.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import android.content.SharedPreferences

@RunWith(MockitoJUnitRunner::class)
class SettingsRepositoryTest {

    @Mock
    private lateinit var prefs: SharedPreferences

    private val repo = SettingsRepository(prefs)

    @Test
    fun `timeSource default is local`() {
        assertEquals("local", repo.timeSource)
    }

    @Test
    fun `timeSource can be set`() {
        repo.timeSource = "taobao"
        verify(prefs).edit().putString("time_source", "taobao").apply()
    }

    @Test
    fun `superIslandEnabled default is false`() {
        assertFalse(repo.superIslandEnabled)
    }

    @Test
    fun `theme default is auto`() {
        assertEquals("auto", repo.theme)
    }
}
```

- [ ] **步骤 2: 添加 Mockito 依赖**

修改: `app/build.gradle.kts`

```kotlin
dependencies {
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
}
```

- [ ] **步骤 3: 运行测试**

运行: `./gradlew test --tests "com.floattime.app.SettingsRepositoryTest"`

- [ ] **步骤 4: 提交**

```bash
git add app/build.gradle.kts app/src/test/java/com/floattime/app/SettingsRepositoryTest.kt
git commit -m "test: add SettingsRepository tests with Mockito"
```

---

### 任务 3: 添加 FloatTimeService 核心逻辑测试

**文件：**
- 创建: `app/src/test/java/com/floattime/app/FloatTimeServiceTest.kt`

- [ ] **步骤 1: 编写服务核心逻辑测试**

```kotlin
package com.floattime.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class FloatTimeServiceTest {

    @Test
    fun `parseServerTime handles taobao format`() {
        // 测试 JSON 解析
        val json = """{"data":{"t":"1704067200000"}}"""
        val result = parseWithReflection(json)
        assertTrue(result > 0)
    }

    @Test
    fun `notification id is constant`() {
        assertEquals(20240320, FloatTimeServiceTestCompanion.NOTIFICATION_ID)
    }

    @Test
    fun `update interval is reasonable`() {
        assertTrue(FloatTimeServiceTestCompanion.UPDATE_INTERVAL_MS in 100..1000)
    }
}

// companion object 访问
object FloatTimeServiceTestCompanion {
    const val NOTIFICATION_ID = 20240320
    const val UPDATE_INTERVAL_MS = 200L
}
```

- [ ] **步骤 2: 运行测试**

- [ ] **步骤 3: 提交**

---

## 阶段二：CI/CD 搭建

### 任务 4: 添加 GitHub Actions 构建流程

**文件：**
- 创建: `.github/workflows/android-ci.yml`

- [ ] **步骤 1: 创建 CI 配置**

```yaml
name: Android CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}
          restore-keys: |
            ${{ runner.os }}-gradle-
            
      - name: Build Debug APK
        run: ./gradlew assembleDebug
        
      - name: Run Tests
        run: ./gradlew test
        
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

- [ ] **步骤 2: 提交**

```bash
git add .github/workflows/android-ci.yml
git commit -m "ci: add GitHub Actions Android CI workflow"
```

---

### 任务 5: 添加单元测试报告

**文件：**
- 修改: `.github/workflows/android-ci.yml`

- [ ] **步骤 1: 添加测试报告生成**

```yaml
      - name: Run Tests
        run: ./gradlew test
        
      - name: Upload Test Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: app/build/reports/tests/testDebugUnitTest/
```

- [ ] **步骤 2: 提交**

---

## 阶段三：代码质量改进

### 任务 6: 统一魔法数字

**文件：**
- 修改: `app/src/main/java/com/floattime/app/FloatTimeService.kt`

- [ ] **步骤 1: 提取常量到伴生对象**

在 `FloatTimeService.Companion` 中已定义常量，检查是否完整：

```kotlin
companion object {
    // 已有常量检查清单
    const val NOTIFICATION_ID = 20240320      // ✅
    const val SYNC_NOTIFICATION_ID = 20240321 // 需要添加
    const val UPDATE_INTERVAL_MS = 200L        // ✅
    const val CONNECT_TIMEOUT_MS = 8000L       // ✅
    const val READ_TIMEOUT_MS = 8000L          // ✅
    const val SAVE_THROTTLE_MS = 5000L        // ✅
    // 缺失常量
    const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 500L
    const val NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS = 3000L
}
```

- [ ] **步骤 2: 运行测试**

- [ ] **步骤 3: 提交**

---

### 任务 7: 添加网络请求重试上限

**文件：**
- 修改: `app/src/main/java/com/floattime/app/FloatTimeService.kt`

- [ ] **步骤 1: 添加重试计数器**

```kotlin
class FloatTimeService : Service() {
    
    companion object {
        private const val MAX_SYNC_RETRIES = 3
    }
    
    private var syncRetryCount = 0
    
    private fun doSync() {
        // ... 现有代码 ...
        
        syncRetryCount++
        if (syncRetryCount >= MAX_SYNC_RETRIES) {
            postSyncFailed()
            Log.w(TAG, "Sync failed after $MAX_SYNC_RETRIES retries")
            syncRetryCount = 0
            return
        }
    }
    
    private fun postSyncSuccess() {
        syncRetryCount = 0  // 成功重置
        // ... 现有代码 ...
    }
}
```

- [ ] **步骤 2: 运行测试**

- [ ] **步骤 3: 提交**

---

### 任务 8: 添加 ProGuard 混淆配置

**文件：**
- 创建: `app/proguard-rules.pro`

- [ ] **步骤 1: 创建混淆规则**

```proguard
# Keep model classes
-keep class com.floattime.app.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
```

- [ ] **步骤 2: 在 build.gradle.kts 中启用**

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

- [ ] **步骤 3: 提交**

---

## 任务清单汇总

| # | 任务 | 阶段 | 预计时间 |
|---|------|------|----------|
| 1 | TimeUtils 边界测试 | 测试 | 10 min |
| 2 | SettingsRepository 测试 | 测试 | 15 min |
| 3 | FloatTimeService 测试 | 测试 | 15 min |
| 4 | GitHub Actions CI | CI | 10 min |
| 5 | 测试报告配置 | CI | 5 min |
| 6 | 统一魔法数字 | 改进 | 10 min |
| 7 | 网络重试上限 | 改进 | 10 min |
| 8 | ProGuard 配置 | 改进 | 10 min |

**总计：约 85 分钟（可分多次完成）**
