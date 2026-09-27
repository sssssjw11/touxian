# Attention Guard 1.4 验收

核验日期：2026-09-22。用户已反馈 1.3 真机进入群聊无采集、无弹窗；本文件记录针对该反馈的本地修复证据，不代表新版本真机通过。

## 交付产物

- package：`com.attentionguard.app`；versionName：`1.4`；versionCode：`5`。
- 原生 Kotlin View + Material Components，minSdk 30 / targetSdk 35；只保留 DeepSeek 产品配置。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，59,801,582 字节，开发用 debug 签名。
- SHA-256：`7CBC97775C552336701C465789C1A0A7C4477F7A0671C85B53B4AF9F12081C69`。
- 构建、测试及 lint 成功；112/112 项测试，16 个测试类，0 失败、0 错误。
- Android lint：45 warnings、0 errors。警告包括旧资源、国际化、旧依赖提示、平台兼容与备份配置提示，不等于发布验收通过。
- APK v2 签名验证通过；ZIP 16KB 对齐检查通过。ML Kit arm64/x86_64 ELF LOAD 段对齐为 16384，32 位库为 4096；仍未做 16KB 系统安装测试。
- Premium strict 静态检查：0 errors / 0 warnings；不证明原生视觉、触控或无障碍体验。

## 回归矩阵

| 范围 | 验收行为 | 测试证据 |
| --- | --- | --- |
| 主界面 | 首启空记录、示例隔离、事件完成/恢复、筛选/搜索/活动重建、悬浮事件深链 | `ActivityFlowTest` 8 |
| 新采集页 | 诊断入口、手动模式默认、目标名校验、准备后仍待确认、OCR 授权确认、草稿重建 | `CaptureActivityTest` 4 |
| 采集服务 | 立即挂无障碍窗、普通消息落盘、暂空恢复、关闭自动分析仍存消息、白名单/前台边界、二次确认、退出暂停、主开关、慢速向前翻页、无进展及动作失败 | `ChatCaptureServiceTest` 8 |
| 诊断 | 离开微信保留上次挂窗失败、心跳过期不显示已连接 | `CaptureDiagnosticsTest` 2 |
| 活动窗口 | 键盘、自己浮层、自己活动、其他前台、暂空根与后台微信隔离 | `ForegroundChatWindowTest` 6 |
| 微信适配 | 可见/离屏/隐藏/外包节点、子节点正文、长群名、结构兼容、搜索列表拒绝、空正文 OCR 条件、正文日期不冒充时间标签 | `WeChatAdapterTest` 14，仅合成树 |
| 日期 | 相对日、星期、绝对日期、年界、上午/下午/中午/午夜、无效日期与正文排除 | `ChatDateParserTest` 5 |
| 回溯状态 | 明确启动、精确群名、手动无自动动作、切群暂停、3 次无进展、日期边界、未知日期、缺口计数、上限与恢复不能重置预算 | `HistorySessionTest` 9 |
| 消息存储 | 普通文本、前后邻屏重叠、重复短文本保留、范围/未知分离、日期修正、任务隔离、重开持久化、分页/OCR 标记、跨实例清空、取消写入 | `MessageArchiveTest` 10 |
| OCR 取消 | 场景切换不截屏、取消恢复窗口、迟到回调丢弃、失败上限/手动重试、超时、缺证据不截屏 | `OnDeviceChatOcrTest` 5，不运行真实图像识别 |
| 事件存储 | 合并、状态恢复、依据上限、旧偏好迁移、坏 JSON/结构/文件失败拒绝覆盖 | `EventStoreTest` 10 |
| 本地规则 | 普通聊天筛选、通知行动、课程分类、稳定 ID | `AttentionEngineTest` 4、`EventQueriesTest` 6 |
| API | 仅 DeepSeek；typed JSON、字数上限、HTTP/格式/取消失败、空字段清除猜测、密钥不进 JSON | `ApiProviderTest` 2、`DeepSeekAttentionClientTest` 14，假 HTTP |
| 悬浮交互 | 普通窗权限检查、点击复位、拖动/取消、事件打开并隐藏窗口 | `AttentionOverlayControllerTest` 5 |

## 可重复命令

从 `X:\` 映射构建；不要从中文路径运行 Gradle 测试 worker：

```powershell
$env:GRADLE_USER_HOME = 'D:\gradle-home'
$env:TEMP = 'D:\tmp'
$env:TMP = 'D:\tmp'
$env:GRADLE_OPTS = '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m'
& 'K:\gradle-8.9-dist\gradle-8.9\bin\gradle.bat' :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=1 --console=plain
& 'K:\android-sdk\build-tools\35.0.0\aapt.exe' dump badging app/build/outputs/apk/debug/app-debug.apk
& 'K:\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose app/build/outputs/apk/debug/app-debug.apk
& 'K:\android-sdk\build-tools\35.0.0\zipalign.exe' -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
git diff --check
```

测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
测试 XML：`app/build/test-results/testDebugUnitTest/`。
Lint 报告：`app/build/reports/lint-results-debug.html`。
设计静态报告：`premium-audit.json`。

工具链仍提示 SDK XML 版本及旧路径检查；任务均返回成功。不能把依赖版本提示当作已经验证过升级。

## 必须补做的真机验收

本机 `adb devices -l` 为空。没有在用户账号、真实微信群或真实 Key 上运行过新版。复测操作说明见 [微信采集 1.4](wechat-capture-1.4.md)。

1. 覆盖安装且保留旧记录、设置、密钥；重新绑定无障碍服务后进入微信，读取诊断。
2. 实际微信版本的 ID/结构、标题、发送者、左右气泡归属及普通文本持久化。
3. 空节点、键盘、通知栏、锁屏、弹窗、群切换、无障碍撤销、应用被杀；不能串群或继续无提示翻页。
4. 手动回溯日期标签；自动滚动方向、延迟、重叠量、上限、暂停/结束响应及不支持动作时停止。
5. 悬浮窗实际挂载、触控、遮挡、拖动、旋转、分屏、小屏/200% 字体、TalkBack；Robolectric 不等于截图验收。
6. 同意 OCR 后真实中文识别、受保护窗口拒绝、截图错误、准确率、长时间运行内存与耗电。
7. SQLite 大规模性能、低存储、写入中被杀；清空后观测保持关闭，事件依据仍在。
8. 自备 Key 且明确同意后的 DeepSeek 请求；回溯任务/OCR 内容不发送，普通监测的云端增强仍按设置选择。

## 未声称完成

不支持后台扫描所有群、聊天数据库读取、完整历史备份、无风控承诺、媒体内容提取、自动回复或通知监听。会话身份仍依赖显示名称；跨任务/重启、同名群、歧义重复、缺失日期和未加载页面可造成重复或遗漏。APK 是待真机复测的开发包，不是已完成商店发布验收的发行包。
