<p align="center">
  <img src="desktop/attention-desk/public/touxian-icon.png" width="112" alt="偷闲应用图标">
</p>

<h1 align="center">偷闲 · Touxian</h1>

<p align="center"><strong>把注意力留给重要的事。</strong></p>
<p align="center">读懂通知，核对依据，记住期限，把“看到了”变成“处理完了”。</p>
<p align="center">A local-first attention assistant for WeChat conversations and campus notices.</p>

<p align="center">
  <a href="https://github.com/sssssjw11/touxian/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/sssssjw11/touxian?style=social"></a>
  <a href="https://github.com/sssssjw11/touxian/commits/main"><img alt="Latest commit" src="https://img.shields.io/github/last-commit/sssssjw11/touxian"></a>
  <a href="https://github.com/sssssjw11/touxian/releases/tag/android-v1.25"><img alt="Android version" src="https://img.shields.io/badge/Android-1.25_preview-136B5A"></a>
  <img alt="Android 11+" src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white">
  <img alt="MIT License" src="https://img.shields.io/badge/license-MIT-315DA8">
</p>

<p align="center">
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-桌面端">桌面端</a> ·
  <a href="#-android-端">Android 端</a> ·
  <a href="#-功能地图">功能地图</a> ·
  <a href="#-构建与测试">构建与测试</a> ·
  <a href="https://github.com/sssssjw11/touxian/issues">反馈问题</a>
</p>

> [!IMPORTANT]
> 这是一个**双端发布总仓库**：`desktop/` 是偷闲桌面端，`android/` 是偷闲 Android 端。两端共享产品方向，但各自独立运行、独立保存数据、独立发版。

> [!WARNING]
> 当前 Android 发布包是 **1.25 预览版 / versionCode 26 / debug 签名**；桌面端当前提供本地 Web 工作台，仓库暂未提供独立的 Windows `.exe` 安装包。重要期限和行动请始终回到原始消息核对。

Android 1.25 同步来源 `attention-guard@8145646`，带来消息正文关键词、单会话记录、关系深度分析、诊断导出与滑动标题恢复。参见 [本次 Release](releases/android-v1.25.md) 和 [完整更新与 Debug 记录](android/docs/releases/v1.25.md)。桌面端本次未改动，两端数据不会自动互通。

---

## 🌟 偷闲是什么？

群聊和学院官网里，真正需要行动的信息往往不是一条消息就说完：

- 一条通知给出报名入口；
- 下一条补充截止时间；
- 后面又有人提醒材料格式；
- 最后还可能临时改期。

偷闲把这些分散的信息整理成一条可追踪的行动线：

```text
发现值得关注的消息
        ↓
识别事件、类别与期限
        ↓
保留原始依据和来源
        ↓
完成、归档、恢复或写入日历
```

它不是聊天备份工具，也不是替你做决定的黑盒模型。它更像一个安静的注意力工作台：帮你把信息摆整齐，把重要的事情留在眼前。

## 🎯 适合谁？

- 经常接收班级群、课程群、学院群通知的学生。
- 需要跟踪报名、比赛、会议、作业和材料提交截止时间的人。
- 想把多个学院官网公告集中查看的人。
- 希望在手机微信里快速确认事件，又想在电脑上集中处理待办的人。
- 重视本地数据、原文证据和可解释判断的使用者。

## 🗺️ 功能地图

| 能力 | 桌面端 | Android 端 |
| --- | :---: | :---: |
| `messages.json` 导入 | ✅ | — |
| 微信聊天记录 zip 导入 | ✅ | — |
| 微信转发收件箱 | ✅ | — |
| 本机微信只读导出 | ✅ | 读取当前可见会话 |
| 温州大学学院官网监测 | ✅ | — |
| 公告分类与发布日期 | ✅ | — |
| 活动 / 报名截止日期提取 | ✅ | — |
| 超期归档与完成状态 | ✅ | ✅ |
| P0–P3 优先级 | ✅ | ✅ |
| 微信悬浮窗 | — | ✅ |
| 意图与语境分析 | Jev / DeepSeek 可选 | 本地规则 |
| 自由文本分析 | — | ✅ |
| 自定义消息正文关键词 | — | 类型 / 重要性 / 启停 |
| 单会话记录与深度分析 | — | 消息日期 / 统计 / 原话证据 |
| 诊断报告导出 | — | 结构化 JSON |
| 确认后写入日历 | 邮件摘要 | ✅ |
| 学院来源拖动排序 / 置顶 | ✅ | — |

## ✨ 功能特性

### 🖥️ 桌面端：Attention Desk

把微信导出记录和公开官网公告放进同一张可操作的桌面工作台：

- 📥 **四种导入方式**：`messages.json`、微信聊天记录 zip、本机微信、转发收件箱。
- 🧭 **日期范围筛选**：开始日期和结束日期均可单独填写，闭区间处理。
- ⏰ **截止状态动态判断**：超期、今日截止、临近、宽裕、未排期。
- 🧩 **P0–P3 行动队列**：重要性和紧迫性分开计算，保留待复核路径。
- ✅ **完成并归档**：处理完一键移出进行中，之后仍可搜索和撤销。
- 🏫 **温州大学学院监测**：串行检查 22 个公开学院来源，保留部分可用和需认证状态。
- 🗂️ **公告分类**：比赛 / 活动通知、公示、其他公告、待确认。
- 📅 **双日期展示**：列表显示公告发布日期，详情按需识别活动 / 报名截止日期。
- 👁️ **已读与完成分离**：已读、已读完、已完成、超期归档各自有明确语义。
- 📌 **来源整理**：学院列表支持拖动排序，右键可以置顶或取消置顶。
- ✉️ **DDL 邮件摘要**：支持 HTML / 纯文本预览，发送前二次确认。
- 🔌 **API 可选**：本地 Jev、TypeSafe Jev、DeepSeek、自定义 Jev API。

### 📱 Android 端：偷闲

在微信当前可见会话中，快速识别值得留意的事件：

- 👀 **当前屏幕观测**：只读取用户主动打开的、当前可见的微信内容。
- 🪟 **三级悬浮窗**：完整面板、紧凑工具条、小球，随时收起。
- 🧠 **事件监测**：识别事件类型、行动要求、重要性、期限和原始依据。
- 💬 **意图分析**：结合当前可见语境展示可能意图、情绪线索和置信度。
- 📝 **自由文本分析**：粘贴一段对话即可分析，不写入事件簿。
- 📓 **事件观测簿**：搜索、完成、归档、恢复，保留事件更新记录。
- 🗓️ **确认后写日历**：预览标题、时间、提醒和目标日历，确认后才写入。
- 🔎 **有界历史回溯**：用户主动启动，受会话、翻页、时间和次数限制。
- 🛠️ **运行诊断**：查看可读消息数、采集连接、悬浮窗和保活状态。
- 🌐 **可选联网增强**：DeepSeek 事件增强默认关闭，本地规则可独立工作。
- **正文关键词**：手动添加识别关键词，选择类型和 P0–P3 重要性；与会话范围分开，命中依据写入详情。
- **单会话记录**：意图模式显式开始后才记录当前可见消息，切换页面或锁屏暂停，不后台扫描聊天历史。
- **关系深度分析**：APP 按消息日期选择范围，分开全量统计、关键语境与原话证据；可单独确认 DeepSeek 深化。
- **诊断导出**：区分标题读取、范围、模式、判定、写入与筛选，不导出聊天正文、会话名或密钥。

## 🚀 快速开始

### 方式一：直接使用 Android 预览版

系统要求：Android 11 / API 30 及以上。

| 下载项 | 地址 |
| --- | --- |
| Android APK · 1.25 预览版 | [touxian-1.25-debug.apk](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-debug.apk) |
| SHA-256 校验文件 | [touxian-1.25-debug.apk.sha256](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-debug.apk.sha256) |
| 发布页 | [Android v1.25](https://github.com/sssssjw11/touxian/releases/tag/android-v1.25) |

ADB 安装：

```bash
adb install -r touxian-1.25-debug.apk
```

> 当前 APK 使用 debug 签名。遇到“签名不一致”时不要直接卸载旧应用，先确认本地事件、消息、密钥和设置的保留方案。

1.22 升级到 1.25 会将消息库从版本 2 迁移到 3，保留旧消息并补充录制及时间字段；没有旧版降级迁移，不建议运行新版本后直接装回旧版。详情见 [升级提醒](releases/android-v1.25.md#upgrade)。

### 方式二：启动桌面端

Windows PowerShell：

```powershell
git clone https://github.com/sssssjw11/touxian.git
cd touxian\desktop\attention-desk
.\start.ps1 -Install
```

打开 `http://127.0.0.1:5173`。

后端 API 默认运行在 `http://127.0.0.1:8765`。首次启动可以直接使用仓库内的合成演示数据，不会自动读取你的微信内容。

### 方式三：从源码构建 Android

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Windows PowerShell：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

APK 输出：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 🖥️ 桌面端使用教程

桌面端源码位于 [`desktop/`](desktop/)，工作台位于 [`desktop/attention-desk/`](desktop/attention-desk/)。

### 1. 选择消息来源

设置面板支持四条互相独立的入口：

| 入口 | 你需要准备什么 | 适用场景 |
| --- | --- | --- |
| 📄 导入文件 | `messages.json` | 已经有标准消息包 |
| 🗜️ 聊天记录包 | 微信导出的 `.zip` | 最简单、最推荐的手动导入 |
| 🧰 本机微信 | 已安装导出器或兼容 SQLite | 直接搜索和读取指定群 |
| 📬 转发收件箱 | 转发给 WorkBuddy 的 zip | 不想反复手动选择文件 |

### 2. 分拣微信群通知

1. 打开设置并选择数据来源。
2. 选择群聊、上传 JSON 或选择聊天记录 zip。
3. 设置消息日期范围。
4. 设置分析基准日。
5. 选择判断提供方：
   - 🧮 **本地 Jev 基线**：默认推荐，不上传聊天内容。
   - ✨ **TypeSafe Jev**：填写自己的 API Key。
   - 🧠 **DeepSeek**：填写 API Key，可使用官方兼容接口。
   - 🧩 **自定义 Jev API**：填写兼容 `state + questions` 的 Endpoint。
6. 点击“开始分拣”。
7. 在进行中查看 P0–P3、截止状态、证据和待复核项。
8. 处理完成后勾选“完成并归档”。

最终截止日和优先级由本地逻辑决定。远程模型只回答窄问题，不能直接把一条普通聊天改成高优先级待办。

### 3. 监测学院官网

1. 点击左侧地球图标进入“公开来源：温州大学 · 学院官网”。
2. 首次使用点击“检查全部”，或选择指定学院。
3. 按学院、日期、关键词、分类和“全部 / 未读 / 已读完”筛选。
4. 列表显示公告发布日期。
5. 打开详情后按需读取公开正文。
6. 有可靠正文证据时显示活动 / 报名截止日期。
7. 可单独标记已读、完成或撤销完成。

认证墙、非温大域名跳转、正文失败和空结果都会显示原因，不会被静默当成“没有公告”。

### 4. 转发收件箱

将微信聊天记录导出为 zip 后转发给 WorkBuddy。工作台会扫描：

```text
~/.workbuddy/app/tmp/chat-history/
```

换机器或目录不同，可以覆盖：

```powershell
$env:ATTENTION_INBOX_WATCH_DIR = "D:\path\to\chat-history"
$env:ATTENTION_INBOX_POLL = "10"
```

工作台会等待文件稳定、校验 zip、按内容哈希去重，再复制到：

```text
data/attention-desk/inbox/
```

### 5. 邮件摘要

右上角“邮件摘要”会生成待办预览：

- HTML 卡片式正文。
- 纯文本正文。
- 逾期事项单独置顶。
- 每项保留截止时间、摘要和一条证据摘录。
- 收件人保存在浏览器本地。
- 第二次确认后才真正发送。

## 📱 Android 使用教程

Android 端完整说明在 [`android/README.md`](android/README.md)，这里给出最短路径：

1. 安装 APK。
2. 在“我的”里打开示例模式，先熟悉界面。
3. 为偷闲开启无障碍服务和必要的悬浮窗能力。
4. 在“规则与外观”设置会话识别词条，或在微信内手动标记当前会话。
5. 打开自己有权查看的微信文字聊天。
6. 选择“事件监测”或“意图分析”。
7. 在观测簿核对原文依据，再完成、归档或加入日历。

### 三种模式

| 模式 | 读取什么 | 会保存什么 |
| --- | --- | --- |
| 🎯 事件监测 | 当前可见微信会话 | 消息和符合条件的事件 |
| 💬 意图分析 | 当前屏幕可读语境 | 默认只展示；显式开始才记录消息，手动保存事项才写事件 |
| 📝 自由文本分析 | 你主动输入或粘贴的文字 | 展示结果，不写入事件簿 |

> 识别词条为空表示不按会话名称限制范围，不等于自动遍历全部会话。历史回溯必须由用户主动启动，并受速度、屏数和时间上限约束。

消息关键词入口：微信「事件监测 → 更多 → 消息关键词」或 APP「规则与外观 → 消息正文关键词」。会话词条限制看哪个聊天，正文规则决定整理哪些消息；删除规则不会删除既有事件。

会话记录入口：微信意图模式的保存图标；APP「会话分析」查看片段和日期范围报告。意图模式更多菜单的「保存当前事项」是另一项独立命令。遇到识别后没有入库，在「采集与回溯 → 诊断 → 导出诊断报告」收集阶段信息。

## 🧠 判断与架构

### 桌面端 JEV 边界

桌面端采用 JEV 风格的类型化判断：

```text
共享消息状态
      ↓
候选聚类与日期解析
      ↓
Boolean / Choice / Score 窄问题
      ↓
本地确定性 reducer
      ↓
P0–P3、截止状态、证据和归档
```

- 日期和截止状态由本地解析器决定。
- 低置信度结果进入待复核或回退路径。
- 非法枚举和远程 `urgency` 覆盖会被拒绝。
- 最终优先级不会由一段自由文本直接改写。

### Android 端采集边界

```text
当前可见微信节点
      ↓
会话范围校验
      ↓
本地事件规则 / 语境分析
      ↓
观测簿 / 日历预览
      ↓
用户确认后写入
```

Android 端不读取微信数据库、不 hook 微信、不自动发送消息。意图模式不自动写事件簿，显式会话记录和手动保存事项分别受用户控制。

## 🧱 技术栈

### Desktop

- React 18 + Vite。
- FastAPI + Uvicorn。
- Python `requests` / `httpx` / BeautifulSoup / lxml。
- 本地 JEV 风格公告分拣技能。
- Lucide 风格线性图标和桌面优先布局。

### Android

- Kotlin。
- Android 原生 View + Material Components。
- ML Kit 中文 OCR。
- JUnit + Robolectric。
- Gradle Wrapper。

## 🧪 构建与测试

### 桌面端

```bash
cd desktop
python -m pytest attention-desk/server -q
cd attention-desk
npm ci
npm run build
```

当前桌面端回归结果：**252 项测试通过，前端生产构建通过**。

### Android 端

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Android 原仓库保留了版本化测试记录和真机验收记录：

- [1.25 更新与 Debug](android/docs/releases/v1.25.md)
- [1.25 正文关键词](android/docs/iteration-1.25.md)
- [1.24 标题恢复排查](android/docs/iteration-1.24.md)
- [1.23 记录与关系分析](android/docs/iteration-1.23.md)
- [1.22 迭代说明](android/docs/iteration-1.22.md)
- [1.22 发布说明](android/docs/releases/v1.22.md)
- [真机验收](android/docs/device-acceptance-1.22.md)

如果当前机器没有 Android SDK，Gradle 会在配置阶段停止；这属于本机构建环境问题，不代表源码测试失败。

1.25 来源发布前有 296 项针对性回归通过；总仓库同步验证见 [发布记录](RELEASE.md)。空标题真实 OCR 恢复、长时间录制、同名会话及更多机型仍待验收，不能将代码测试当作完整真机覆盖。

## 🔐 权限、隐私与安全

### Desktop

- 本地模式不上传聊天内容。
- 远程提供方只在用户主动选择后接收候选消息片段和可选画像。
- API Key 只在当前请求中使用，不写入仓库。
- 学院官网只读取公开温州大学域名，不绕过认证或验证码。
- 运行时聊天数据、报告、下载附件和状态文件被 Git 忽略。

### Android

- 无障碍服务只用于读取前台微信可见节点和显示应用悬浮窗。
- 不后台遍历全部微信群，不自动回复，不执行转账、红包或发送操作。
- OCR 截图只在内存中处理。
- DeepSeek 联网增强默认关闭。
- APP 会话深化另需确认，发送所选范围统计与最多 90 条关键消息；微信实时意图分析仍在本地运行。
- 日历事件只有在用户点击确认后才写入。

请只处理自己有权查看的内容，并遵守微信、Android 和目标网站的适用规则。

## 🗂️ 仓库结构

```text
touxian/
├── README.md
├── RELEASE.md
├── desktop/
│   ├── attention-desk/       # React + FastAPI 工作台
│   ├── .agents/              # 公告分拣技能与 JEV 规则
│   ├── data/                 # 版本化站点目录和合成演示数据
│   ├── scrape.py             # 温大官网通知列表抓取
│   ├── download.py           # 正文、图片和附件下载
│   └── ...
└── android/
    ├── app/                  # Android 应用模块
    ├── docs/                 # 发布、设计、真机记录
    ├── scripts/              # APK 检查和设备规则验证
    └── ...
```

运行时数据和构建产物不进入发布仓库：

- Desktop：`desktop/data/attention-desk/`、`desktop/data/contacts/`、`desktop/out/`、`desktop/download/`、`desktop/attention-desk/node_modules/`
- Android：`android/.gradle/`、`android/local.properties`、`android/app/build/`、签名文件和密钥配置

## 🛣️ 路线图

- [x] 桌面端微信群导入、日期范围、Jev / DeepSeek 可选判断。
- [x] 学院官网监测、公告分类、发布日期和按需截止日期。
- [x] 已读完、完成归档、超期归档和学院来源置顶。
- [x] Android 微信可见会话事件监测和三级悬浮窗。
- [x] Android 观测簿、组合检索、日历确认写入和本地 OCR。
- [x] Android 正文关键词、单会话记录、关系深度分析与诊断导出。
- [ ] 总仓库自动同步两个来源仓库。
- [ ] 桌面端打包独立 Windows 安装包。
- [ ] Android 扩大机型、微信版本和后台保活验收范围。
- [ ] 设计跨端导出 / 导入协议，保持本地优先。
- [ ] 建立去标识样本集，持续校准规则和低置信度路径。

路线图是方向清单，不是时间承诺；平台边界和隐私约束优先级更高。

## 🤝 贡献

欢迎提交 Issue、可复现样例、文档改进和小范围补丁：

1. 桌面端来源解析、日期识别、状态逻辑和布局问题。
2. Android 端机型兼容、无障碍读取、悬浮窗和交互问题。
3. 去标识的回归样例与测试。
4. 构建、发布和 README 改进。

提交前请确认：

- 不提交完整聊天导出、数据库、截图、API Key、令牌、证书或签名文件。
- 不绕过认证、验证码、系统权限或第三方平台访问控制。
- 修改 JEV / 本地规则时附带测试和证据说明。
- 设备日志和样例已经移除姓名、群名、号码、邮箱和本地路径。

## 📦 版本与来源

| 平台 | 来源仓库 | 当前同步版本 | 主要产物 |
| --- | --- | --- | --- |
| Desktop | [wzu-notice-scraper](https://github.com/sssssjw11/wzu-notice-scraper) | `126b45e` | 本地 Web / FastAPI |
| Android | [attention-guard](https://github.com/sssssjw11/attention-guard) | `8145646` / `1.25` | Debug APK |

总仓库是发布聚合仓库，不自动跟踪来源仓库。更新流程：

```text
来源仓库完成开发与测试
          ↓
同步到 desktop/ 或 android/
          ↓
更新 README / RELEASE.md
          ↓
运行对应平台回归
          ↓
推送总仓库并打发布标签
```

## 📚 文档索引

### Desktop

- [桌面端总说明](desktop/README.md)
- [Attention Desk 使用手册](desktop/attention-desk/README.md)
- [产品需求与体验计划](desktop/attention-desk/PRODUCT_REQUIREMENTS.md)
- [版本改进记录](desktop/attention-desk/VERSION_NOTES.md)
- [合作与贡献](desktop/CREDITS.md)

### Android

- [Android 端完整说明](android/README.md)
- [Android 1.25 Release](releases/android-v1.25.md)
- [1.25 更新与 Debug](android/docs/releases/v1.25.md)
- [设计系统](android/DESIGN.md)
- [交互约定](android/UX-CONTRACT.md)
- [1.22 发布说明](android/docs/releases/v1.22.md)
- [真机验收记录](android/docs/device-acceptance-1.22.md)

## ⚖️ 许可证与致谢

本总仓库和两个平台代码均采用 MIT License；平台目录保留各自的版权和许可文件：

- [`desktop/LICENSE`](desktop/LICENSE)
- [`android/LICENSE`](android/LICENSE)
- [`LICENSE`](LICENSE)

感谢原始温州大学通知抓取器和 Android 偷闲项目的贡献者。合作与模块范围见 [`desktop/CREDITS.md`](desktop/CREDITS.md)。

偷闲是独立项目，不隶属于微信、腾讯、DeepSeek、温州大学或任何参考项目。

<p align="center">
  <strong>如果偷闲帮你少漏掉一次截止时间，欢迎给仓库点一个 Star。</strong>
</p>
