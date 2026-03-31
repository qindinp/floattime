# FloatTime 代码质量改进设计文档

**版本：** 1.0
**日期：** 2026-03-31
**状态：** 已批准

---

## 1. 目标

提升 FloatTime Android 项目的代码质量、测试覆盖和持续集成能力。

## 2. 当前问题

### 2.1 测试覆盖不足
- 仅有 1 个测试类 (TimeUtilsTest)
- Service 层无测试
- Repository 层无测试

### 2.2 缺少 CI/CD
- 无自动化构建
- 无自动化测试
- 无 APK 自动发布

### 2.3 代码质量问题
- 魔法数字分散
- 网络请求无重试上限
- 缺少 ProGuard 配置

## 3. 改进方案

### 3.1 测试增强
- 添加 TimeUtils 边界测试
- 添加 SettingsRepository Mock 测试
- 添加 FloatTimeService 核心逻辑测试

### 3.2 CI/CD 搭建
- GitHub Actions 构建流程
- 单元测试报告生成
- APK 自动上传

### 3.3 代码改进
- 统一魔法数字常量
- 添加网络请求重试上限
- 添加 ProGuard 混淆规则

## 4. 架构

```
阶段一：测试增强
├── 任务1: TimeUtils 边界测试
├── 任务2: SettingsRepository 测试
└── 任务3: FloatTimeService 测试

阶段二：CI/CD
├── 任务4: GitHub Actions 配置
└── 任务5: 测试报告

阶段三：代码改进
├── 任务6: 统一常量
├── 任务7: 重试上限
└── 任务8: ProGuard
```

## 5. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| Mock 测试在 CI 失败 | 使用 shadowJar 或 androidTest |
| AGP 版本兼容 | 锁定版本号 |
| 反射 API 失效 | 版本化适配器模式 |

## 6. 验收标准

- [ ] 测试覆盖率提升至 50%+
- [ ] CI 构建通过率 100%
- [ ] 无 Critical/Important 问题
