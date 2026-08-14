# 为 SwiftSlate 贡献

感谢你想要贡献！以下是需要了解的全部内容。

## 项目理念

- **网络/JSON 零外部依赖** —— 使用 `HttpURLConnection` 和 `org.json`
- **最小 APK 体积** —— 目前约 1.4 MB，请保持
- **API 23+ 兼容** —— 每个功能都必须在 Android 6.0+ 上工作
- **隐私优先** —— 无分析统计、无遥测，除用户配置的 AI 提供方外，数据绝不离开设备

## 开发环境搭建

```bash
git clone https://github.com/YOUR_USERNAME/SwiftSlate.git
cd SwiftSlate
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

## 提交 PR

1. Fork 并创建分支：`feature/your-thing` 或 `fix/the-bug`
2. 做出修改
3. 在真机上开启无障碍服务测试
4. 运行 `./gradlew assembleDebug` —— 必须干净构建通过
5. 针对 `master` 提交 PR —— 填写模板

## 我不会合并的内容

- 引入第三方网络/JSON 库的 PR
- 没有版本检查、破坏 API 23 兼容性的改动
- 收集用户数据或添加遥测的功能
- 无法干净构建的代码

## 有问题？

在仓库 Discussions 里发起讨论，或直接联系我。编码愉快！
