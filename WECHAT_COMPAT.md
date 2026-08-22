# SwiftSlate：微信（com.tencent.mm）兼容补丁

**读者对象：** 本项目维护者（及任何想集成微信支持的人）。本文档解释微信为什么会破坏 SwiftSlate、我们采用的最小修复方案、涉及的每个文件，以及如何在上游采用。

> 适用范围：补丁目前只打包在 `preview` 构建类型中，不影响稳定版。建议上游采用相同的结构（见"上游集成建议"）。
>
> 版本：本文档对应 **v1.0.76** 代码库（upstream `master` 的 `cabd098`）。上游已在 #125 中自行采纳 `srcNull` 兜底，本分支现在只额外提供：preview 伪装白名单服务、递归 editable 节点搜索、`SwiftSlateDiag` `Log.e` 诊断、UI 提示（见 §3）。

---

## 1. 问题：微信主动对抗无障碍服务

微信 8.0.74（versionCode 3120，可推断所有当前版本）内置**反无障碍防御机制**：检测到非白名单的无障碍服务后，会**清空（或伪造）当前活动窗口的无障碍节点树**。可见症状：

- `rootInActiveWindow` 返回的树里节点是空的（`rootCls` 只对根节点报真实类名，子 `EditText` 节点全部消失）。
- 聊天输入框的 `TYPE_VIEW_TEXT_CHANGED` 事件要么根本不来，要么 source 为空/残缺。
- SwiftSlate 读不到文本，`?fix` / `?translate` 永远无法触发。

这**不是** FLAG_SECURE：微信窗口并未设置该标志（已用 `dumpsys window` 核实）。空树是微信无障碍桥接层在软件层面主动产生的。

### 逆向确认的机制（对照 smali 验证）

| 环节 | 位置（smali） | 行为 |
|---|---|---|
| 总开关 | `AccUtil.smali` — `isAccessibilityEnabled()` | 以下**任一**命中返回 `true`（→ 不攻击）：monkey 环境、TalkBack 触摸浏览开启、白名单服务已启用、账号被服务端白名单。否则返回 `false` → 攻击激活。 |
| 白名单匹配 | `AccExptService.smali` — `checkHasServiceInList()` | 读取 `Settings.Secure.enabled_accessibility_services`，对每个已启用服务的 `package/name` 字符串与每条白名单项做 **`CharSequence.contains()`** 匹配。 |
| 白名单条目 | `AccExptServiceKt.smali`（clinit） | 恰好两条、硬编码：`com.google.android.accessibility.selecttospeak.SelectToSpeakService` 和 `com.dianming.phoneapp.MyAccessibilityService`。 |
| 清空节点树 | `base/MapExpandKt.smali` — `clearInfo()`/`toFakeInfo()`；入口 `MMAccessibilityDelegateWrap.smali:340` `onInitializeAccessibilityNodeInfo()` | 激活时 `needClearNodeInfo()` 让 delegate 直接返回、不填充节点 → 空树。`needUseFakeInfo()` 伪造虚假文本。 |
| 服务端配置 | `q15/a.smali` + `AccConfigManager.smali` | 字段如 `accinfo_clear_strike`、`accinfo_random_strike`、`intercept_stack`；缓存在 MMKV，定期过期。 |

**关键洞察：** 白名单匹配是对服务 `package/name` 字符串做 `contains()` 匹配，目标只有两条硬编码类名。微信并不验证类是否真的属于 Google 或点名 —— 只检查字符串。

---

## 2. 修复方案：类名*就是*白名单条目的 no-op 服务

因为匹配是 `package/name` **包含**白名单 FQCN，SwiftSlate 可以声明一个类名完全等于白名单条目之一的无障碍服务。微信看到"白名单服务已启用"，`isAccessibilityEnabled()` 返回 `true`，清空节点树的攻击就永远不会激活。该服务本身什么都不做。

声明了两个这样的服务（每条白名单一项一个，互为冗余 —— 微信若轮换其中一条，另一条仍能命中）：

- `com.google.android.accessibility.selecttospeak.SelectToSpeakService`
- `com.dianming.phoneapp.MyAccessibilityService`

两个都是 no-op（`onAccessibilityEvent`/`onInterrupt` 为空）。它们**只**存在于 `preview` 源码集，稳定版中不存在。

**真机验证（Realme RMX2202，Android 14，无 root）：** 启用兼容服务 + SwiftSlate 主服务后，微信 `rootCls` 重新报告 `android.widget.FrameLayout`，`TEXT_CHANGED` 事件带真实 source 正常到达，文本可读，`?fix` 替换端到端成功。**不需要 root** —— 只依赖标准的无障碍设置开关。

---

## 3. 逐文件改动清单

### 新增文件（仅 preview）

| 文件 | 用途 |
|---|---|
| `app/src/preview/AndroidManifest.xml` | 注册两个 no-op 兼容服务（label、`BIND_ACCESSIBILITY_SERVICE` 权限、标准 a11y meta-data）。 |
| `app/src/preview/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt` | no-op `AccessibilityService`；类名匹配白名单第 1 条。 |
| `app/src/preview/java/com/dianming/phoneapp/MyAccessibilityService.kt` | no-op `AccessibilityService`；类名匹配白名单第 2 条。 |

### 修改的文件

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | `buildConfigField("String", "WHITELIST_SERVICE", ...)`：在 **`defaultConfig` 中定义为空串**（这样稳定版也能编译 —— 该字段被主源码引用），并在 `preview` buildType 中覆盖为 `"com.dianming.phoneapp.MyAccessibilityService"`。Dashboard 提示据此判断兼容服务是否已启用。 |
| `app/proguard-rules.pro` | `-keep` 两个 no-op 服务类的 `<init>()` —— **R8 绝不能重命名它们**，FQCN 本身就是功能。（类名必须原样通过混淆。）同时保留 `Log.e`（承载 `SwiftSlateDiag` 诊断；`-assumenosideeffects` 只剥离 v/d/i/w）。 |
| `gradle.properties` | fork 发布固定版本：`versionName=1.0.76`、`versionCode=227`（可用 `-PversionName`/`-PversionCode` 覆盖）。 |
| `app/src/preview/res/values/strings.xml`（+ `values-zh`、`values-zh-rCN`） | 应用名与无障碍服务名（`app_name` = `SwiftSlate 微信适配`），主程序服务名（`accessibility_service_label` = `SwiftSlate（主程序）`），以及两个兼容服务的显示名（`SwiftSlate 微信适配` / `SwiftSlate 微信适配（备选）`）。 |
| `app/src/main/java/.../service/AssistantService.kt` | **(a)** `srcNull` 兜底改进：上游 #125 的兜底用 `root.findFocus(FOCUS_INPUT)`，对 WebView 类编辑器会返回 WebView 容器节点（不可编辑）。本分支改为遍历整棵树寻找**同时满足 editable 且 focused** 的节点（`findFocusedEditableSource` / `findFocusedEditable`），保留上游的节流与崩溃加固。**(b)** 事件链路和 `replaceText` 上的 `Log.e` 诊断输出（`SwiftSlateDiag`）。**(c)** `startWindowDump()` 调试用 dump 循环，**默认禁用**（调用被注释）—— 每 3 秒戳一次微信 delegate 会让 IME 候选栏跳动。 |
| `app/src/main/java/.../ui/DashboardScreen.kt` | 微信兼容提示卡片：主服务开启但白名单服务关闭时，提示两者必须同时启用（仅 preview 构建；由 `BuildConfig.WHITELIST_SERVICE` 非空控制）。 |
| `app/src/main/java/.../ui/SettingsScreen.kt` | 永久"无障碍"入口行（打开 `Settings.ACTION_ACCESSIBILITY_SETTINGS`），放在备份卡片内，以免挤压 About 卡片的固定高度布局。 |
| `app/src/main/res/values/strings.xml`（+ `values-zh`、`values-zh-rCN`） | 新增字符串：无障碍入口（`settings_accessibility_*`）和 Dashboard 提示（`dashboard_wechat_hint_*`）。 |

---

## 4. srcNull 兜底（一个真正有用的附带修复）

诊断过程中我们发现，部分 App 会发出 `event.source == null` 的 `TYPE_VIEW_TEXT_CHANGED` 事件（自定义输入管线、WebView 等）。此前 SwiftSlate 遇到就直接放弃。**上游已在 #125 采纳该修复**（v1.0.76）；本分支只做了改进：上游版本调用 `root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`，对 WebView 类编辑器会返回 WebView *容器*（不可编辑）。本分支改为遍历整棵树找**同时 editable 且 focused** 的节点：当事件不带 source 时，遍历 `rootInActiveWindow` 找聚焦的可编辑节点。

- **适用：** 原生 `EditText` 但事件不带 source 的 App（兜底有效）。
- **不适用：** WebView 富文本编辑器（如 ColorOS 便签）。其可编辑 HTML 字段是*虚拟*节点，从不通过无障碍子节点层级暴露（`childCount` 为 1 但 `getChild(0)` 返回 null）。这是 WebView 无障碍的固有限制，不是 SwiftSlate 的 bug。

---

## 5. 上游集成建议

补丁被隔离在 `preview` 里，稳定版不受影响。建议上游采用同样模式，也可以用真正的功能开关：

1. **把两个 no-op 服务放在独立的源码集/可选构建类型里**，让稳定版不携带以 Google/点名命名的类（见"合规 / Play 商店考量"）。
2. **保留 `-keep` 规则与精确类名** —— 重命名会静默破坏功能。
3. **在 `AssistantService` 中无条件保留 `srcNull` 兜底**：对原生 EditText App 是纯粹的改进，与微信无关。
4. **让 Dashboard 提示仅在编译进兼容服务时显示**（如通过 `BuildConfig.WHITELIST_SERVICE`）。
5. **在微信新版本上重新验证白名单条目。** 两条名字硬编码在微信 `AccExptServiceKt` 的 clinit 里；微信增删条目时，同步增删对应的 no-op 类。

### 测试清单
1. 安装并启用 SwiftSlate 主服务 + 一个兼容服务。
2. 打开微信，进入聊天，聚焦输入框。
3. 确认 `adb logcat -s SwiftSlateDiag:E` 显示 `rootCls=android.widget.FrameLayout`（非 `null`）和 `TEXT_CHANGED pkg=com.tencent.mm`。
4. 输入 `hello world ?fix` 并确认替换落进输入框。

---

## 6. 合规 / Play 商店考量（请阅读）

- no-op 服务借用 Google 和点名的类名，纯粹是为了满足微信客户端里的字符串比较。这是**本地自用软件** —— 不触碰任何微信服务器，数据也不会以异于平常的方式离开设备。
- 在**公开**的 Play 商店构建中携带名为 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 的类有风险：Play Integrity、微信或 OEM 可能把"冒充 Google 组件的服务"视为可疑。**请务必排除在稳定版之外**（这正是它放在 `preview` 里的原因）。
- 如果要在稳定渠道提供微信支持且不做冒充，替代方案：(a) 提示用户开启 TalkBack 触摸浏览或 Google 官方随选朗读（两者都会让 `isAccessibilityEnabled()` 返回 true），或 (b) 与用户协商使用 preview 构建。方案 (a) 不需要代码，但有额外 UX 成本。

---

## 7. 无 root 适配的适用性（其他手机）

**机制本身不需要 root** —— 伪装服务与普通无障碍 App 一样，只依赖「设置 → 无障碍」开关即可启用。但换手机 / 换账号时能否生效取决于三点，**需要逐台实测**：

1. **微信版本** —— 两条白名单类名硬编码在微信 8.0.74（versionCode 3120）客户端的 `AccExptServiceKt` clinit。同版本或名单未变的版本可用；微信更新若轮换名单（或改变匹配逻辑），需重新逆向确认并同步更新伪装服务。
2. **账号服务端配置** —— 微信按账号下发实验配置（MMKV 缓存）：`clicfg_acc_white_service_list`（服务端可配置白名单）、`accinfo_clear_strike`（清空概率因子）、`accinfo_random_strike`（随机命中因子）。**不同账号可能收到不同配置**，硬编码类名不一定命中；验证时所用账号确实收到了 clear 配置且绕过成功，但其他账号不保证。
3. **机型 / OEM 限制** —— 部分国产 ROM（小米 HyperOS、一加、三星等）隐藏或限制第三方无障碍服务；Android 13+ 侧载应用需先「应用信息 → ⋮ → 允许受限设置」授权，无障碍开关才能打开。这是操作门槛，不是 root 门槛。

### 通用兜底（方案 3，无 root）

若伪装服务在某台手机 / 某账号下无效，开启系统自带的「随选朗读 / Select to Speak」或 TalkBack 触摸浏览即可 —— 真实的 Google 随选朗读服务名本身就在微信白名单里，能让 `isAccessibilityEnabled()` 返回 true，无需安装任何东西。注意国行 ROM 不一定自带该组件（测试机 Realme 的 TalkBack 内带有）。

### 在其他手机上的验证步骤

1. 安装本分支 Release 的 `app-preview.apk`
2. 设置 → 无障碍 → 同时启用「SwiftSlate（主程序）」与「SwiftSlate 微信适配」
3. 微信聊天框输入 `hello world ，fix`，或连 adb 看 `adb logcat -s SwiftSlateDiag:E` 是否出现 `TEXT_CHANGED pkg=com.tencent.mm`、`srcNull=false`、`text=[...]` 可读、`ACTION_SET_TEXT=true`

## 8. 附录：adb 快速启用/停用

```bash
# 启用主服务 + 点名兼容服务（推荐组合）
adb shell settings put secure enabled_accessibility_services \
  "com.musheer360.swiftslate.preview/com.musheer360.swiftslate.service.AssistantService:\
com.musheer360.swiftslate.preview/com.dianming.phoneapp.MyAccessibilityService"

# 验证绑定
adb shell dumpsys accessibility | grep "Bound services"
```

（稳定版 applicationId 请相应替换包名。）
