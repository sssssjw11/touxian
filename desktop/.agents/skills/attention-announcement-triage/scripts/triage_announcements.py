"""JEV-style announcement triage for exported WeChat group messages.

The script intentionally keeps the decision graph local and deterministic:
typed judges produce evidence-backed slots, then a reducer ranks the queue.
It is suitable as an offline baseline and can later be wrapped by a model
adapter without changing the output contract.
"""

from __future__ import annotations

import argparse
import difflib
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Iterable


if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


# Increment when the deterministic graph changes.  The web app uses this to
# invalidate cached triage results instead of silently displaying judgments
# produced by an older rule set.
ALGORITHM_VERSION = "2.4.0"
RECORD_KINDS = {"announcement", "resource", "conversation", "noise", "other"}
DEADLINE_STATUSES = {"overdue", "due_today", "near", "ample", "unscheduled", "reference", "unknown"}


CATEGORY_KEYWORDS = {
    "safety": ["台风", "避险", "防台", "暴雨", "防汛", "恶劣天气", "安全", "消防", "电动车", "充电", "宿舍", "交通", "撤离", "留校", "返校", "严禁", "禁止", "马蜂窝", "晚归", "无人机", "反诈", "诈骗"],
    "exam": ["考试", "补考", "缓考", "重修", "测验", "成绩", "考场", "准考证", "期末", "四六级", "计算机等级"],
    "course": ["上课", "课程", "调课", "停课", "教室", "课堂", "实验课", "补课", "教材", "课表", "校史馆", "教学"],
    "assignment": ["作业", "论文", "心得", "材料", "提交", "填报", "报名表", "问卷", "收集", "作品", "报告", "上传", "微推"],
    "admin": ["缴费", "团费", "医保", "保险", "请假", "证明", "档案", "户口", "学工系统", "信息采集", "个人信息", "登记", "统计", "转专业", "选课", "资助", "助学金", "不参保", "学籍", "赋分", "综合评价", "晚自习", "军训", "外宿", "青年大学习"],
    "employment": ["实习", "招聘", "就业", "岗位", "企业", "简历", "考公", "招收", "教师招聘", "招聘会"],
    "activity": ["会议", "讲座", "活动", "志愿", "党支部", "党课", "学习会", "趣味运动会", "竞赛", "招募", "交换生", "交流项目", "大讲堂", "海选", "社团", "街舞", "加油稿"],
    "resource": [
        "课件", "软件", "资料", "分享", "下载", "教程", "ppt", "附件", "安装",
        "解决方案", "配置", "命令", "命令行", "排错", "故障", "经验", "参考",
        "手册", "文档", "代码", "脚本", "开源", "github", "cisco", "wlc", "ap",
        "路由", "交换机", "网络", "版本", "驱动", "安装包", "题库",
    ],
    "noise": ["哈哈", "收到", "好的", "ok", "嗯嗯", "谢谢", "表情"],
}

# Generic "请" and "通知" are intentionally absent. They occur in ordinary
# forwarded text and otherwise make the candidate gate far too permissive.
REQUIRED_ACTION_WORDS = [
    "务必", "必须", "请于", "截止", "报名", "提交", "填写", "带上", "携带", "参加",
    "加入", "联系", "回复", "统计", "缴费", "下载", "安装", "遵守", "发我", "转发",
    "登录", "打印", "准备", "确认", "报备", "撤离", "暂停", "离校", "返校", "查询",
    "不得", "禁止", "严禁",
]
PASSIVE_RESOURCE_ACTION_WORDS = ["下载", "安装", "查阅", "查看", "参考", "学习", "转发", "查收"]
SOFT_ACTION_WORDS = ["需要", "记得", "请注意", "及时", "关注", "提醒", "查收"]
STRONG_ANNOUNCEMENT_WORDS = [
    "各位同学", "全体同学", "通知", "重要", "截止", "安排如下", "请注意",
    "@所有人", "报名", "招聘公告", "会议通知", "课程安排",
]
RISK_WORDS = ["缴费", "身份证", "银行卡", "密码", "验证码", "严肃处理", "诈骗", "骗取", "人身安全", "财产安全", "撤离", "不得返校"]
OPTIONAL_WORDS = ["感兴趣", "有意向", "自愿", "推荐", "欢迎", "可报名", "可以参加"]
HARD_REQUIRED_ACTION_WORDS = [
    "务必", "必须", "请于", "截止", "提交", "填写", "缴费", "携带", "带上", "回复", "统计",
    "报备", "撤离", "暂停", "离校", "返校", "不得", "禁止", "严禁", "确认", "上传", "打印",
]
DEADLINE_CUES = [
    "截止", "截至", "之前", "前提交", "前完成", "报名时间", "申请时间", "提交时间",
    "缴费时间", "选课时间", "填报时间", "开放时间", "核对时间", "关闭系统", "逾期",
]

NOISE_EXACT = {
    "收到", "好的", "好滴", "嗯", "嗯嗯", "哈哈", "谢谢", "ok", "OK", "继续", "3", "2",
    "1", "见上", "同上", "转发", "辛苦了", "辛苦", "了解", "收到啦", "好嘞",
}
CONVERSATIONAL_PREFIXES = ("我觉得", "我认为", "你们", "你咋", "哈哈", "笑死", "不是吧", "真的", "怎么", "有没有")
TITLE_MARKERS = ("通知", "提醒", "公告", "安排", "报名", "考试", "招聘", "竞赛", "讲座", "活动", "安全")

DATE_PATTERNS = [
    re.compile(r"(?P<year>20\d{2})\s*年\s*(?P<month>\d{1,2})\s*月\s*(?P<day>\d{1,2})\s*[日号]?"),
    re.compile(r"(?P<year>20\d{2})[./-](?P<month>0?[1-9]|1[0-2])[./-](?P<day>[0-2]?\d|3[01])"),
    re.compile(r"(?P<month>\d{1,2})\s*月\s*(?P<day>\d{1,2})\s*[日号]?"),
    re.compile(r"(?<!\d)(?P<month>0?[1-9]|1[0-2])[./-](?P<day>[0-2]?\d|3[01])(?!\d)"),
]
DAY_RANGE_PATTERN = re.compile(r"(?P<start>\d{1,2})\s*[-—~至]\s*(?P<end>\d{1,2})\s*日")
WEEKDAY_PATTERN = re.compile(r"(?:(?P<prefix>本|这|下)周|周|星期)(?P<weekday>[一二三四五六日天])(?P<before>前|之前)?")
TIME_PATTERN = re.compile(r"(?P<hour>\d{1,2})\s*[:：.]\s*(?P<minute>\d{2})")
HOUR_PATTERN = re.compile(r"(?P<hour>\d{1,2})点(?P<half>半)?")
DATE_CUE_PATTERN = re.compile(r"(?:日期|时间|截止|报名|考试|活动|会议|上课|周[一二三四五六日天]|星期|今天|明天|后天|本周|下周|月|日|号)")
DEFAULT_ALL_MENTION_WINDOW_MINUTES = 5
TECHNICAL_DATE_CUES = (
    "配置", "命令", "修改", "版本", "固件", "wlc", "ap", "ios", "时间修改",
    "时间设置", "时钟", "release", "build", "sdk", "api", "安装包",
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def parse_dt(value: str | None) -> date:
    if value:
        return datetime.strptime(value, "%Y-%m-%d").date()
    return date.today()


def message_date(message: dict[str, Any]) -> date | None:
    timestamp = message.get("timestamp")
    if not timestamp:
        return None
    return message_datetime(timestamp).date()


def filter_messages_by_date(
    messages: list[dict[str, Any]],
    from_date: date | None,
    to_date: date | None,
) -> list[dict[str, Any]]:
    if from_date and to_date and from_date > to_date:
        raise ValueError("--from-date 不能晚于 --to-date")
    selected = []
    for message in messages:
        current = message_date(message)
        if current is None:
            continue
        if from_date and current < from_date:
            continue
        if to_date and current > to_date:
            continue
        selected.append(message)
    return selected


def message_datetime(timestamp: int | float) -> datetime:
    return datetime.fromtimestamp(timestamp)


def compact(text: str, limit: int = 180) -> str:
    text = re.sub(r"\s+", " ", text or "").strip()
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "…"


def evidence_id(message: dict[str, Any]) -> str:
    """Build a stable evidence ID across multi-shard WeChat exports."""
    return f"m-{message.get('message_uid', message.get('local_id'))}"


def normalized_text(text: str) -> str:
    text = (text or "").lower()
    text = re.sub(r"https?://\S+", " URL ", text)
    text = re.sub(r"\[[^\]]+\]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def technical_date_context(text: str, start: int, end: int) -> bool:
    """Return True for version/configuration dates, not calendar events.

    WeChat messages often contain commands such as ``2022.12.02`` or a date
    embedded in a URL.  Those numbers are useful evidence for a technical
    resource, but they must never become an actionable deadline.
    """
    for url_match in re.finditer(r"https?://\S+", text, flags=re.IGNORECASE):
        if url_match.start() <= start < url_match.end():
            return True
    nearby = text[max(0, start - 48): min(len(text), end + 48)].lower()
    if any(cue in nearby for cue in TECHNICAL_DATE_CUES):
        return True
    # A dotted numeric version/build token is not a calendar date unless the
    # surrounding text explicitly describes an event or a submission.
    if re.search(r"\b(?:v\s*)?\d+(?:\.\d+){2,}\b", nearby, flags=re.IGNORECASE):
        return True
    return False


def infer_date_kind(nearby: str) -> str:
    if any(word in nearby for word in DEADLINE_CUES):
        return "deadline"
    if re.search(r"(?:前|之前)\s*(?:在线)?\s*(?:提交|完成|填写|填报|报名|申请|缴费|上交|发送|办理|交)", nearby):
        return "deadline"
    if re.search(r"(?:请|务必|需要|须|应|尽快).{0,18}(?:提交|完成|填写|填报|报名|申请|缴费|上交|发送|办理)", nearby):
        return "deadline"
    if re.search(r"(?:报名|申请|选课|提交|缴费|填报|开放|核对).{0,8}(?:截止|关闭|时间|日期)", nearby):
        return "deadline"
    return "event"


def message_features(message: dict[str, Any]) -> dict[str, Any]:
    content = str(message.get("content") or "").strip()
    lower = content.lower()
    strong_actions = [word for word in REQUIRED_ACTION_WORDS if word in content]
    soft_actions = [word for word in SOFT_ACTION_WORDS if word in content]
    dates = any(pattern.search(content) for pattern in DATE_PATTERNS) or bool(DAY_RANGE_PATTERN.search(content))
    urls = re.findall(r"https?://[^\s)]+", content)
    title = content.splitlines()[0].strip(" ：:") if content else ""
    category_scores = {
        category: sum(content.lower().count(keyword.lower()) for keyword in keywords)
        for category, keywords in CATEGORY_KEYWORDS.items()
    }
    resource_signals = [
        keyword for keyword in CATEGORY_KEYWORDS["resource"]
        if keyword.lower() in lower
    ]
    return {
        "length": len(content),
        "lower": lower,
        "strong_actions": strong_actions,
        "soft_actions": soft_actions,
        "has_date": dates,
        "urls": urls,
        "title": title,
        "category_scores": category_scores,
        "resource_signals": resource_signals,
        "has_marker": any(marker.lower() in lower for marker in TITLE_MARKERS),
    }


def is_candidate_message(message: dict[str, Any]) -> bool:
    if message.get("type") not in {"text", "link"}:
        return False
    content = str(message.get("content") or "").strip()
    if not content or content.startswith("[系统消息]"):
        return False
    normalized = normalized_text(content)
    if normalized in NOISE_EXACT or len(normalized) < 18:
        return False
    features = message_features(message)
    if any(normalized.startswith(prefix) for prefix in CONVERSATIONAL_PREFIXES) and features["length"] < 180:
        return False
    has_link = message.get("type") == "link" or bool(features["urls"])
    strong_marker = any(word.lower() in content.lower() for word in STRONG_ANNOUNCEMENT_WORDS)
    category_signal = max(features["category_scores"].values(), default=0)
    # A link-only card is useful only when its title looks like an announcement.
    if message.get("type") == "link" and features["length"] < 36:
        return bool(strong_marker or category_signal >= 2)
    if features["length"] >= 180:
        return bool(strong_marker or features["has_date"] or features["strong_actions"] or category_signal >= 2)
    return bool(
        strong_marker
        or features["has_date"]
        or len(features["strong_actions"]) >= 1
        or (has_link and (category_signal >= 1 or features["length"] >= 40))
    )


def is_continuation(message: dict[str, Any]) -> bool:
    text = str(message.get("content") or "").strip()
    if not text:
        return True
    first_line = text.splitlines()[0].strip()
    return bool(
        re.match(r"^(?:\d+[、.．)]|[（(]\d+[）)]|[-*])", first_line)
        or first_line.startswith(("具体", "如下", "其中", "请注意", "如有", "报名时间", "地点", "时间", "链接"))
        or len(text) < 55
    )


def topic_compatibility(left: dict[str, Any], right: dict[str, Any]) -> bool:
    lf = message_features(left)
    rf = message_features(right)
    left_scores = lf["category_scores"]
    right_scores = rf["category_scores"]
    left_category = max(left_scores, key=left_scores.get)
    right_category = max(right_scores, key=right_scores.get)
    if left_category != right_category and max(left_scores.values(), default=0) >= 2 and max(right_scores.values(), default=0) >= 2:
        return False
    left_urls = set(re.findall(r"https?://[^\s)]+", str(left.get("content") or "")))
    right_urls = set(re.findall(r"https?://[^\s)]+", str(right.get("content") or "")))
    if left_urls and right_urls and left_urls.isdisjoint(right_urls) and not is_continuation(right):
        return False
    # Strong new headings begin a new subject unless the following text is a continuation.
    if rf["has_marker"] and not is_continuation(right) and lf["has_marker"]:
        return False
    return True


def nearby_all_mentions(
    candidate_messages: list[dict[str, Any]],
    all_messages: list[dict[str, Any]],
    window_minutes: int,
) -> dict[str, Any]:
    candidate_ids = {evidence_id(message) for message in candidate_messages}
    start = min(message["timestamp"] for message in candidate_messages)
    end = max(message["timestamp"] for message in candidate_messages)
    window_seconds = max(0, window_minutes) * 60
    matches = []
    for message in all_messages:
        if evidence_id(message) in candidate_ids:
            continue
        if "@所有人" not in str(message.get("content") or ""):
            continue
        timestamp = message.get("timestamp")
        if not timestamp or timestamp < start - window_seconds or timestamp > end + window_seconds:
            continue
        distance = 0 if start <= timestamp <= end else min(abs(timestamp - start), abs(timestamp - end))
        matches.append({
            "message_id": evidence_id(message),
            "timestamp": message_datetime(timestamp).isoformat(sep=" ", timespec="minutes"),
            "distance_minutes": round(distance / 60, 2),
            "preview": compact(message.get("content"), 120),
        })
    matches.sort(key=lambda item: (item["distance_minutes"], item["timestamp"]))
    return {
        "nearby_all_mention": bool(matches),
        "window_minutes": window_minutes,
        "message_ids": [item["message_id"] for item in matches],
        "matches": matches,
    }


def cluster_candidates(
    messages: list[dict[str, Any]],
    all_mention_window_minutes: int = DEFAULT_ALL_MENTION_WINDOW_MINUTES,
) -> list[dict[str, Any]]:
    eligible = [message for message in messages if is_candidate_message(message)]
    eligible.sort(key=lambda message: message.get("timestamp", 0))
    clusters: list[list[dict[str, Any]]] = []
    for message in eligible:
        if not clusters:
            clusters.append([message])
            continue
        previous = clusters[-1][-1]
        gap = message_datetime(message["timestamp"]) - message_datetime(previous["timestamp"])
        if gap <= timedelta(minutes=12) and (topic_compatibility(previous, message) or is_continuation(message)):
            clusters[-1].append(message)
        else:
            clusters.append([message])

    result = []
    for index, cluster in enumerate(clusters, start=1):
        joined = "\n".join(str(message.get("content") or "") for message in cluster)
        if not is_announcement_text(joined):
            continue
        result.append({
            "candidate_id": f"ann-{index:04d}",
            "messages": cluster,
            "text": joined,
            "context": nearby_all_mentions(cluster, messages, all_mention_window_minutes),
        })
    return result


def is_announcement_text(text: str) -> bool:
    marker_count = sum(word.lower() in text.lower() for word in STRONG_ANNOUNCEMENT_WORDS)
    features = message_features({"content": text, "type": "text"})
    action_count = len(features["strong_actions"])
    has_date = features["has_date"]
    has_link = "http://" in text or "https://" in text
    category_signal = max(features["category_scores"].values(), default=0)
    return marker_count > 0 or action_count >= 1 or has_date or category_signal >= 2 or (has_link and len(text) >= 40)


def typed_result(
    value: Any,
    confidence: float,
    evidence_ids: list[str],
    reason: str,
    answer_type: str | None = None,
    probabilities: dict[str, float] | None = None,
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    confidence = round(max(0.0, min(1.0, confidence)), 3)
    if answer_type is None:
        if isinstance(value, bool):
            answer_type = "Boolean"
        elif isinstance(value, (int, float)):
            answer_type = "Score"
        else:
            answer_type = "Choice"
    if probabilities is None:
        if answer_type == "Boolean":
            probabilities = {"true": confidence if value else 1 - confidence, "false": 1 - confidence if value else confidence}
        elif answer_type == "Score":
            probabilities = {"score": confidence}
        else:
            probabilities = {str(value): confidence}
    result = {
        "answer_type": answer_type,
        "value": value,
        "confidence": confidence,
        "probabilities": probabilities,
        "evidence_ids": evidence_ids,
        "reason": reason,
    }
    if metadata:
        result["metadata"] = metadata
    return result


def choose_category(text: str, evidence_ids: list[str]) -> dict[str, Any]:
    features = message_features({"content": text, "type": "text"})
    scores = features["category_scores"]
    scores["noise"] = min(scores["noise"], 2)
    # Technical how-to messages are resources even when words such as
    # “经验/分享/活动” also occur in the body.  Give the resource judge a
    # small, explainable boost instead of relying on a single keyword tie.
    if len(features["resource_signals"]) >= 2:
        scores["resource"] += 2
    category_order = ["safety", "exam", "course", "admin", "assignment", "employment", "activity", "resource", "noise"]
    category = max(category_order, key=lambda name: (scores.get(name, 0), -category_order.index(name)))
    score = scores.get(category, 0)
    if score == 0:
        return typed_result("other", 0.25, evidence_ids, "没有命中明确类别词", answer_type="Choice")
    ordered = sorted(scores.values(), reverse=True)
    margin = score - (ordered[1] if len(ordered) > 1 else 0)
    confidence = min(0.95, 0.48 + 0.12 * margin + 0.04 * score)
    alternatives = [name for name in category_order if name != category and scores.get(name, 0) > 0]
    return typed_result(
        category,
        confidence,
        evidence_ids,
        f"类别词得分={score}，次高得分={ordered[1] if len(ordered) > 1 else 0}",
        answer_type="Choice",
        metadata={"scores": scores, "alternatives": alternatives[:3]},
    )


def choose_audience(text: str, evidence_ids: list[str]) -> dict[str, Any]:
    # "大家参考/大家查看" is a common way to introduce a resource share,
    # not evidence that every member must act.  Keep the audience unknown so
    # the record-kind gate can leave passive technical posts out of the queue.
    passive_reference = bool(
        re.search(
            r"(?:供|给|让)?\s*大家\s*(?:参考|查看|学习|了解|收藏|查收|下载|安装|转发)",
            text,
        )
    )
    if passive_reference and not any(word in text for word in HARD_REQUIRED_ACTION_WORDS):
        return typed_result("unknown", 0.68, evidence_ids, "“大家”用于资源分享语境，未形成明确全员行动", answer_type="Choice")
    if any(token in text for token in ["@所有人", "各位同学", "全体同学", "大家", "本群"]):
        return typed_result("all", 0.92, evidence_ids, "出现面向全体群成员的称呼", answer_type="Choice")
    if any(token in text for token in ["男生", "女生", "报名的", "有意向", "相关同学"]):
        return typed_result("subgroup", 0.78, evidence_ids, "出现限定对象", answer_type="Choice")
    return typed_result("unknown", 0.35, evidence_ids, "没有明确受众", answer_type="Choice")


def choose_action(text: str, evidence_ids: list[str]) -> dict[str, Any]:
    features = message_features({"content": text, "type": "text"})
    action_hits = features["strong_actions"]
    soft_hits = features["soft_actions"]
    optional = any(word in text for word in OPTIONAL_WORDS)
    request_phrase = bool(re.search(r"请(?:各位|全体|大家|同学|于|在|及时|尽快|务必).{0,12}", text))
    hard_action_hits = [word for word in action_hits if word in HARD_REQUIRED_ACTION_WORDS]
    resource_like = len(features.get("resource_signals", [])) >= 2
    addressed_to_group = any(token in text for token in ["@所有人", "各位同学", "全体同学", "大家", "本群"])
    # “下载/安装/查看/转发” in a standalone how-to or link share is not an
    # obligation.  Keep it informational unless the message also addresses
    # the group, contains a hard action, or has a deadline cue.
    passive_only = resource_like and not hard_action_hits and not addressed_to_group and not any(
        cue in text for cue in DEADLINE_CUES
    )
    effective_action_hits = [word for word in action_hits if word not in PASSIVE_RESOURCE_ACTION_WORDS]
    if passive_only and not effective_action_hits and not request_phrase:
        return typed_result(
            False,
            0.84,
            evidence_ids,
            "资源/技术分享中的查看、下载或参考语气，不构成群体行动要求",
            answer_type="Boolean",
            metadata={"kind": "informational", "signals": (action_hits + soft_hits)[:8], "hard_signals": []},
        )
    if action_hits or request_phrase:
        kind = "optional" if optional and not hard_action_hits else "required"
        return typed_result(
            True,
            min(0.95, 0.58 + 0.05 * len(action_hits) + (0.05 if request_phrase else 0)),
            evidence_ids,
            "命中明确行动词" + ("，但包含自愿语气" if optional and not hard_action_hits else ""),
            answer_type="Boolean",
            metadata={"kind": kind, "signals": (action_hits + soft_hits)[:8], "hard_signals": hard_action_hits[:8]},
        )
    return typed_result(False, 0.76, evidence_ids, "没有命中明确行动要求", answer_type="Boolean", metadata={"kind": "informational", "signals": soft_hits[:8]})


def classify_record_kind(
    text: str,
    category: str,
    audience: str,
    action: dict[str, Any],
    deadline: dict[str, Any] | None,
) -> tuple[str, str, float]:
    """Classify the candidate before scoring it.

    Candidate selection answers “值得看一眼”，whereas this gate answers
    “是否是公告”。Keeping the two separate prevents a useful resource or a
    long conversational post from being promoted into the action queue.
    """
    normalized = normalized_text(text)
    features = message_features({"content": text, "type": "text"})
    if normalized in NOISE_EXACT:
        return "noise", "短确认、表情或固定噪声", 0.96
    if any(normalized.startswith(prefix) for prefix in CONVERSATIONAL_PREFIXES) and len(normalized) < 180:
        return "conversation", "以闲聊句式开头且没有公告结构", 0.86

    action_kind = (action.get("metadata") or {}).get("kind")
    hard_action = bool((action.get("metadata") or {}).get("hard_signals"))
    has_marker = any(word.lower() in text.lower() for word in STRONG_ANNOUNCEMENT_WORDS)
    has_date = bool(deadline)
    has_group_target = audience == "all"
    # Only treat short list numbers as announcement structure.  A broad
    # ``\d+[.]`` pattern mistakes technical versions such as ``2022.12.02``
    # for a numbered notice item.
    has_structure = bool(re.search(r"(?:^|\n)\s*(?:[一二三四五六七八九十]+[、.]|\d{1,2}[、.]|时间[:：]|地点[:：]|报名[:：])", text))
    resource_like = len(features.get("resource_signals", [])) >= 2

    # Length alone is not a safe noise gate.  Short, high-signal messages such
    # as “请于9月25日前提交作业” still carry a real deadline and must remain
    # actionable candidates.
    if len(normalized) < 18 and not (hard_action or has_date or has_marker or has_structure):
        return "noise", "短文本且缺少日期、行动或公告结构", 0.9

    if resource_like and not (hard_action or has_date or has_group_target or has_structure or has_marker):
        return "resource", "技术/资料分享，缺少面向群体的公告结构", 0.88
    if has_marker or hard_action or has_date or has_group_target or has_structure:
        confidence = 0.72
        confidence += 0.08 * sum(bool(value) for value in [has_marker, hard_action, has_date, has_group_target, has_structure])
        if action_kind == "optional":
            confidence -= 0.05
        return "announcement", "含公告标记、明确行动、日期或受众结构", min(0.96, confidence)
    if resource_like:
        return "resource", "以资料/技术内容为主，行动性不足", 0.78
    if category == "noise":
        return "noise", "命中噪声词且没有结构化事项", 0.9
    return "other", "有一定事项信号，但不足以确认是公告", 0.48


def safe_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def classify_deadline_status(deadline: dict[str, Any] | None) -> str:
    """Map a parsed date to a stable, user-facing deadline band.

    The judgment is relative to the analysis ``as_of`` date through the
    parser's ``days_from_as_of`` value.  Event dates stay references rather
    than being presented as submission deadlines.
    """
    if not deadline:
        return "unscheduled"
    if deadline.get("kind") != "deadline":
        return "reference"
    days = deadline.get("days_from_as_of")
    if not isinstance(days, int):
        return "unknown"
    if days < 0:
        return "overdue"
    if days == 0:
        return "due_today"
    if days <= 3:
        return "near"
    return "ample"


def extract_deadlines(text: str, anchor: datetime, as_of: date, evidence_ids: list[str]) -> dict[str, Any]:
    found: list[dict[str, Any]] = []
    for match in DAY_RANGE_PATTERN.finditer(text):
        if technical_date_context(text, match.start(), match.end()):
            continue
        parsed = safe_date(anchor.year, anchor.month, int(match.group("end")))
        if not parsed:
            continue
        nearby = text[max(0, match.start() - 45): min(len(text), match.end() + 50)]
        found.append({
            "raw": compact(nearby, 100),
            "normalized": parsed.isoformat(),
            "date": parsed,
            "kind": infer_date_kind(nearby),
            "confidence": 0.78,
        })
    for pattern in DATE_PATTERNS:
        for match in pattern.finditer(text):
            # Numeric fragments inside URLs, IDs, scores, and phrases such as
            # "3-3张照片" are not dates unless nearby text provides a date cue.
            if pattern.pattern.startswith(r"(?<!\d)"):
                nearby_context = text[max(0, match.start() - 24): min(len(text), match.end() + 24)]
                if re.search(r"\d{1,2}[:：]\d{2}\s*[-—~]\s*\d{1,2}[:：]\d{2}", nearby_context):
                    continue
                if re.search(r"\d\s*[-—~]\s*\d\s*(?:张|个|次|人|份|门)", nearby_context):
                    continue
                if not DATE_CUE_PATTERN.search(nearby_context):
                    continue
            if DAY_RANGE_PATTERN.search(text[max(0, match.start() - 2): min(len(text), match.end() + 2)]):
                continue
            if technical_date_context(text, match.start(), match.end()):
                continue
            year = int(match.groupdict().get("year") or anchor.year)
            month = int(match.group("month"))
            day = int(match.group("day"))
            parsed = safe_date(year, month, day)
            if not parsed:
                continue
            nearby = text[max(0, match.start() - 45): min(len(text), match.end() + 50)]
            hour = minute = None
            time_match = TIME_PATTERN.search(text[match.end(): min(len(text), match.end() + 35)])
            if time_match:
                hour, minute = int(time_match.group("hour")), int(time_match.group("minute"))
                if hour > 23 or minute > 59:
                    hour = minute = None
            else:
                hour_match = HOUR_PATTERN.search(text[match.end(): min(len(text), match.end() + 35)])
                if hour_match:
                    hour = int(hour_match.group("hour"))
                    minute = 30 if hour_match.group("half") else 0
                    if hour > 23:
                        hour = minute = None
            normalized = parsed.isoformat()
            if hour is not None:
                normalized += f"T{hour:02d}:{minute:02d}"
            found.append({
                "raw": compact(nearby, 100),
                "normalized": normalized,
                "date": parsed,
                "kind": infer_date_kind(nearby),
                "confidence": 0.9 if "year" in match.groupdict() and match.group("year") else 0.78,
            })

    weekday_numbers = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6}
    for match in WEEKDAY_PATTERN.finditer(text):
        target_weekday = weekday_numbers[match.group("weekday")]
        current_weekday = anchor.date().weekday()
        prefix = match.group("prefix")
        if prefix == "下":
            delta = 7 - current_weekday + target_weekday
        else:
            delta = target_weekday - current_weekday
            if delta < 0 and prefix not in {"本", "这"}:
                delta += 7
        parsed = anchor.date() + timedelta(days=delta)
        nearby = text[max(0, match.start() - 35): min(len(text), match.end() + 45)]
        found.append({
            "raw": compact(nearby, 100),
            "normalized": parsed.isoformat(),
            "date": parsed,
            "kind": "deadline" if match.group("before") else infer_date_kind(nearby),
            "confidence": 0.72 if prefix else 0.62,
        })

    for phrase, delta in [("今天", 0), ("明天", 1), ("后天", 2), ("今晚", 0)]:
        if phrase in text:
            parsed = anchor.date() + timedelta(days=delta)
            phrase_start = text.find(phrase)
            nearby = text[max(0, phrase_start - 25): min(len(text), phrase_start + len(phrase) + 45)]
            found.append({
                "raw": phrase,
                "normalized": parsed.isoformat(),
                "date": parsed,
                "kind": infer_date_kind(nearby),
                "confidence": 0.6,
            })

    if not found and infer_date_kind(text) == "deadline":
        time_match = TIME_PATTERN.search(text)
        hour_match = HOUR_PATTERN.search(text)
        hour = minute = None
        if time_match:
            hour, minute = int(time_match.group("hour")), int(time_match.group("minute"))
        elif hour_match:
            hour = int(hour_match.group("hour"))
            minute = 30 if hour_match.group("half") else 0
        if hour is not None and hour <= 23 and minute is not None and minute <= 59:
            parsed = anchor.date()
            found.append({
                "raw": compact(text, 100),
                "normalized": f"{parsed.isoformat()}T{hour:02d}:{minute:02d}",
                "date": parsed,
                "kind": "deadline",
                "confidence": 0.56,
            })

    deduped = {}
    for item in found:
        deduped[(item["normalized"], item["kind"])] = item
    found = sorted(deduped.values(), key=lambda item: item["date"])
    if not found:
        return typed_result(None, 0.25, evidence_ids, "没有解析到明确日期；保留原文中的相对时间需人工确认", answer_type="AppParser")

    deadline_matches = [item for item in found if item["kind"] == "deadline"]
    # For ranges such as "9月14日—9月25日", the end is the actionable DDL.
    chosen = max(deadline_matches, key=lambda item: item["date"]) if deadline_matches else found[0]
    days = (chosen["date"] - as_of).days
    status = classify_deadline_status({
        "kind": chosen["kind"],
        "days_from_as_of": days,
    })
    return typed_result(
        {
            "raw": chosen["raw"],
            "normalized": chosen["normalized"],
            "status": status,
            "kind": chosen["kind"],
            "days_from_as_of": days,
            "all_matches": [
                {key: value for key, value in item.items() if key not in {"date", "confidence"}}
                | {"confidence": item["confidence"]}
                for item in found
            ],
        },
        chosen["confidence"],
        evidence_ids,
        f"从文本中解析到 {len(found)} 个日期，采用截止语义中最晚日期作为排序锚点",
        answer_type="AppParser",
    )


def score_importance(
    text: str,
    category: str,
    audience: str,
    action_judgment: dict[str, Any],
    evidence_ids: list[str],
    record_kind: str = "announcement",
    deadline: dict[str, Any] | None = None,
) -> dict[str, Any]:
    score = 20
    reasons = []
    if audience == "all":
        score += 30
        reasons.append("面向全体")
    if category in {"course", "assignment", "exam", "admin", "safety"}:
        score += 25
        reasons.append("课程/提交/考试/行政/安全事项")
    if category in {"employment", "activity"}:
        score += 12
        reasons.append("机会或活动事项")
    if deadline:
        score += 10
        reasons.append("含日期")
    if "http://" in text or "https://" in text or "[链接]" in text or "附件" in text:
        score += 10
        reasons.append("含链接或附件")
    if action_judgment.get("metadata", {}).get("kind") == "optional":
        score -= 15
        reasons.append("自愿/推荐语气")
    if record_kind == "resource":
        score = min(score, 35)
        reasons.append("资料/技术分享不进入行动事项")
    elif record_kind in {"conversation", "noise", "other"}:
        score = min(score, 20)
        reasons.append("未确认是公告")
    score = max(0, min(100, score))
    return typed_result(score, 0.82, evidence_ids, "；".join(reasons) or "默认重要性", answer_type="Score")


def score_urgency(deadline: dict[str, Any] | None, evidence_ids: list[str]) -> dict[str, Any]:
    if not deadline:
        return typed_result(10, 0.4, evidence_ids, "没有可用 DDL，按低紧迫性处理", answer_type="Score")
    days = deadline.get("days_from_as_of")
    if not isinstance(days, int):
        return typed_result(10, 0.3, evidence_ids, "DDL 日期不确定", answer_type="Score")
    if deadline.get("kind") != "deadline":
        if days < 0:
            score = 5
        elif days <= 3:
            score = 70
        elif days <= 7:
            score = 48
        elif days <= 30:
            score = 28
        else:
            score = 10
        return typed_result(score, 0.82, evidence_ids, f"事件日期距离 as-of 日期 {days} 天；不是行动截止", answer_type="Score")
    if days < -30:
        score = 5
    elif days < 0:
        score = 100
    elif days <= 3:
        score = 90
    elif days <= 7:
        score = 68
    elif days <= 30:
        score = 42
    else:
        score = 15
    reason = f"距离 as-of 日期 {days} 天"
    if days < -30:
        reason += "；属于历史消息，不默认进入当前行动队列"
    return typed_result(score, 0.88, evidence_ids, reason, answer_type="Score")


def score_risk(text: str, category: str, evidence_ids: list[str]) -> dict[str, Any]:
    hits = []
    if "缴费" in text or "银行卡" in text or "验证码" in text:
        hits.append("支付或验证码")
    if any(word in text for word in ["诈骗", "骗取", "人身安全", "财产安全", "不得返校"]):
        hits.append("安全或诈骗")
    if any(word in text for word in ["索要密码", "提供密码", "发送密码", "泄露密码"]):
        hits.append("密码泄露")
    if "身份证" in text and any(word in text for word in ["上传", "收集", "复印件", "照片", "提交"]):
        hits.append("身份材料")
    if category == "safety" and any(word in text for word in ["台风", "暴雨", "撤离", "不得返校", "人身安全"]):
        hits.append("极端天气或人身安全")
    score = min(100, len(hits) * 18)
    if category == "safety" and any(word in text for word in ["台风", "暴雨", "撤离", "不得返校", "人身安全"]):
        score = max(score, 60)
    if any(word in text for word in ["诈骗", "骗取", "银行卡", "验证码", "索要密码", "提供密码", "发送密码", "泄露密码"]):
        score = max(score, 78)
    return typed_result(score, 0.76 if hits else 0.55, evidence_ids, "风险词: " + ", ".join(hits) if hits else "未命中高风险词", answer_type="Score")


def judge_candidate(candidate: dict[str, Any], as_of: date) -> dict[str, Any]:
    messages = candidate["messages"]
    evidence_ids = [evidence_id(message) for message in messages]
    text = candidate["text"]
    anchor = message_datetime(messages[0]["timestamp"])
    category = choose_category(text, evidence_ids)
    audience = choose_audience(text, evidence_ids)
    action = choose_action(text, evidence_ids)
    deadline = extract_deadlines(text, anchor, as_of, evidence_ids)
    deadline_value = deadline["value"] if isinstance(deadline["value"], dict) else None
    record_kind, record_reason, record_confidence = classify_record_kind(
        text, category["value"], audience["value"], action, deadline_value
    )
    importance = score_importance(
        text,
        category["value"],
        audience["value"],
        action,
        evidence_ids,
        record_kind=record_kind,
        deadline=deadline_value,
    )
    urgency = score_urgency(deadline_value, evidence_ids)
    risk = score_risk(text, category["value"], evidence_ids)
    is_announcement = typed_result(
        record_kind == "announcement",
        record_confidence,
        evidence_ids,
        record_reason,
        answer_type="Boolean",
        metadata={"record_kind": record_kind},
    )
    record_kind_judgment = typed_result(
        record_kind,
        record_confidence,
        evidence_ids,
        record_reason,
        answer_type="Choice",
        metadata={"allowed_values": sorted(RECORD_KINDS)},
    )
    judgments = {
        "is_announcement": is_announcement,
        "record_kind": record_kind_judgment,
        "category": category,
        "audience": audience,
        "deadline": deadline,
        "action_required": action,
        "importance": importance,
        "urgency": urgency,
        "risk": risk,
    }
    for question_id, judgment in judgments.items():
        judgment.setdefault("question_id", question_id)
    return {
        "candidate_id": candidate["candidate_id"],
        "window": {
            "start": message_datetime(messages[0]["timestamp"]).isoformat(sep=" ", timespec="minutes"),
            "end": message_datetime(messages[-1]["timestamp"]).isoformat(sep=" ", timespec="minutes"),
        },
        "message_ids": evidence_ids,
        "preview": compact(text, 260),
        "context": candidate.get("context", {}),
        "judgments": judgments,
    }


def reduce_priority(item: dict[str, Any], as_of: date) -> dict[str, Any]:
    judgments = item["judgments"]
    importance = judgments["importance"]["value"] or 0
    urgency = judgments["urgency"]["value"] or 0
    risk = judgments["risk"]["value"] or 0
    action = judgments["action_required"]["value"]
    action_meta = judgments["action_required"].get("metadata", {})
    required_action = bool(action and action_meta.get("kind") == "required")
    record_kind = (
        judgments.get("record_kind", {}).get("value")
        or judgments.get("is_announcement", {}).get("metadata", {}).get("record_kind")
        or ("announcement" if judgments.get("is_announcement", {}).get("value") else "other")
    )
    is_announcement = bool(judgments.get("is_announcement", {}).get("value")) and record_kind == "announcement"
    deadline = judgments["deadline"]["value"] or {}
    status = classify_deadline_status(deadline or None)
    queue_state = "archived" if status == "overdue" else "active"
    if deadline:
        # Recompute instead of trusting a model or stale cache.  Deadline
        # bands are deterministic reducer state derived from ``as_of``.
        deadline["status"] = status
    days = deadline.get("days_from_as_of")
    message_date = date.fromisoformat(item["window"]["end"][:10])
    historical = (
        isinstance(days, int) and days < -30
    ) or (
        deadline.get("normalized") is None and message_date < as_of - timedelta(days=30)
    )
    context = item.get("context", {})
    nearby_all_mention = bool(context.get("nearby_all_mention"))
    needs_review = False
    if risk >= 70 and required_action and is_announcement:
        priority = "P0"
    elif status == "overdue" and not historical and deadline.get("kind") == "deadline" and required_action and is_announcement:
        priority = "P0"
    elif is_announcement and (urgency >= 80 or (importance >= 70 and required_action and judgments["audience"]["value"] == "all")):
        priority = "P1"
    elif is_announcement and (urgency >= 40 or importance >= 50):
        priority = "P2"
    else:
        priority = "P3"
    if historical:
        priority = "P3"
    elif nearby_all_mention and is_announcement and priority == "P3":
        priority = "P2"
    if is_announcement and judgments["deadline"]["value"] is None and importance >= 75 and action:
        needs_review = True
    if record_kind in {"conversation", "other"}:
        needs_review = True
    if judgments["category"]["value"] in {"other", "noise"} and judgments["category"]["confidence"] < 0.5:
        needs_review = True
    if risk >= 50:
        needs_review = True
    item["decision"] = {
        "priority": priority,
        "queue_state": queue_state,
        "archive_reason": "deadline_passed" if queue_state == "archived" else None,
        "importance": importance,
        "urgency": urgency,
        "risk": risk,
        "deadline_status": status,
        "deadline_band": status,
        "historical": historical,
        "needs_review": needs_review,
        "action_kind": action_meta.get("kind", "informational"),
        "record_kind": record_kind,
        "is_announcement": is_announcement,
        "nearby_all_mention": nearby_all_mention,
        "nearby_all_mention_message_ids": context.get("message_ids", []),
        "nearby_all_mention_window_minutes": context.get("window_minutes"),
        "reason": f"{priority}: importance={importance}, urgency={urgency}, risk={risk}, deadline={status}",
    }
    return item


def item_urls(item: dict[str, Any]) -> set[str]:
    return set(re.findall(r"https?://[^\s)]+", item.get("preview", "")))


def item_fingerprint(item: dict[str, Any]) -> str:
    text = normalized_text(item.get("preview", ""))
    return re.sub(r"[^\w\u4e00-\u9fff]+", "", text)


def is_recruitment_digest(item: dict[str, Any]) -> bool:
    if item["judgments"]["category"]["value"] != "employment":
        return False
    text = item.get("preview", "")
    return any(token in text for token in ["教师招聘岗位推荐", "招聘公告汇总", "岗位推荐", "特岗教师招聘公告"])


def merge_duplicate(base: dict[str, Any], duplicate: dict[str, Any], reason: str) -> dict[str, Any]:
    # Keep the newest representative while retaining every source message ID.
    base_end = base["window"]["end"]
    duplicate_end = duplicate["window"]["end"]
    representative = duplicate if duplicate_end >= base_end else base
    other = base if representative is duplicate else duplicate
    merged_ids = list(dict.fromkeys(representative["message_ids"] + other["message_ids"]))
    merged_candidates = list(dict.fromkeys(
        representative.get("merged_candidate_ids", [representative["candidate_id"]])
        + other.get("merged_candidate_ids", [other["candidate_id"]])
    ))
    representative["message_ids"] = merged_ids
    representative["merged_candidate_ids"] = merged_candidates
    representative["deduplication"] = {
        "merged_count": len(merged_candidates),
        "reason": reason,
    }
    return representative


def dedupe_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse forwards without comparing every item with every other item.

    Exact fingerprints and shared URLs provide cheap candidate buckets.  The
    comparatively expensive SequenceMatcher is only run inside a shared-URL
    bucket, which keeps large class archives comfortably interactive.
    """
    groups: list[dict[str, Any]] = []
    fingerprint_index: dict[tuple[str, str], set[int]] = defaultdict(set)
    url_index: dict[str, set[int]] = defaultdict(set)
    digest_indices: set[int] = set()

    def register(index: int, value: dict[str, Any]) -> None:
        category = value["judgments"]["category"]["value"]
        fingerprint = item_fingerprint(value)
        if fingerprint:
            fingerprint_index[(category, fingerprint)].add(index)
        for url in item_urls(value):
            url_index[url].add(index)
        if is_recruitment_digest(value):
            digest_indices.add(index)

    for item in sorted(items, key=lambda value: value["window"]["end"]):
        category = item["judgments"]["category"]["value"]
        fingerprint = item_fingerprint(item)
        urls = item_urls(item)
        candidate_indices: set[int] = set()
        if fingerprint:
            candidate_indices.update(fingerprint_index.get((category, fingerprint), set()))
        for url in urls:
            candidate_indices.update(url_index.get(url, set()))
        if is_recruitment_digest(item):
            candidate_indices.update(digest_indices)

        merged = False
        for index in sorted(candidate_indices):
            existing = groups[index]
            if existing["judgments"]["category"]["value"] != category:
                continue
            existing_fp = item_fingerprint(existing)
            existing_urls = item_urls(existing)
            same_text = bool(fingerprint and fingerprint == existing_fp)
            same_url = bool(urls and existing_urls and urls.intersection(existing_urls))
            similar = (
                difflib.SequenceMatcher(None, fingerprint, existing_fp).ratio()
                if same_url and fingerprint and existing_fp else 0
            )
            digest_pair = is_recruitment_digest(item) and is_recruitment_digest(existing)
            if same_text or (same_url and similar >= 0.5) or (
                digest_pair and not item["judgments"]["deadline"]["value"]
            ):
                reason = (
                    "same normalized text" if same_text
                    else "same link and similar title" if same_url
                    else "recurring recruitment digest"
                )
                groups[index] = merge_duplicate(existing, item, reason)
                register(index, groups[index])
                merged = True
                break
        if not merged:
            groups.append(item)
            register(len(groups) - 1, item)
    return groups


def sort_key(item: dict[str, Any]) -> tuple[Any, ...]:
    rank = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
    deadline = item["judgments"]["deadline"]["value"] or {}
    status = item.get("decision", {}).get("deadline_status") or classify_deadline_status(deadline or None)
    days = deadline.get("days_from_as_of")
    status_rank = {
        "due_today": 0,
        "near": 1,
        "ample": 2,
        "unscheduled": 3,
        "reference": 4,
        "unknown": 5,
        "overdue": 6,
    }
    # Inside the overdue archive, a deadline missed yesterday is more useful
    # than one missed years ago.  Active dates keep their natural proximity.
    deadline_sort = abs(days) if status == "overdue" and isinstance(days, int) else (days if isinstance(days, int) else 9999)
    if item.get("decision", {}).get("queue_state") == "archived":
        return (1, deadline_sort, -item["decision"]["importance"], -item["decision"]["risk"])
    return (
        0,
        rank[item["decision"]["priority"]],
        status_rank.get(status, 5),
        deadline_sort,
        -item["decision"]["importance"],
        -item["decision"]["risk"],
    )


def format_deadline(item: dict[str, Any]) -> str:
    deadline = item["judgments"]["deadline"]["value"]
    if not deadline:
        return "未识别明确 DDL"
    status = deadline.get("status", "unknown")
    return f"{deadline.get('normalized')} ({status})"


def write_markdown(result: dict[str, Any], path: Path) -> None:
    source = result["source"]
    date_filter = source.get("message_date_filter") or {}
    filter_from = date_filter.get("from") or "不限"
    filter_to = date_filter.get("to") or "不限"
    lines = [
        f"# Attention 公告分拣：{source['conversation']}",
        "",
        f"- 分析基准日：{source['as_of']}",
        f"- 消息筛选范围：{filter_from} 至 {filter_to}",
        f"- 完整归档范围：{source['archive_start']} 至 {source['archive_end']}",
        f"- 归档消息：{source['archive_message_count']} 条；本次分析消息：{source['message_count']} 条；原始候选：{result['summary']['raw_candidate_count']} 条；去重后事项：{result['summary']['candidate_count']} 条",
        "",
        "> 这是基于本地归档的注意力队列。归档结束日期早于分析基准日时，不代表之后没有新公告。",
        "",
    ]
    lines += ["## 分类概览", ""]
    for category, count in sorted(result["summary"]["category_counts"].items(), key=lambda pair: (-pair[1], pair[0])):
        lines.append(f"- {category}: {count}")
    lines += ["", "> `triage.json` 保留全部去重后的结构化事项；本页只展开注意力优先级较高的事项，避免再次制造信息洪流。", ""]
    for priority in ["P0", "P1", "P2", "P3"]:
        items = [item for item in result["items"] if item["decision"]["priority"] == priority]
        lines += [f"## {priority} · {priority_label(priority)}", ""]
        if not items:
            lines += ["暂无。", ""]
            continue
        display_limit = {"P0": 50, "P1": 80, "P2": 80, "P3": 30}[priority]
        displayed = items[:display_limit]
        for index, item in enumerate(displayed, start=1):
            j = item["judgments"]
            action_meta = j["action_required"].get("metadata", {})
            lines.append(f"### {index}. {j['category']['value']} | {format_deadline(item)}")
            lines.append(f"- 重要性 {item['decision']['importance']}/100；紧迫性 {item['decision']['urgency']}/100；风险 {item['decision']['risk']}/100")
            lines.append(f"- 受众：{j['audience']['value']}；行动：{action_meta.get('kind', 'unknown')}")
            if item["decision"].get("nearby_all_mention"):
                nearby_ids = ", ".join(item["decision"].get("nearby_all_mention_message_ids", []))
                window_minutes = item["decision"].get("nearby_all_mention_window_minutes")
                lines.append(f"- 提升依据：{window_minutes} 分钟内发现近邻 @所有人（证据：{nearby_ids}），最低按 P2")
            if item["decision"]["needs_review"]:
                lines.append("- 复核：需要人工确认")
            lines.append(f"- 证据：{', '.join(item['message_ids'])}")
            lines.append(f"- 内容：{item['preview']}")
            lines.append("")
        if len(items) > display_limit:
            lines.append(f"> 其余 {len(items) - display_limit} 条 {priority} 事项已保留在 `triage.json`，此处省略以保护注意力。")
            lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def priority_label(priority: str) -> str:
    return {"P0": "立即处理 / 逾期或高风险", "P1": "近期开工", "P2": "重要但可排期", "P3": "参考或低优先级"}[priority]


def main() -> None:
    parser = argparse.ArgumentParser(description="JEV 风格微信群公告分拣")
    parser.add_argument("--input", required=True, help="extract_messages.py 生成的 messages.json")
    parser.add_argument("--output-dir", required=True, help="triage.json / attention.md 输出目录")
    parser.add_argument("--as-of", help="分析基准日 YYYY-MM-DD；默认使用本机日期")
    date_group = parser.add_argument_group("消息日期筛选")
    date_group.add_argument("--date", help="只分析消息日期为 YYYY-MM-DD 的一天")
    date_group.add_argument("--from-date", help="只分析该日期及之后的消息，格式 YYYY-MM-DD")
    date_group.add_argument("--to-date", help="只分析该日期及之前的消息，格式 YYYY-MM-DD")
    parser.add_argument(
        "--all-mention-window-minutes",
        type=int,
        default=DEFAULT_ALL_MENTION_WINDOW_MINUTES,
        help=f"近邻 @所有人 提升窗口，默认 {DEFAULT_ALL_MENTION_WINDOW_MINUTES} 分钟",
    )
    args = parser.parse_args()

    if args.date and (args.from_date or args.to_date):
        parser.error("--date 不能与 --from-date/--to-date 同时使用")
    if args.all_mention_window_minutes < 0:
        parser.error("--all-mention-window-minutes 不能小于 0")

    input_path = Path(args.input).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    source = load_json(input_path)
    all_messages = source.get("messages", [])
    as_of = parse_dt(args.as_of)
    if args.date:
        from_date = to_date = parse_dt(args.date)
    else:
        from_date = parse_dt(args.from_date) if args.from_date else None
        to_date = parse_dt(args.to_date) if args.to_date else None
    messages = filter_messages_by_date(all_messages, from_date, to_date)
    timestamps = [message["timestamp"] for message in all_messages if message.get("timestamp")]
    selected_timestamps = [message["timestamp"] for message in messages if message.get("timestamp")]
    archive_start = message_datetime(min(timestamps)).date().isoformat() if timestamps else None
    archive_end = message_datetime(max(timestamps)).date().isoformat() if timestamps else None
    selected_start = message_datetime(min(selected_timestamps)).date().isoformat() if selected_timestamps else None
    selected_end = message_datetime(max(selected_timestamps)).date().isoformat() if selected_timestamps else None

    candidates = cluster_candidates(messages, args.all_mention_window_minutes)
    raw_items = [reduce_priority(judge_candidate(candidate, as_of), as_of) for candidate in candidates]
    items = dedupe_items(raw_items)
    items.sort(key=sort_key)
    category_counts = Counter(item["judgments"]["category"]["value"] for item in items)
    priority_counts = Counter(item["decision"]["priority"] for item in items)
    deadline_status_counts = Counter(item["decision"]["deadline_status"] for item in items)
    queue_state_counts = Counter(item["decision"]["queue_state"] for item in items)
    result = {
        "schema_version": "1.2",
        "algorithm_version": ALGORITHM_VERSION,
        "architecture": "jev-style-local",
        "source": {
            "conversation": source.get("contact_display"),
            "input": str(input_path),
            "as_of": as_of.isoformat(),
            "archive_start": archive_start,
            "archive_end": archive_end,
            "selected_start": selected_start,
            "selected_end": selected_end,
            "message_date_filter": {
                "from": from_date.isoformat() if from_date else None,
                "to": to_date.isoformat() if to_date else None,
            },
            "all_mention_window_minutes": args.all_mention_window_minutes,
            "archive_message_count": len(all_messages),
            "message_count": len(messages),
        },
        "summary": {
            "raw_candidate_count": len(raw_items),
            "candidate_count": len(items),
            "priority_counts": dict(priority_counts),
            "category_counts": dict(category_counts),
            "deadline_status_counts": dict(deadline_status_counts),
            "queue_state_counts": dict(queue_state_counts),
            "active_count": queue_state_counts.get("active", 0),
            "archived_count": queue_state_counts.get("archived", 0),
            "overdue_count": queue_state_counts.get("archived", 0),
            "needs_review_count": sum(1 for item in items if item["decision"]["queue_state"] == "active" and item["decision"]["needs_review"]),
            "deduplicated_count": len(raw_items) - len(items),
        },
        "items": items,
    }
    (output_dir / "triage.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    with (output_dir / "evidence.jsonl").open("w", encoding="utf-8") as handle:
        for item in items:
            handle.write(json.dumps({
                "candidate_id": item["candidate_id"],
                "message_ids": item["message_ids"],
                "preview": item["preview"],
                "judgments": item["judgments"],
                "decision": item["decision"],
            }, ensure_ascii=False) + "\n")
    write_markdown(result, output_dir / "attention.md")
    print(json.dumps({
        "status": "ok",
        "triage": str(output_dir / "triage.json"),
        "markdown": str(output_dir / "attention.md"),
        "candidate_count": len(items),
        "priority_counts": dict(priority_counts),
        "archive_range": [archive_start, archive_end],
        "message_date_filter": {
            "from": from_date.isoformat() if from_date else None,
            "to": to_date.isoformat() if to_date else None,
        },
        "all_mention_window_minutes": args.all_mention_window_minutes,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
