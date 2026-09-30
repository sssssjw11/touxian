# 偷闲 Android v1.25

> 本版把「识别到」到「APP 中看得见」的流程补齐：你可以手动配置消息正文关键词、保存单会话片段并在 APP 分析关系语境，也能导出诊断定位未入库的阶段。修复重点是滑动后群名绑定失效、事件异步保存与列表刷新，以及否定/引用导致的误判。本版仅更新 Android，桌面端和跨端数据互通不在此次范围。

发布日期：2026-10-01 · Android 11+ · 1.25 / versionCode 26 · **debug 签名预览版**

[下载与安装](#download) · [升级提醒](#upgrade) · [使用路径](#usage) · [验证与边界](#verification) · [English](#english)

---

## 重点变化

- **自己决定整理哪些消息。** 添加正文关键词，并设置类型、P0–P3 重要性和启用状态；命中依据保存到事件详情，不把群名误当成正文关键词。
- **保存一个聊天的指定片段。** 在意图模式显式开始记录，自行滚动收集可见内容；回到 APP 按消息日期筛选、查看原话与关系报告。
- **看清保存停在哪一步。** 诊断区分标题、会话范围、当前模式、本地判定、入库与筛选；JSON 报告不含聊天正文、会话名、截图或 API Key。
- **改善翻页后的持续监测。** 完整标题建立绑定，同一失败页的重复轮询不再快速释放绑定；开启本机 OCR 后可对缺失标题限频核对。

<a id="download"></a>
## 下载与安装

| 文件 | 用途 |
| --- | --- |
| [touxian-1.25-debug.apk](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-debug.apk) | Android 11+ 安装包，约 58.1 MiB |
| [touxian-1.25-debug.apk.sha256](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-debug.apk.sha256) | 校验安装包完整性 |
| [touxian-1.25-release-notes.md](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-release-notes.md) | 本页发布说明 |
| [touxian-1.25-update-and-debug.md](https://github.com/sssssjw11/touxian/releases/download/android-v1.25/touxian-1.25-update-and-debug.md) | 详细变更、根因和 Debug 记录 |

```bash
adb install -r touxian-1.25-debug.apk
```

SHA-256：

```text
e4c67eb4c43bfcdd64c2279c92b8379082c5023213ecd40053cee47790314c39  touxian-1.25-debug.apk
```

包名仍为 `com.attentionguard.app`，发布附件与来源仓库 1.25 为同一个已核对 APK，不是另行签名的版本。此 Release 不提供 Windows 安装包。

---

## 新功能

### 消息正文关键词

每条规则支持关键词、事件类型、重要性与启停，最多 100 条；新增、编辑、删除均在本机即时保存。只匹配对方的原生文字正文，保留全半角、大小写规范化及英文边界，不匹配自己发言、群名或发送者。

会话名称词条负责观测范围，正文规则负责消息整理，两者独立。规则命中默认可进入持续观测，明确行动才进入待办；取消、完成或过期不升级紧急提醒。停用或删除规则不会删除旧事件，云端也不改写规则指定的类型和重要性。

### 单会话记录与深度分析

意图模式默认不保存聊天。显式开始后记录读到的可见页，离开微信、切页面、锁屏或切模式暂停，继续需要确认。APP 的日期选择使用消息日期，未知日期单列，不把采集时间当成发送时间。

本地报告按全量统计、关键语境与原话证据组织，分开双方、群成员、情绪和关系态度。可选 DeepSeek 深化需单独确认，只发送所选统计和最多 90 条关键消息；失败保留本地报告，引用必须来自输入原话。

### 诊断导出与显式保存事项

意图模式更多菜单增加「保存当前事项」，不偷偷切换为事件模式。诊断通过系统文件选择器导出 JSON，无需额外文件存储权限，包含版本、权限事实、心跳、数量和最近 80 条阶段元数据。

---

## 修复

- **会话词条存在但滑动后不识别。** 微信可能不开放顶部群名节点；修复绑定初始化、重复失败计数和旧 OCR 回调，增加当前页顶部标题核对。不从词条表或消息昵称猜群名，也不只凭窗口 ID 复用标题。
- **保存后立即打开 APP，事项没有出现。** 保持已确认写入任务有效，事件稍晚落盘时自动刷新已打开列表，悬浮窗显示实际合并后的完成/归档状态。
- **否定、引用或已取消事项成为新任务。** 分离行动否定与实际信息，避免无关引用或已取消的高优先级关键词覆盖同条消息的有效任务；过期 P3 不升级为 P2。
- **情绪归属和分数互相干扰。** 排除自己和其他群成员的无关线索，处理否定、问句、引用及重复权重，不把评分包装成正确率或被爱概率。
- **关键词编辑首击保存的竞态。** 弹窗显示后立即绑定保存监听，新增不等待框架回调的回归测试；确认删除不会连带移除其他规则。

根因、最短诊断步骤和对应记录见 [完整更新与 Debug 文档](https://github.com/sssssjw11/touxian/blob/main/android/docs/releases/v1.25.md)，不在发布首页附私人聊天或原始设备转储。

---

<a id="upgrade"></a>
## 升级提醒

> [!WARNING]
> 1.22 到 1.25 的本机消息 SQLite 从版本 2 升到 3，新增消息时间字段与录制信息，升级保留旧消息。当前没有旧版降级迁移，也没有完整备份恢复工具；运行新版本后，不建议直接换回 1.22，不通过卸载或清空数据解决识别问题。

- 同签名优先覆盖安装；签名不一致时先确认数据保留方案。卸载会删除本机事件、消息、密钥与设置。
- 旧会话范围和已保存事件保留，正文关键词需要主动添加，不将旧会话词条改造成消息规则。
- 记录不会因为升级自动开启；重启后的会话记录保持暂停，回到目标会话确认才继续。
- 意图模式仍不自动创建事件；显式记录消息、手动保存事项和自动事件监测是不同操作。
- 复杂语义结果仍需核对原话；本机 OCR 需可用截屏，截断或误读标题保留确认路径。

<a id="usage"></a>
## 使用路径

| 需要做的事 | 入口 |
| --- | --- |
| 添加正文规则 | 微信「事件监测 → 更多 → 消息关键词」，或 APP「规则与外观 → 消息正文关键词」 |
| 开始单会话记录 | 微信意图面板的保存图标，确认会话后开始 |
| 按日期深度分析 | APP「会话分析」选择记录与消息日期范围 |
| 将意图中事项入库 | 微信「意图分析 → 更多 → 保存当前事项」 |
| 提供 Debug 信息 | APP「采集与回溯 → 诊断 → 导出诊断报告」 |

---

<a id="verification"></a>
## 验证与已知边界

- 来源发布前 296 项针对性回归通过；总仓库另行核对 Android 对象树与来源完全一致，并验证同步后的构建与受影响功能。
- 发布 APK 已真机覆盖安装；正文关键词命中、入库、APP 展示及编辑/确认删除已验证，测试规则已清理，原数据保留。
- 6 次滚动监测检查的原生标题均可读，不是 6 次真实 OCR 恢复测试。标题空节点时的实际 OCR 恢复、长时间录制、同名会话、系统导出完整交互与跨机型仍待验收。
- 此次总仓库同步不新增手机测试，不更改桌面端。APK 校验和一致不等于任意聊天准确率或兼容性保证。
- 没有发送微信消息、操作真实日历或归档用户事件；模型格式与证据约束使用模拟网络测试，不等于真实联网深化验收。

## 源码与记录

- [Android 源码](https://github.com/sssssjw11/touxian/tree/main/android) · [Android 完整使用说明](https://github.com/sssssjw11/touxian/blob/main/android/README.md)
- [来源提交 8145646](https://github.com/sssssjw11/attention-guard/commit/8145646f2b009d002ef285596a2e16f03580d352) · [来源完整变更](https://github.com/sssssjw11/attention-guard/compare/f258b5b...8145646)
- [总仓库变更](https://github.com/sssssjw11/touxian/compare/9881103...android-v1.25) · [诊断反馈](https://github.com/sssssjw11/touxian/issues)

<a id="english"></a>
<details>
<summary>English Release Notes</summary>

This Android-only preview adds message-body keyword rules, explicit single-chat recording, evidence-backed relationship reports, and diagnostic JSON export. It improves title continuity after scrolling and fixes asynchronous event saving/list refresh, negation, quotation, and keyword-editor timing issues.

- Android 11+; version 1.25 / code 26; debug-signed APK; package ID unchanged.
- Install over an existing same-signature build. The message database migrates from schema 2 to 3; no downgrade migration or complete backup/restore tool is provided. Do not uninstall to troubleshoot.
- Intent analysis remains local and does not automatically create events. Recording and saving an event are separate explicit actions. Optional APP-based DeepSeek refinement requires confirmation.
- Only visible messages are recorded. Relationship conclusions are candidates with source quotes, not love probabilities or diagnoses.
- The published APK is identical to the verified upstream release. Real missing-title OCR recovery, extended recording, same-name chats, and broader device compatibility still require validation.
- Desktop files and cross-device synchronization are unchanged.

</details>

Release organization references [CC Switch v3.20.4](https://github.com/farion1231/cc-switch/releases/tag/v3.20.4); its product changes and claims are not copied here.
