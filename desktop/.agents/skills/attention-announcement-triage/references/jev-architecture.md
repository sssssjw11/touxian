# JEV 风格决策架构

本 Skill 按 Vercel / TypeSafe AI 对 Jev 的公开定义落地：Jev 接收一份共享
state 和一组窄问题，返回带概率的类型化决策；应用代码负责阈值、排序、权限和
后续动作。Jev 不负责写自然语言报告，报告由本地渲染器根据已批准的决策生成。
核心是：共享状态、独立判断问题、Choice/Score/Boolean、确定性归并、证据可回溯。

## 共享状态

```text
State = {
  source: {conversation, archive_range, as_of},
  messages: [...],
  candidates: [...],
  judgments: {candidate_id: {...}},
  decisions: [...],
  evidence: [...]
}
```

候选消息只追加，不覆盖原始消息。每个判断节点只能读取共享状态并写入自己的
结果槽位，不能直接改写最终优先级。

## 判断图

对每个公告候选，运行以下节点。节点之间尽量独立，最后由 reducer 统一合并。

| 节点 | 类型 | 输出 |
|---|---|---|
| `is_announcement` | `Boolean` | `true / false` 的概率 + 证据 |
| `category` | `choice` | `course / assignment / exam / activity / admin / safety / employment / resource / noise / other` |
| `audience` | `choice` | `all / subgroup / volunteers / unknown` |
| `deadline` | `app parser` | 原文、标准日期时间、状态、置信度；不强行交给模型猜日期 |
| `action_required` | `Boolean` | 是否需要成员行动的概率 |
| `importance` | `score` | 0-100 + 证据 |
| `urgency` | `score` | 0-100 + 计算原因 |
| `risk` | `score` | 0-100，面向缴费、身份、招聘、安全等需人工复核的内容 |

每个节点必须返回：

```json
{
  "question_id": "category",
  "answer_type": "Choice",
  "value": "...",
  "probabilities": {"option_a": 0.7, "insufficient_evidence": 0.3},
  "evidence_ids": ["m-123"],
  "reason": "..."
}
```

`value` 可以是 `null`，也可以包含 `insufficient_evidence`。缺少证据时不能
用“看起来像”补齐。

## Reducer

最终优先级由以下规则合成，顺序固定。概率只是决策依据，不直接变成用户看到的
“确定事实”：

1. `P0`：已逾期但仍需处理，或安全/身份/支付风险很高且明确要求行动。
2. `P1`：未来 72 小时内截止，或明确点名全体并需要行动。
3. `P2`：未来 7 天内截止，或重要但无明确 DDL 的课程、考试、行政事项。
4. `P3`：参考资料、招聘浏览、活动预告、已完成事项和普通群聊噪声。

同一优先级内按：截止时间升序、重要性降序、风险降序、原始时间降序。
日期不确定时不能因为“可能很急”直接升为 P0；应保留 `needs_review: true`。

截止状态由本地 reducer 相对 `as_of` 动态计算，远程判断不得覆盖：已超期、今日截止、
临近 1-3 天、宽裕 4 天以上、未排期、日期参考或待确认。展示层必须把 `overdue`
从进行中队列分流到独立超期库；变更 `as_of` 后重新计算，不能沿用旧缓存状态。

## Attention 原则

公告分拣的目标不是让用户读更多，而是减少注意力切换：

- 先告诉用户“现在要做什么”。
- 再告诉用户“什么时候做”。
- 最后保留“为什么这样判断”的最小证据。
