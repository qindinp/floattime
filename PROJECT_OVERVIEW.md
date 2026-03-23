# FloatTime 项目整理报告

## 📁 项目结构概览

```
floattime/
├── 📱 Android 原生层 (Capacitor 集成)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml    # 权限配置：悬浮窗、前台服务、通知
│   │   │   ├── java/com/floattime/app/
│   │   │   │   ├── MainActivity.java  # 主入口
│   │   │   │   └── FloatTimeService.java  # 悬浮窗前台服务
│   │   │   └── res/                   # 图标、布局资源
│   │   └── build.gradle               # App 构建配置
│   ├── capacitor.build.gradle         # Capacitor 插件配置
│   └── gradle/                        # Gradle wrapper
│
├── 💻 Web 应用层 (Vue 3 + Vite)
│   ├── src/
│   │   ├── App.vue                    # 主组件：悬浮球 + 设置面板
│   │   └── main.ts                    # 应用入口
│   ├── index.html                     # HTML 模板
│   ├── vite.config.ts                 # Vite 构建配置
│   ├── tsconfig.json                  # TypeScript 配置
│   └── package.json                   # 依赖管理
│
├── 🔧 构建 & 部署
│   ├── .github/workflows/
│   │   └── build-android.yml          # GitHub Actions: 自动构建 APK
│   ├── capacitor.config.json          # Capacitor 应用配置
│   └── dist/                          # Web 构建输出目录
│
└── 📄 项目文档
    ├── README.md                      # 项目说明
    └── CHANGELOG.md                   # 版本更新日志
```

---

## 🎯 核心功能

### 1. 悬浮时间显示
- 可拖拽的悬浮球，显示当前时间
- 支持淘宝/美团/本地三种时间源
- 自动同步服务器时间，精度 ±50ms

### 2. 主题系统
- 日间/夜间/跟随系统 三种模式
- 深色/浅色主题自动切换
- 位置持久化存储

### 3. 秒表功能
- 内置秒表计时器
- 毫秒级精度显示

### 4. Android 原生功能
- 悬浮窗权限 (SYSTEM_ALERT_WINDOW)
- 前台服务保活 (FOREGROUND_SERVICE)
- 通知栏显示

---

## 🔧 技术栈

| 层级 | 技术 |
|------|------|
| 前端框架 | Vue 3 + TypeScript |
| 构建工具 | Vite 5 |
| 移动端框架 | Capacitor 6 |
| 原生平台 | Android (Java) |
| CI/CD | GitHub Actions |

---

## 📦 依赖清单

### 核心依赖
- `@capacitor/core` ^6.2.0 - Capacitor 核心
- `@capacitor/android` ^6.2.0 - Android 平台
- `@capacitor/cli` ^6.2.0 - CLI 工具

### 开发依赖
- `vite` ^5.4.0 - 构建工具
- `@vitejs/plugin-vue` ^5.1.0 - Vue 插件
- `vue` ^3.5.0 - 前端框架
- `typescript` ^5.6.0 - 类型系统

---

## 🚀 构建流程

```
1. npm ci              # 安装依赖
2. npx vite build      # 构建 Web 应用 → dist/
3. npx cap sync android # 同步到 Android 项目
4. ./gradlew assembleDebug  # 构建 APK
```

---

## 📝 关键配置

### Capacitor 配置 (capacitor.config.json)
```json
{
  "appId": "com.floattime.app",
  "appName": "FloatTime",
  "version": "1.2.1",
  "webDir": "dist"
}
```

### Vite 配置亮点
- 基础路径: `./` (相对路径，适配 Android)
- Vue 单独打包: 优化缓存
- 资源哈希: 长期缓存策略
- 代码压缩: esbuild

---

## 🔐 权限声明

```xml
<!-- 网络 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- 悬浮窗 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- 通知 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 🔄 版本历史

| 版本 | 更新内容 |
|------|----------|
| v1.2.1 | 当前版本，修复内存泄漏、CORS 代理、位置持久化 |

---

## 🎯 整理建议

### ✅ 已完成
- [x] 清晰的目录结构
- [x] GitHub Actions 自动构建
- [x] 完整的权限配置
- [x] 深色/浅色主题支持

### 🔧 可优化项
1. **代码组织**: App.vue 较大 (28KB)，可拆分为多个组件
2. **类型定义**: 可提取 TimeSource、ThemeMode 到独立 types 文件
3. **工具函数**: parseServerTime、formatMs 等可提取到 utils
4. **常量配置**: CORS_PROXIES、endpoints 可配置化

### 📂 建议目录调整
```
src/
├── components/
│   ├── FloatBall.vue      # 悬浮球组件
│   ├── SettingsPanel.vue  # 设置面板组件
│   └── Stopwatch.vue      # 秒表组件
├── composables/
│   ├── useTimeSync.ts     # 时间同步逻辑
│   ├── useTheme.ts        # 主题管理
│   └── useDrag.ts         # 拖拽逻辑
├── utils/
│   ├── time.ts            # 时间格式化
│   ├── storage.ts         # localStorage 封装
│   └── proxy.ts           # CORS 代理
├── types/
│   └── index.ts           # 类型定义
├── App.vue
└── main.ts
```

---

## 📊 项目状态

- **版本**: v1.2.1
- **构建状态**: ✅ 可正常构建
- **CI/CD**: ✅ GitHub Actions 配置完成
- **文档**: ✅ README + CHANGELOG

---

*整理时间: 2026-03-23*
