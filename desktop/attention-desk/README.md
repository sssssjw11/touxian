# Attention Desk

本地 Web 工作台，把微信群导出的 `messages.json` 变成按 P0–P3 排序的注意力队列。

它也可以作为温州大学学院官网的公开通知阅读器使用：列表扫描保留轻量，正文、分类证据和活动截止日期按需读取。工作台面向桌面端，本地启动、本地保存，默认不需要云服务。

运行要求：Python 3.10+、Node.js 18+ 和 npm 9+。

## 目录

- [5 分钟上手](#5-分钟上手)
- [你会看到什么](#你会看到什么)
- [四条消息来源](#四条消息来源)
- [运行](#运行)
- [学院官网监测](#学院官网监测)
- [API 模式](#api-模式)
- [邮件摘要](#邮件摘要一键发送-ddl)
- [JEV 边界](#jev-边界)
- [用户画像增强](#用户画像增强可选默认关闭)

## 5 分钟上手

### Windows

在仓库根目录打开 PowerShell：

```powershell
cd attention-desk
.\start.ps1 -Install
```

打开脚本输出的地址，默认是 [http://127.0.0.1:5173](http://127.0.0.1:5173)。API 默认运行在 `http://127.0.0.1:8765`。

`-Install` 会执行 `npm install`，创建仓库根目录的 `.venv`，并安装 `server/requirements.txt`。已经安装过依赖时直接运行：

```powershell
.\start.ps1
```

端口被占用时：

```powershell
.\start.ps1 -WebPort 5174 -ApiPort 8865
```

### Linux / macOS

```bash
cd attention-desk
python3 -m venv ../.venv
../.venv/bin/python -m pip install -r server/requirements.txt
npm ci
```

终端一启动 API：

```bash
../.venv/bin/python -m uvicorn server.main:app --host 127.0.0.1 --port 8765
```

终端二启动前端：

```bash
ATTENTION_API_PORT=8765 npm run dev -- --host 127.0.0.1 --port 5173
```

然后打开 `http://127.0.0.1:5173`。

### 本地生产预览

如果不需要 Vite 开发服务器，可以让 FastAPI 直接提供构建后的页面：

```bash
npm ci
npm run build
../.venv/bin/python -m uvicorn server.main:app --host 127.0.0.1 --port 8765
```

打开 `http://127.0.0.1:8765`。生产模式要求 `attention-desk/dist/` 已经存在。

> 这是本地桌面工作台，不包含账号系统、云端数据库或公网反向代理配置。若要在局域网临时访问，请自行评估数据风险，并把 uvicorn 绑定地址改为明确的内网地址。

## 你会看到什么

- **微信群通知**：导入或读取聊天记录，筛出需要行动的事项。
- **公开来源：温州大学 · 学院官网**：扫描 22 个学院公开通知入口，跟踪发布日期、分类、未读和完成状态。
- **设置**：选择数据入口、日期范围、判断提供方、Jev / DeepSeek 参数和提醒频率。
- **邮件摘要**：预览当前待办，确认后调用本机邮件 CLI 发送。

## 四条消息来源

工作台支持四条**互相独立**的导入链路，任选其一，其余不受影响：

| 来源 | 入口 | 说明 |
|---|---|---|
| 导入文件 | 设置 → 导入文件 | 直接上传 `messages.json`（微信导出的对象格式或消息数组） |
| 聊天记录压缩包 | 设置 → 聊天记录包 | 上传微信「导出聊天记录」得到的 zip，本地解析文本与附件 |
| 本机微信 | 设置 → 本机微信 | 只读读取本机微信，见下文 |
| 转发收件箱 | 设置 → 转发收件箱 | 微信里把聊天记录转发给 WorkBuddy，工作台自动收取，见下文 |

### 转发收件箱链路

把微信聊天记录「导出聊天记录」后**直接转发给 WorkBuddy**，即可跳过手动上传：

1. WorkBuddy 收到转发后会把 zip 存到 `~/.workbuddy/app/tmp/chat-history/`；
2. 工作台启动时自建收件箱目录 `data/attention-desk/inbox/`，后台线程每 10 秒
   （`ATTENTION_INBOX_POLL` 环境变量可调，设 0 关闭）扫描该目录，把新的
   聊天记录 zip 拷入收件箱并登记 `manifest.json`（按内容哈希去重，拷贝只读、
   不动 WorkBuddy 的原始文件；只收 mtime 已稳定且通过 zip 校验的文件）；
3. 设置 → 转发收件箱里选中一条，点「开始分拣」，走与压缩包上传完全相同的
   解析与分拣流程；导入成功标记 `imported`，失败标记 `error` 并保留原因。

本地收件箱 API：

- `GET /api/inbox`：收件箱概览（含 watcher 状态与条目列表，访问时顺带即时扫描一次）。
- `POST /api/inbox/import`：传 `filename`（来自列表条目）及与 `/api/analyze-archive`
  相同的可选参数；导入并分拣，成功后更新 manifest 状态。

换机器部署时，这条链路依赖两个目录位置，都可用环境变量覆盖，不需要改代码：

| 环境变量 | 默认值 | 作用 |
|---|---|---|
| `ATTENTION_INBOX_WATCH_DIR` | `~/.workbuddy/app/tmp/chat-history` | WorkBuddy 微信转发落盘目录 |
| `ATTENTION_INBOX_POLL` | `10` | 轮询间隔秒数，设 0 关闭自动收取 |

代码本身跨平台（只用标准库，路径走 `Path.home()`），Windows / macOS / Linux
都可运行。目标机器上没有那个分享目录时不会报错：收件箱如实报告
`watch_dir_exists: false`，自动收取停用，手动上传 zip 的链路照常可用。

「本机微信」链路会优先发现并使用已经安装的 `weflow-cli` / CipherTalk 导出器，
按“列会话 → 导出 JSON → 转换联系人包”的方式读取指定群；如果本机没有导出器，
则回退到 `vendor/wechat-decrypt/config.json` 指向的兼容解密 SQLite。两条路径
都会在分拣前生成同一格式的本地联系人包，入口只读，不会操作或发送微信消息。

### 聊天记录压缩包链路

微信自带的「导出聊天记录」会生成一个 zip，内含 `聊天记录.txt` 与
`聊天记录内的图片、视频和文件/` 附件目录。工作台可直接解析这个 zip：

1. 设置里把数据源切到「聊天记录包」，选择 zip；
2. 点「开始分拣」，后端 `POST /api/analyze-archive` 会解析出消息与附件、
   落成本地消息包（`data/attention-desk/archives/<时间戳>/`），再走与其它
   来源完全相同的分拣流程。

解析兼容性（都有单元测试覆盖）：

- 文本按「`·发送者` / `2026年9月23日 07:52` / 正文」结构切分，正文多行、
  正文内出现 `·` 列表项都不会误判成发送者；
- 日期支持 `2026年9月23日`、`2026-09-23`、`09/23` 等写法，也支持只有时间的续条；
- `[文件] / [图片] / [链接] / [小程序]` 等标记映射到既有消息类型
  （`file` / `image` / `link` / `system`），附件按文件名与标记对应；
- **中文文件名乱码兼容**：Windows 资源管理器压缩出的 zip 常把中文名按
  cp437 存放实为 GBK 的字节，解析器会还原原始字节再解码，避免附件名失配；
- 只保存公开页面式的元数据与本地附件副本，真实聊天内容不入库。

> 前端入口页 `attention-desk/index.html` 必须入库：它曾被根目录 `.gitignore`
> 里的裸 `index.html` 规则误伤（现已收窄为 `/index.html`），否则 `npm run dev`
> 与 `npm run build` 在干净 clone 上都会失败。

## 运行

```powershell
cd attention-desk
.\start.ps1 -Install
```

打开 Vite 输出的本地地址（默认 `http://127.0.0.1:5173`）。后端 API 在 `http://127.0.0.1:8765`。
端口被占用时可用 `-WebPort` 和 `-ApiPort` 指定其他端口。

## 学院官网监测

左侧地球图标打开第二个消息来源。来源清单来自 `data/sites.json` 中的 22 个学院；
检查时复用原始抓取器的串行请求、限速、隐藏日期节点解析和通知栏目发现逻辑。
每个学院优先检查已知学生公告列表，同时检查首页及最多两个公开通知栏目。
部分栏目失败会标成“部分可用”；需要认证的页面不会尝试绕过。

点击“检查全部”进行第一次全量检查，之后可设为关闭或每 6、12、24 小时自动检查
（默认每 12 小时，第一次全量检查完成后才开始计时）。也可只检查选中的学院。
首次检查得到的历史公告默认为已读；后续发现的新链接进入未读。打开公告会自动标已读，
也能单条撤销或批量标记已读。列表支持近 7 天、近 30 天、全部、标题搜索，以及
“全部 / 未读 / 已读完”分区；已读完的公告可找回并撤销已读。

产品目标、功能补全与桌面体验重构顺序见 [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md)。

列表扫描只保存公开页面的标题、发布日期、栏目、链接、分类和已读/完成状态，不下载图片与附件。
用户打开公告详情或主动读取时，后端才按需读取公开正文，提取正文摘要、分类证据和活动/报名截止日期；
正文读取失败、认证墙或跳转到非温大域名时会明确报错，不会猜测截止日。本地状态在被 Git 忽略的
`data/attention-desk/official_monitor.json`。完整图文与附件下载仍由仓库根目录的原始脚本单独执行。
校外无法访问的学院站点会保留失败状态，不被当成“没有公告”。

本地官网监测 API：

- `GET /api/official/overview`：22 个来源的检查状态、未读/已读/已完成计数、分类计数和公告元数据。
- `POST /api/official/scan`：空 JSON 检查全部；`{"source_ids":["ai.wzu.edu.cn"]}` 只检查指定学院。
- `POST /api/official/read`：传 `source_id`、可选 `url` 和布尔 `read`；省略来源与 URL 可批量更新全部。
- `GET /api/official/notice?source_id=ai.wzu.edu.cn&url=https%3A%2F%2F...`：按需读取公开正文摘要、
  分类证据和截止日期；截止日期无可靠证据时返回 `null`，不会把发布日期当成截止日。
- `POST /api/official/action`：传 `source_id`、`url` 和布尔 `completed`，独立保存官网公告的完成状态。
- `POST /api/official/settings`：传 `{"auto_interval_hours":12}`，可选 0、6、12、24。

公告分类固定为 `competition_activity`（比赛 / 活动）、`publicity`（公示）、`other`（其他公告）
和 `unknown`（待确认）。日期区间类表述（例如“9月7日至9月28日送交材料，逾期不再受理”）
取区间结束日作为截止日期，并保留原文证据。自动检查默认每 12 小时；目前不自动发送邮件或外部通知，
邮件摘要仍由用户手动触发。

## API 模式

- **本地 Jev 基线**：复用仓库 `.agents/skills/attention-announcement-triage` 的候选聚类、日期解析和确定性 reducer，不上传数据。
- **TypeSafe Jev**：后端按 `state + questions` 结构调用 `https://api.typesafe.ai/v1/systemone`，问题节点使用 `choice / score / noul`，再回到本地 reducer 排序。
- **DeepSeek**：走 OpenAI 兼容的 `/chat/completions`。后端把 `state + questions` 渲染成中文 prompt（附 json 输出样例），启用 `response_format={"type":"json_object"}`，再把返回的 `choices[0].message.content` 解析回 typed judgments。默认 `deepseek-flash`，Endpoint 可只填 base_url（自动补 `/chat/completions`）。
- **自定义 Jev API**：可填任意兼容 Jev `state + questions` 的地址，API key 只在本次请求中转发，不写入文件。

### DeepSeek 适配说明

DeepSeek 不是 Jev 类模型，不吃 `state + questions` 协议，所以中间加了一层适配器：

- **请求侧**：`build_deepseek_messages()` 把共享 state 与窄问题渲染成「system + user」两段 prompt。为满足 JSON Output 的两条硬要求，prompt 里出现 `json` 字样并给出完整输出样例。
- **响应侧**：`parse_deepseek_content()` 兼容纯 json、` ```json ` 围栏、夹杂解释文字三种形态，并自动拆 `answers/results/judgments/data` 外层包裹。
- **容错**：官方已知 JSON Output 偶发**空 content**，适配器对该情况自动重试（`DEEPSEEK_ATTEMPTS`）；失败则落回本地基线并在 `provider.errors` 里记录，不会中断整批分拣。
- **门控不变**：DeepSeek 的回答与 Jev 一样要过 `apply_jev_answers()`——低置信度（<0.72/0.68）拒绝、非法选项拒绝、`deadline` 与 `urgency` 强制忽略、本地判为非公告的不能被翻案。**最终优先级始终由本地 reducer 决定。**

并发上限按提供方区分：DeepSeek 为 4（保守限流，官方 flash 账号级上限 2500），Jev 保持 5。

日期范围：设置中的“消息日期范围”支持开始日期、结束日期或只填写一端；两端均为闭区间，
筛选发生在候选聚类之前。顶部的“分析基准日”仍单独用于判断 DDL 是否临近、逾期，
不会替代消息日期筛选。首屏提供“最近 1 天 / 最近 7 天 / 本周 / 全部”快捷范围，
并支持按事项类别和“待复核”状态收敛队列；如果范围内没有消息，会给出恢复到完整归档的入口。

截止状态会相对分析基准日动态重算：已超期、今日截止、临近 1-3 天、宽裕 4 天以上、
未排期和日期参考。截止日一过，事项会在下一次分析时自动标记为 `archived`，
退出进行中队列，进入“超期归档”。归档仍可搜索并查看原始判断与证据，但不再
按 P0–P3 当作待办呈现；超期记录按最近截止日期排列。

进行中事项可直接点击行尾勾选“完成并归档”，无需打开详情。完成记录保存在本机
`data/attention-desk/completions.json`，按群聊与证据消息生成稳定标识；刷新、调整
消息日期范围或重新分拣后仍然生效。归档可分别查看“已完成”和“已超期”，已完成事项
可一键撤销；如果它的截止日也已过去，撤销后仍保留在超期归档。

本机微信接口：

- `GET /api/wechat/status`：检查导出器、微信进程、解密目录和 SQLite 分片是否可读，并报告数据新鲜度。
- `GET /api/wechat/groups?q=群名`：搜索导出器会话或本机解密库中的群聊。
- `POST /api/wechat/analyze`：按 `username` 或 `display_name` 读取指定群并分拣；可选 `from_date` / `to_date`（YYYY-MM-DD，首尾包含）。
- `POST /api/wechat/export`：只导出消息包和脱敏后的文件清单；传 `resolve_files: true` 时会额外尝试定位已经下载到本机缓存的文件（文件条目只返回 `local_available`，不重复暴露每个文件的绝对缓存路径）。完整消息仍只写入本地联系人包。

上传文件接口 `POST /api/analyze`、压缩包接口 `POST /api/analyze-archive`
和示例接口 `GET /api/sample` 也接受相同的 `from_date` / `to_date` 参数。
返回结果的 `source` 会同时记录 `archive_start/archive_end`、`archive_message_count`、
`selected_start/selected_end`、`message_count` 和 `message_date_filter`，便于确认筛选是否生效。

聊天记录压缩包接口：

- `POST /api/analyze-archive`：`multipart/form-data` 上传 zip（字段 `file`），
  可选 `provider` / `api_key` / `endpoint` / `model` / `as_of` / `from_date` / `to_date` /
  `max_candidates`，参数含义与 `/api/analyze` 一致。
  返回结果的 `source.source_kind` 为 `archive-upload`、`source_strategy` 为
  `chat-archive-zip`，并在 `source_export` 里给出消息包路径、附件数量与脱敏文件清单。
  非 zip、空文件、缺少聊天记录文本都会返回 400。

## 邮件摘要（一键发送 DDL）

工作台右上角的「邮件摘要」把当前分拣结果渲染成一封待办邮件，通过
[`agently-cli`](https://agent.qq.com) 发送。

**邮件不包含完整聊天记录。** 正文只保留 DDL、摘要和每项的一条证据摘录，固定为三段：

1. 概览：来源群、归档区间、判断基准日；进行中事项数量（按优先级拆分）与其中已逾期数量。
2. 已逾期单列：`已逾期` 的事项单独成段置顶，提醒优先确认。
3. 按优先级排序的待办：P0 → P3 分组，每条含标题、类别、截止时间（含「还有 N 天 / 已逾期 N 天」）
   以及一条证据原文摘要。

没有进行中事项时主题会如实写「暂无进行中事项」，不会伪造成有代办。

### 两种正文格式

面板顶部可切换格式，选择会记在本机浏览器里：

- **卡片式富文本（HTML，默认）**：深色头部 + 概览统计条 + 优先级彩色标签 + 每项独立卡片 +
  逾期红色警示块，倒计时按紧急度着色；证据摘要以左侧竖线引用样式呈现。
  用 `table` 布局 + 全部内联样式，兼容 Outlook / 各邮箱客户端；聊天原文经 HTML 转义，
  即使含 `<script>` 也只会作为文本显示。
- **纯文本**：等宽排版、分隔线分区，兼容性最好，适合不渲染 HTML 的客户端或纯文本归档。

两种格式共用同一套口径计算（`_digest_parts`），数字不会出现差异；主题行也完全一致。

**收件人可自定义**：面板输入框支持多个地址（逗号、分号或空格分隔，最多 20 个），
只保存在本机浏览器 `localStorage`，不上传。修改收件人或切换格式后需要重新生成预览
（确认令牌与收件人和正文内容绑定）。

**发送走两阶段确认**，与 `agently-cli` 的写操作协议一致：

- `GET /api/mail/status`：报告 CLI 是否安装并已授权，返回发件邮箱与每日配额；前端据此决定按钮可用性。
- `POST /api/mail/prepare`：入参 `{result, recipients, as_of, body_format}`（`body_format` 取
  `html` / `text`，默认 `html`），渲染摘要并调用 CLI 拿到 `ctk_xxx` 确认令牌，**此阶段不发送**；
  返回 `{confirmation_token, subject, body, body_format, recipients, sender, summary}`。
- `POST /api/mail/send`：入参 `{confirmation_token, recipients, subject, body, body_format}`，
  带令牌真正投递。

面板会先把主题与正文完整展示给用户（HTML 用沙箱 iframe 渲染，不执行其中的脚本），
点「确认发送」才调用第二阶段。`agently-cli` 要求的 `--body-file` 只接受相对路径，
后缀决定渲染方式（`.html` → 富文本，`.txt` → 纯文本）；后端把正文写到
`data/attention-desk/mail/`（被 Git 忽略）并以该目录为工作目录调用，避免命令行转义与路径问题。

邮件链路未安装或未授权时，按钮置灰并提示原因，不做静默降级。回复、转发、收件箱等其他
邮件能力不在本面板内，需要时直接用 `agently-cli`。

导出器未安装时，Windows 可用仓库内的安装检查脚本准备：

```powershell
python scripts/setup_chat_exporter.py --provider auto --install
```

安装器会先尝试 `weflow-cli`，再回退 CipherTalk；旧版兼容解密器只作为已有本地数据的回退路径。

默认加载仓库内的 `data/demo_messages.json` 合成演示记录。真实聊天记录不会入库；
可上传自己的 `messages.json`，或用 `ATTENTION_SAMPLE_PATH` 环境变量指向本机归档文件。
运行时完成记录和微信导出结果也只保存在被 Git 忽略的本地目录。

## JEV 边界

Jev 只负责窄问题的结构化判断，不能直接改写最终优先级。当前图会先把候选区分为
公告、资料/技术分享、普通对话、噪声和待确认事项；技术配置中的版本号不会当作 DDL。
DDL 和紧迫性由本地解析器计算，远程 Jev 的低置信度、越界枚举以及 `urgency` 覆盖都会被拒绝，
P0–P3 由确定性 reducer 计算，并保留证据消息 ID和人工复核状态。

短但有明确日期或行动词的消息不会因为长度被降为噪声；“供大家参考”这类技术分享也不会仅凭“大家”
被提升成全员公告。历史归档如果早于分析基准日，会明确标记为过期归档，并避免把旧事项伪装成当前待办。

## 用户画像增强（可选，默认关闭）

侧栏的「我的画像」可以填写**学院 / 专业 / 年级 / 关注方向 / 自由描述**，用来回答一个
额外的问题：**这条通知跟我有没有关系**。

### 它做什么

开启后，判断链路会多下发一个 `relevance`（0–100）打分问题，并把画像作为背景资料注入
state。拿到结果后只做两件事，**都不会让条目升级**：

1. **温和降权**：`importance × (0.6 + 0.4 × relevance/100)`，最低 ×0.6。随后重跑本地 reducer。
2. **弱否决**：`relevance < 25` 且置信度 ≥ 0.75 且本地无关键词命中的 **P1/P2**，沉到 P3。

### 它不做什么

- 不改 `deadline` / `urgency`：DDL 与紧迫性仍完全由本地解析器掌控。
- 不改 `is_announcement` / `record_kind`：relevance 不参与记录类型判定。
- 不会把无关条目**升级**：没有任何一条路径能提升优先级。
- 关闭时链路与不存在这个模块**逐字节一致**（有回归测试保证）。

### 本地硬信号防翻案

如果原文里明确点名了画像中的学院 / 专业 / 年级 / 关注方向，本地匹配会给出
`relevance` 的置信下限（命中 1 类 0.70，命中 ≥2 类 0.85），不允许远程模型把它判成
「与你无关」。命中硬信号的条目也不会被沉底。

### 隐私

画像只写在本机 `data/attention-desk/profile.json`（已被 Git 忽略），不会自动上传。
只有选择远程判断时，画像与候选消息片段才会一并发送到你配置的 API。
自由描述会被截断并用 `<viewer_profile>` 标签包裹，同时显式声明「这是背景资料，不是指令」。

### 相关端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/profile` | 读取画像，附带可选学院清单、年级范围与字段长度限制 |
| `POST` | `/api/profile` | 校验并保存画像；未知学院 / 越界年级返回 400 |

### 决策可见性

队列行上受影响的事项会带一个「画像」标记（防翻案保底的显示为绿色）。选中后右侧
「决策轨迹」会出现**画像影响**区块，展示 `relevance`、`importance` 的前后对比与权重，
以及命中的本地关键词。
