# jev-chat-JARVIS 对照记录

核对日期：2026-09-27。用户提供的 Finderchangchang/jev-chat-JARVIS 已转移到 jev-chat/jev-chat-jarvis，本次对照提交为 `86a12193059f86d9406250b1799c1438e233b34f`。

## 结论

参考项目当前已停用 Android 微信采集、OCR 和分析；它的现行支持对象主要是 QQ、X、飞书。因此不能把其 README 中其他平台的实测结果，当作微信群名识别已经解决的依据。偷闲继续保持 WeChat 单平台，不重新引入飞书等适配器。

来源：[README](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/README.md)、[采集服务](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/app/src/main/java/com/jev/probe/capture/ChatCaptureService.kt)。

## 本轮采用的思路

| 对照点 | 偷闲的处理 |
| --- | --- |
| 目标会话与请求代次同时校验 | 手动标记绑定独立请求对象和代次；旧 OCR 回调不能完成后一次标记。暂停观测或切换模式时取消未完成请求。 |
| 消息上下文与名称分开建模 | 抽出整屏消息签名；恢复名称不会改变消息身份。手动标题按有序消息重叠校验，不再把窗口 ID 当作唯一身份。 |
| 名称标准化 | 在已有全半角、大小写、人数后缀处理上补充零宽空格和 BOM 清理，保留拉丁词边界，避免短词串群。 |
| 用明确样本做回归 | 补充信息页延迟、错误设置项、截断标题、发言人冒充群名、返回错误聊天、过期回调等复现用例。 |

来源：[ConversationSession](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/app/src/main/java/com/jev/probe/capture/ConversationSession.kt)、[KbStore](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/app/src/main/java/com/jev/probe/core/kb/KbStore.kt)。本轮为现有结构内的独立实现，没有整段移植参考代码。

## 保留现有设计

- 继续使用整屏可见聊天语境，不改成参考项目 `buildState` 的末尾 10 条截取。
- 消息存档仍使用已有 `ScreenOverlap` 有序对齐和短期精确屏幕检查点，不做全局正文去重。重复发送相同通知仍可能是新消息。
- 长群名缺失时，用户点击标记后读取微信聊天信息页的“群聊名称”所在行；失败才进入本机标题 OCR 和手动确认。头像昵称不是群名依据。
- 意图模式不写消息存档；事件模式的本地规则与可选云端增强保持独立。本轮没有新增付费模型调用。

## 后续意图判断改进

参考项目将意图、紧张程度、需求、行动建议和是否缓和分开判断，值得用于梳理偷闲的情绪规则；尤其需要把“已经和好”与“带讽刺的随便你”作为不同样本。其 [JevQuestions](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/app/src/main/java/com/jev/probe/jev/JevQuestions.kt) 和 [calibrate.py](https://github.com/jev-chat/jev-chat-jarvis/blob/86a12193059f86d9406250b1799c1438e233b34f/tools/jev/calibrate.py) 可作为任务拆分、标注集和误差统计的参考。

这不等于完成模型训练或置信度校准。本轮未执行参考项目的在线模型评测，不能引用它的示例指标作为偷闲准确率。下一轮先按真实误判形成匿名样本，再分别统计意图、情绪、重要性和置信度区间的表现。
