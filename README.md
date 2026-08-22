<div align="center">

<br>

<img src="playstore-icon.png" width="140" alt="SwiftSlate Icon" />

<br>

# SwiftSlate（CN 汉化 / 微信适配版）

### 系统级 AI 文本助手 —— 在微信里也能用 `?fix`

<br>

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-快速开始)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)
[![License: MIT](https://img.shields.io/badge/MIT-blue?style=for-the-badge&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![上游最新](https://img.shields.io/github/v/release/Musheer360/SwiftSlate?style=flat-square&label=上游最新&color=brightgreen)](https://github.com/Musheer360/SwiftSlate/releases/latest)

<br>

[<img src="https://img.shields.io/badge/⬇_下载_微信适配版_APK-282828?style=for-the-badge" alt="下载微信适配版 APK" height="36">](https://github.com/guoxpeng/SwiftSlate/releases/latest)
&nbsp;&nbsp;
[<img src="https://img.shields.io/badge/📖_微信适配说明-282828?style=for-the-badge" alt="微信适配说明" height="36">](WECHAT_COMPAT.md)

<br>

</div>

> [!IMPORTANT]
> **本仓库是个人 CN 汉化 / 微信适配分支**（默认分支 `cn-zh`），基于上游 SwiftSlate **v1.0.76**。与上游相比，本版本：
> - **💬 在微信聊天输入框里也能用 `?fix` 等命令** —— 无需 root，preview 构建内置「伪装白名单」无障碍服务，绕过微信的反无障碍对抗机制
> - **🇨🇳 完整简体中文翻译与中文文档**（README / WECHAT_COMPAT / CONTRIBUTING / SECURITY / CODE_OF_CONDUCT 全部中文化）
> - **🔍 SwiftSlateDiag 真机诊断日志**，连 adb 即可快速排查
>
> 上游原版：[Musheer360/SwiftSlate](https://github.com/Musheer360/SwiftSlate)

## ✨ 本版本特色（相对上游）

| 特色 | 说明 |
|:-----|:-----|
| 💬 **微信适配** | preview 构建内置「伪装白名单」无障碍服务，微信不再清空节点树，聊天输入框内直接触发命令（详见[微信适配](#-微信适配本分支新增)） |
| 🇨🇳 **中文汉化** | `values-zh` / `values-zh-rCN` 134/134 键全覆盖；全部文档中文化 |
| 🔍 **真机诊断** | `adb logcat -s SwiftSlateDiag:E` 输出事件、文本、替换全链路日志 |
| 🛡️ **稳定版不受影响** | 伪装服务只在 preview flavor 里，稳定版不包含任何微信相关代码 |

## 💬 微信适配（本分支新增）

### 问题背景

微信 8.0.52+ 内置**反无障碍对抗机制**：检测到非白名单的无障碍服务时，会**清空/伪造当前窗口的节点树**，导致第三方 App 收不到事件、读不到文本、触发不了命令。该机制按「已启用服务的 `包名/类名` 字符串 contains 白名单条目」判断，白名单仅两条硬编码（逆向自微信 8.0.74 / versionCode 3120 的 `AccExptServiceKt` clinit）：

- `com.google.android.accessibility.selecttospeak.SelectToSpeakService`（Google 随选朗读）
- `com.dianming.phoneapp.MyAccessibilityService`（点名）

### 方案

preview 构建内置两个 **no-op 伪装白名单服务**，类名精确等于白名单条目。启用后微信认为"白名单服务已启用"，`isAccessibilityEnabled()` 返回 true，停止清空节点树。**已真机验证**（Realme RMX2202，Android 14，无 root）：微信内 `TEXT_CHANGED` 事件正常、文本可读、`hello world ，fix` 端到端替换成功。

### 无需 root，但换手机有三个变量

**机制本身不需要 root** —— 只依赖标准的「设置 → 无障碍」开关，和任何无障碍 App 一样。但换手机 / 换账号时能否生效取决于三点，**需要逐台实测**：

1. **微信版本** —— 白名单类名硬编码在微信 8.0.74（versionCode 3120）客户端。同版本或名单未变的版本可用；新版若轮换名单（或改匹配逻辑）会失效，需重新逆向确认。
2. **账号服务端配置** —— 微信按账号下发实验配置（MMKV 缓存），含服务端可配置白名单 `clicfg_acc_white_service_list`、清空概率因子 `accinfo_clear_strike` 等。**不同账号可能收到不同配置**，硬编码类名不一定命中。
3. **机型 / OEM 限制** —— 部分国产 ROM（小米 HyperOS、一加、三星等）会隐藏或限制第三方无障碍服务；Android 13+ 侧载应用需先在「应用信息 → ⋮ → 允许受限设置」授权，无障碍开关才能打开。

**通用兜底（方案 3，无 root）**：若伪装服务在某台手机 / 某账号下无效，可开启系统自带的「随选朗读 / Select to Speak」或 TalkBack 触摸浏览 —— 真实的 Google 随选朗读服务名本身就在微信白名单里，能让微信放行，**无需安装任何东西**。注意国行 ROM 不一定自带该组件（测试机 Realme 的 TalkBack 内带有）。

### 安装启用（3 步）

1. 安装本分支 [Release](https://github.com/guoxpeng/SwiftSlate/releases/latest) 的 `app-preview.apk`（或本地 `./gradlew assemblePreview` 构建）
2. 设置 → 无障碍 → 同时启用 **「SwiftSlate 微信适配」** 和 **「SwiftSlate 微信适配」**
3. 打开微信聊天输入框，输入 `hello world ，fix` 试试

### 验证成功标志（连 adb 时）

```
adb logcat -d -v time -s SwiftSlateDiag:E
```

出现 `TEXT_CHANGED pkg=com.tencent.mm`、`srcNull=false`、`text=[...]` 可读、`findCommand result=…`、`ACTION_SET_TEXT=true` 即为成功。

> 完整逆向机制、方案对比、逐文件改动清单见 [WECHAT_COMPAT.md](WECHAT_COMPAT.md)。

## 🚀 快速开始

### 前置要求

- **Android 6.0+**（API 23 及以上）
- **API 密钥**：Gemini 免费密钥（[aistudio.google.com](https://aistudio.google.com)）、Groq 或任意 OpenAI 兼容提供方（文本替换命令不需要）

### 安装

- **微信适配版（本分支）**：下载 [Release](https://github.com/guoxpeng/SwiftSlate/releases/latest) 的 `app-preview.apk` —— 与官方稳定版可并存安装，互不影响数据
- **官方稳定版**：上游 [Releases](https://github.com/Musheer360/SwiftSlate/releases/latest) 或 [F-Droid](https://f-droid.org/en/packages/com.musheer360.swiftslate/)

### 三步设置

1. 🔑 **密钥**：打开「密钥」标签页，添加 API 密钥（保存前会实时校验）
2. ♿ **服务**：仪表盘点「启用」→ 无障碍设置里打开 **SwiftSlate 微信适配**（微信适配版还需打开 **SwiftSlate 微信适配**）
3. ✍️ **输入**：任意输入框末尾输入 `?fix` 等触发词

## 🧩 内置命令

| 触发词 | 作用 |
|:--------|:-------|
| `?fix` | 修正语法、拼写与标点 |
| `?improve` | 提升清晰度与可读性 |
| `?shorten` / `?expand` | 精简 / 展开 |
| `?formal` / `?casual` | 正式 / 轻松语气 |
| `?emoji` | 添加贴切 emoji |
| `?human` | 让 AI 文本更像人写的 |
| `?reply` | 生成上下文回复 |
| `?translate:XX` | 翻译成任意语言 |
| `?undo` | 恢复上一次替换前的文本 |
| `?copy` / `?cut` / `?paste` / `?replace` | 剪贴板命令（完全离线） |
| 自定义文本替换命令 | 完全离线、零延迟（签名、常用回复、快捷输入等） |

> 完整命令示例、AI 提供方配置（Gemini / Groq / 自定义接口）和更多上游功能见[上游 README](https://github.com/Musheer360/SwiftSlate#readme)。

## ⚠️ 已知限制

- **部分 App 使用自定义输入框**（WebView 类编辑器，如 Google Keep、三星备忘录）可能忽略替换 —— 可改用[文本选择菜单](https://github.com/Musheer360/SwiftSlate#-text-selection-menu)
- **微信适配依赖微信版本 / 账号 / 机型**，换设备后需按上文逐台实测；无效时用系统随选朗读兜底
- **部分 OEM 限制无障碍服务**，且激进的电池优化可能静默停用服务（仪表盘显示未激活时重新启用即可）
- **部分银行 App** 在开启任意无障碍服务时拒绝打开（银行 App 自身的安全行为，无法从我们这侧修复）

## 🔨 从源码构建

```bash
git clone https://github.com/guoxpeng/SwiftSlate.git
cd SwiftSlate

# 微信适配版（preview，推荐）
./gradlew assemblePreview
# 产物：app/build/outputs/apk/preview/app-preview.apk

# 普通调试版
./gradlew assembleDebug
```

## 📄 许可证与上游

- 本分支基于上游 [Musheer360/SwiftSlate](https://github.com/Musheer360/SwiftSlate)，遵循 **MIT License**（见 [LICENSE](LICENSE)）
- 微信适配详细技术文档：[WECHAT_COMPAT.md](WECHAT_COMPAT.md)
- 贡献指南（含微信适配分支提交流程）：[CONTRIBUTING.md](CONTRIBUTING.md)
