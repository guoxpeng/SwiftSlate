# 为 SwiftSlate 贡献

感谢你想要贡献！以下是需要了解的全部内容。

## 项目理念

- **网络/JSON 零外部依赖** —— 使用 `HttpURLConnection` 和 `org.json`
- **最小 APK 体积** —— 目前约 1.4 MB，请保持
- **API 23+ 兼容** —— 每个功能都必须在 Android 6.0+ 上工作
- **隐私优先** —— 无分析统计、无遥测，除用户配置的 AI 提供方外，数据绝不离开设备

## 开发环境搭建

```bash
git clone https://github.com/YOUR_USERNAME/SwiftSlate-cn.git
cd SwiftSlate-cn
```

用 Android Studio（最新稳定版）打开，同步 Gradle，然后构建：

```bash
./gradlew assembleDebug
```

需要真机测试 —— 无障碍服务在模拟器里无法正常工作。

## 你可以贡献什么

| 领域 | 需要什么 |
|:-----|:-------------|
| **命令** | 提示词实用、新的内置 AI 命令 |
| **提供方** | OpenAI 兼容接口的集成 |
| **翻译** | `app/src/main/res/values-XX/strings.xml` 中的新语言字符串 |
| **Bug 修复** | 尤其是特定 App 里文本替换边界情况的修复 |
| **UI** | 同时兼顾 AMOLED 深色和浅色主题的 Material 3 改进 |

## 架构概览

```
service/AssistantService.kt  → 核心无障碍事件处理
service/CommandRunner.kt     → 共享请求策略（密钥轮换、限流、错误处理），
                               无障碍流程和下面的弹窗共用
ui/processtext/*.kt          → ACTION_PROCESS_TEXT 入口（文本选择弹窗）——
                               同样的命令和请求，不需要无障碍权限
api/*Client.kt               → AI 提供方通信
manager/KeyManager.kt        → 加密密钥存储 + 轮换
manager/CommandManager.kt    → 命令增删改查 + 触发词匹配
ui/*Screen.kt                → Jetpack Compose 界面
```

动核心代码前需要理解的关键行为：
- **触发词检测** 先做快速退出优化（末字符检查）再全量扫描
- 多个触发词都能匹配时，**最长匹配**优先
- **文本替换** 先尝试 `ACTION_SET_TEXT`，失败回退剪贴板粘贴
- AI 处理期间 **加载动画** 在文本输入框内联运行
- **文本选择弹窗** 与无障碍流程共享 `CommandRunner`，但它没有实时文本输入框 —— 只能对弹窗自身的结果提供插入/复制，不支持内置剪贴板/撤销命令

## 测试指南

在 `app/src/test/` 下添加不需要设备就能测试的逻辑单元测试（参考现有测试套件的写法）。任何涉及无障碍服务或文本选择弹窗的部分，真机手工测试仍然必不可少：

1. **在设备上启用无障碍服务**
2. **测试触发词检测** —— 在 WhatsApp、Gmail、备忘录和 Chrome 里输入触发词
3. **验证密码框被跳过** —— 在密码框里输入触发词，不应发生任何事
4. **测试两种命令类型** —— AI 命令（需要 API 密钥）和文本替换（离线）
5. **测试撤销命令** —— `?undo` 应恢复之前的文本
6. **测试文本选择弹窗** —— 在 App 里选中文本，在复制/分享菜单点 SwiftSlate，运行命令，确认插入/复制可用 —— 分别在无障碍开启和关闭两种情况下测试
7. **在 API 23 上验证** —— 如果用了任何更新的 API，用版本检查保护起来

## 代码风格

- Kotlin，遵循代码库现有约定
- 不用自动格式化工具 —— 与已有代码风格保持一致即可
- Compose UI 遵循 `ui/components/` 中现有的组件模式
- 保持函数聚焦、短小

## 微信适配分支（cn-zh）提交流程

本 fork 在 upstream 之上额外维护微信适配与中文文档，仓库地址为 `https://github.com/guoxpeng/SwiftSlate-cn`，默认分支是 `cn-zh`。涉及微信适配的改动按下面的流程走。

### 分支结构

| 分支 | 说明 |
|:-----|:-----|
| `cn-zh` | **本 fork 默认分支**，基于 upstream v1.0.80，仓库为 `guoxpeng/SwiftSlate-cn`，含微信适配（preview）与中文文档 |
| `master` | 早期微信适配实验（v1.0.73 时期），已由 `cn-zh` 取代 |
| `Musheer360/SwiftSlate:master` | 上游主线 |

### 微信适配改动只落在 preview

伪装白名单服务**仅存在于 `app/src/preview/`**，稳定版不含：

- `app/src/preview/AndroidManifest.xml` —— 注册两个 no-op 服务
- `app/src/preview/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt`
- `app/src/preview/java/com/dianming/phoneapp/MyAccessibilityService.kt`
- `app/src/preview/res/xml/fake_accessibility_service_config.xml`
- `app/src/preview/res/values/strings.xml`

必须遵守的规则：

- **R8 不能重命名这两个伪装服务类**（类名本身就是匹配依据），`app/proguard-rules.pro` 里的 `-keep` 规则必须保留
- `WHITELIST_SERVICE` BuildConfig 字段定义在 `defaultConfig`（空串），`preview` 覆盖为 `com.dianming.phoneapp.MyAccessibilityService` —— 保证稳定版也能编译
- `Log.e` 承载 `SwiftSlateDiag` 诊断日志，proguard 只剥离 v/d/i/w，**不要**把 `e` 加回 `-assumenosideeffects`

### 构建与真机验证

```bash
# 构建 preview APK
./gradlew assemblePreview
adb install -r app/build/outputs/apk/preview/app-preview.apk

# 启用「微信适配」伪装服务 + 主服务（方案 2：点名类名）
adb shell settings put secure enabled_accessibility_services \
  "com.musheer360.swiftslate.preview/com.musheer360.swiftslate.service.AssistantService:\
com.musheer360.swiftslate.preview/com.dianming.phoneapp.MyAccessibilityService"

# 看诊断日志
adb logcat -d -v time -s SwiftSlateDiag:E
```

成功标志：微信内出现 `TEXT_CHANGED pkg=com.tencent.mm`、`srcNull=false`、`text=[...]` 可读、`findCommand` 非空、`ACTION_SET_TEXT=true` 且 verify 通过。完整测试流程见 [WECHAT_COMPAT.md](WECHAT_COMPAT.md)。

### 微信适配改动的提交与 PR

1. 基于 `cn-zh` 建分支：`git checkout -b wechat/你的改动 cn-zh`
2. 微信适配改动只进 `preview`；通用修复（如 `srcNull` 兜底、崩溃加固）优先提给上游
3. 真机验证通过后：`git push fork wechat/你的改动`
4. 微信适配改动 → 对本 fork 的 `cn-zh` 开 PR；通用上游修复 → 对 `Musheer360/SwiftSlate:master` 开 PR

### 注意事项

- 伪装服务借用了 Google 与点名的类名，属**本地自用规避手段**，不要提交到 upstream 稳定版或公开 Play 渠道（见 WECHAT_COMPAT.md 第 6 节）
- 微信升级后需重新核验白名单条目（硬编码在微信 `AccExptServiceKt` 的 clinit 中）

## 提交 PR

1. Fork 并创建分支：`feature/your-thing` 或 `fix/the-bug`（微信适配相关用 `wechat/xxx`，见上一节）
2. 做出修改
3. 在真机上开启无障碍服务测试
4. 运行 `./gradlew assembleDebug` —— 必须干净构建通过
5. 通用改动针对上游 `master` 提交 PR，微信适配改动针对本 fork 的 `cn-zh` 提交 PR —— 填写模板

## 我不会合并的内容

- 引入第三方网络/JSON 库的 PR
- 没有版本检查、破坏 API 23 兼容性的改动
- 收集用户数据或添加遥测的功能
- 无法干净构建的代码

## 有问题？

在仓库 Discussions 里发起讨论，或直接联系我。编码愉快！
