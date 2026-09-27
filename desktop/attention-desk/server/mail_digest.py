"""把 Attention Desk 的分拣结果导出成 DDL 摘要邮件。

通过 ``agently-cli`` 发送，遵循它的两阶段确认协议：
``prepare()`` 拿到 ctk（确认令牌）并把预览交给用户，``send()`` 才真正投递。

边界：
- 只在本机执行 CLI，不上传数据到别处；调用方负责收件人来自用户。
- ``agently-cli`` 未安装或未授权时如实报错，不做静默降级。
- 发送内容只包含 DDL 与摘要，不添加任何 Agent 署名。
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from datetime import date, datetime
from html import escape
from pathlib import Path
from typing import Any

CLI_NAME = "agently-cli"
CLI_TIMEOUT = 90

# 优先级名称与排序权重（P0 最急）
PRIORITY_ORDER = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
PRIORITY_LABEL = {
    "P0": "P0 · 立即处理",
    "P1": "P1 · 尽快处理",
    "P2": "P2 · 关注",
    "P3": "P3 · 参考",
}
CATEGORY_LABEL = {
    "course": "课程安排",
    "assignment": "材料提交",
    "exam": "考试测验",
    "activity": "活动会议",
    "admin": "行政手续",
    "safety": "安全提醒",
    "employment": "实习就业",
    "resource": "资料分享",
    "noise": "噪声",
    "other": "其他",
}
# 截止状态 → 人话
DEADLINE_LABEL = {
    "overdue": "已逾期",
    "due_today": "今日截止",
    "near": "临近（1-3 天）",
    "ample": "时间宽裕",
    "unscheduled": "未排期",
    "reference": "日期参考",
    "unknown": "未识别",
}

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


class MailError(RuntimeError):
    """邮件链路错误，消息可直接展示给用户。"""


def cli_path() -> str | None:
    """定位 agently-cli：优先 PATH，其次 WorkBuddy 自带的 node 目录。"""
    found = shutil.which(CLI_NAME)
    if found:
        return found
    for root in (
        Path.home() / ".workbuddy" / "binaries" / "node" / "versions",
        Path(os.environ.get("APPDATA", "")) / "npm",
    ):
        if not root.exists():
            continue
        for candidate in sorted(root.glob(f"*/{CLI_NAME}*"), reverse=True):
            if candidate.is_file():
                return str(candidate)
    return None


def run_cli(args: list[str], timeout: int = CLI_TIMEOUT, cwd: str | Path | None = None) -> dict[str, Any]:
    """执行 agently-cli 并解析 JSON envelope。

    ``cwd`` 用于让 ``--body-file`` 等参数能传相对路径：CLI 明确拒绝绝对路径。
    """
    executable = cli_path()
    if not executable:
        raise MailError("未找到 agently-cli，请先安装：npm install -g @tencent-qqmail/agently-cli")
    try:
        completed = subprocess.run(
            [executable, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            cwd=str(cwd) if cwd else None,
        )
    except subprocess.TimeoutExpired as exc:
        raise MailError("邮件命令超时，请稍后重试") from exc
    except OSError as exc:
        raise MailError(f"无法执行邮件命令：{exc}") from exc

    stdout = (completed.stdout or "").strip()
    payload = _decode_envelope(stdout)

    if completed.returncode != 0:
        message = ""
        error = payload.get("error") if isinstance(payload, dict) else None
        if isinstance(error, dict):
            message = str(error.get("message") or "")
        if not message:
            message = (completed.stderr or stdout or "邮件命令执行失败").strip()[:300]
        raise MailError(_friendly_error(completed.returncode, message))
    if not payload:
        raise MailError("邮件命令没有返回可解析的结果")
    return payload


def _decode_envelope(stdout: str) -> dict[str, Any]:
    """从 CLI 输出里取出第一个 JSON 对象。

    CLI 把正文 JSON 写在 stdout、把 ``tip: ...`` 提示写在 stderr；但为防上游改动
    或个别子命令把提示混进 stdout，这里用 ``raw_decode`` 只吃第一个完整对象，
    忽略其后的任何尾随文本。
    """
    if not stdout:
        return {}
    start = stdout.find("{")
    if start < 0:
        return {}
    try:
        payload, _ = json.JSONDecoder().raw_decode(stdout[start:])
    except ValueError:
        return {}
    return payload if isinstance(payload, dict) else {}


def _friendly_error(code: int, message: str) -> str:
    hints = {
        3: "邮箱授权已失效，请重新运行 agently-cli auth login",
        6: "邮箱服务拒绝了这个请求",
        7: "触发邮箱发送频率限制，请稍后再试",
    }
    hint = hints.get(code)
    return f"{message}（{hint}）" if hint else message


def mail_status() -> dict[str, Any]:
    """报告邮件链路是否可用，供前端决定是否展示发送入口。"""
    executable = cli_path()
    if not executable:
        return {"ready": False, "reason": "未安装 agently-cli", "cli": None}
    try:
        payload = run_cli(["+me"], timeout=30)
    except MailError as exc:
        return {"ready": False, "reason": str(exc), "cli": executable}
    data = payload.get("data") or {}
    aliases = data.get("aliases") or []
    primary = next((a for a in aliases if a.get("is_primary")), aliases[0] if aliases else {})
    return {
        "ready": bool(primary.get("email")),
        "reason": "" if primary.get("email") else "已授权但未找到发件邮箱",
        "cli": executable,
        "sender": primary.get("email"),
        "sender_name": primary.get("name"),
        "daily_send_quota": (data.get("rate_limits") or {}).get("daily_send_quota"),
    }


def validate_recipients(raw: Any) -> list[str]:
    """校验并去重收件人地址。"""
    if isinstance(raw, str):
        candidates = re.split(r"[,;\s]+", raw)
    elif isinstance(raw, list):
        candidates = [str(item) for item in raw]
    else:
        candidates = []
    seen: list[str] = []
    for item in candidates:
        address = item.strip()
        if not address or address in seen:
            continue
        if not EMAIL_RE.match(address):
            raise MailError(f"收件人地址格式不正确：{address}")
        seen.append(address)
    if not seen:
        raise MailError("请至少填写一个收件人邮箱")
    if len(seen) > 20:
        raise MailError("单次收件人不要超过 20 个")
    return seen


# ------------------------------------------------------------------ 摘要渲染

def _deadline_of(item: dict[str, Any]) -> tuple[str | None, str]:
    """取出规范化截止时间与状态标签。"""
    judgment = (item.get("judgments") or {}).get("deadline") or {}
    value = judgment.get("value")
    if not isinstance(value, dict):
        return None, DEADLINE_LABEL["unknown"]
    normalized = value.get("normalized")
    status = item.get("decision", {}).get("deadline_status") or value.get("status") or "unknown"
    return normalized, DEADLINE_LABEL.get(status, status)


def _due_text(normalized: str | None, as_of: date | None) -> str:
    """把截止时间渲染成「2026-09-27 23:00（还有 2 天）」这种形式。"""
    if not normalized:
        return ""
    try:
        moment = datetime.fromisoformat(normalized)
    except ValueError:
        return str(normalized)
    text = moment.strftime("%Y-%m-%d %H:%M") if (moment.hour or moment.minute) else moment.strftime("%Y-%m-%d")
    if as_of:
        delta = (moment.date() - as_of).days
        if delta < 0:
            text += f"（已逾期 {abs(delta)} 天）"
        elif delta == 0:
            text += "（今天）"
        elif delta == 1:
            text += "（明天）"
        else:
            text += f"（还有 {delta} 天）"
    return text


def _evidence_snippet(item: dict[str, Any], limit: int = 90) -> str:
    """取一条证据消息作为摘要，去掉多余空白。"""
    evidence = item.get("evidence") or []
    if not evidence:
        return ""
    raw = str(evidence[0].get("content") or "").strip()
    raw = re.sub(r"\s+", " ", raw)
    if len(raw) > limit:
        raw = raw[:limit].rstrip() + "…"
    return raw


def _digest_parts(result: dict[str, Any], as_of: date | None) -> dict[str, Any]:
    """把分拣结果归一化成渲染所需的中间结构。

    ``build_digest``（纯文本）与 ``build_digest_html``（富文本）共用这里，
    保证两种格式的口径完全一致——尤其是「只统计进行中」这条。
    """
    source = result.get("source") or {}
    items = result.get("items") or []
    conversation = source.get("conversation") or "群消息"
    if as_of is None:
        raw_as_of = source.get("as_of")
        try:
            as_of = datetime.strptime(str(raw_as_of), "%Y-%m-%d").date()
        except (TypeError, ValueError):
            as_of = date.today()

    active = [item for item in items if (item.get("decision") or {}).get("queue_state", "active") == "active"]
    overdue = [item for item in active if (item.get("decision") or {}).get("deadline_status") == "overdue"]
    pending = [item for item in active if item not in overdue]

    def sort_key(item: dict[str, Any]):
        priority = (item.get("decision") or {}).get("priority", "P3")
        normalized, _ = _deadline_of(item)
        return (
            PRIORITY_ORDER.get(priority, 9),
            normalized or "9999",
        )

    pending.sort(key=sort_key)

    # 按「进行中」事项统计，避免和已归档项的数字混在一起
    active_counts: dict[str, int] = {}
    for item in active:
        key = (item.get("decision") or {}).get("priority", "P3")
        active_counts[key] = active_counts.get(key, 0) + 1

    return {
        "conversation": conversation,
        "source": source,
        "as_of": as_of,
        "active": active,
        "overdue": overdue,
        "pending": pending,
        "active_counts": active_counts,
    }


def _subject_for(parts: dict[str, Any]) -> str:
    conversation = parts["conversation"]
    overdue = parts["overdue"]
    active = parts["active"]
    overdue_tag = f"含逾期 {len(overdue)} 条 · " if overdue else ""
    if active:
        return f"【待办提醒】{conversation} · {overdue_tag}{len(active)} 条进行中"
    return f"【待办提醒】{conversation} · 暂无进行中事项"


def build_digest(
    result: dict[str, Any],
    recipients: list[str],
    as_of: date | None = None,
    sender: str | None = None,
) -> dict[str, str]:
    """把分拣结果渲染成邮件主题与正文（纯文本）。

    正文结构：概览 → 已逾期单独提示 → 按优先级分组的待办清单（附证据摘要）。
    只列进行中的事项，归档项不进入邮件。
    """
    parts = _digest_parts(result, as_of)
    source = parts["source"]
    as_of = parts["as_of"]
    active = parts["active"]
    overdue = parts["overdue"]
    pending = parts["pending"]
    active_counts = parts["active_counts"]
    conversation = parts["conversation"]

    lines: list[str] = []
    lines.append(f"来源：{conversation}")
    lines.append(f"归档区间：{source.get('archive_start') or '—'} ~ {source.get('archive_end') or '—'}")
    lines.append(f"判断基准日：{as_of.isoformat()}")
    lines.append("")

    counts_text = "、".join(
        f"{key} {active_counts[key]} 条" for key in sorted(active_counts, key=lambda k: PRIORITY_ORDER.get(k, 9))
    ) or "无"
    lines.append(f"进行中事项 {len(active)} 条（{counts_text}）")
    lines.append(f"其中已逾期 {len(overdue)} 条")
    lines.append("")

    if overdue:
        lines.append("=" * 28)
        lines.append("【已逾期 · 请尽快确认】")
        lines.append("=" * 28)
        for index, item in enumerate(overdue, start=1):
            lines.extend(_item_lines(index, item, as_of))
        lines.append("")

    if pending:
        lines.append("=" * 28)
        lines.append("【按优先级排序的待办】")
        lines.append("=" * 28)
        current_priority = None
        counter = 0
        for item in pending:
            priority = (item.get("decision") or {}).get("priority", "P3")
            if priority != current_priority:
                current_priority = priority
                counter = 0
                lines.append("")
                lines.append(f"—— {PRIORITY_LABEL.get(priority, priority)} ——")
            counter += 1
            lines.extend(_item_lines(counter, item, as_of))
        lines.append("")

    if not active:
        lines.append("当前没有进行中的待办事项。")
        lines.append("")

    lines.append("-" * 28)
    lines.append(f"本邮件由 Attention Desk 生成（{datetime.now().strftime('%Y-%m-%d %H:%M')}）。")
    lines.append("只包含截止时间与摘要，原文请回工作台查看。")

    body = "\n".join(lines)
    return {"subject": _subject_for(parts), "body": body, "conversation": conversation}


def _item_lines(index: int, item: dict[str, Any], as_of: date | None) -> list[str]:
    decision = item.get("decision") or {}
    judgments = item.get("judgments") or {}
    title = _clean_title(item.get("preview") or item.get("title") or "（无标题）")
    category = CATEGORY_LABEL.get((judgments.get("category") or {}).get("value"), "")
    priority = decision.get("priority", "P3")
    normalized, status_label = _deadline_of(item)

    lines = [f"{index}. [{priority}] {title}"]
    meta = []
    if category:
        meta.append(category)
    if normalized:
        meta.append(f"截止 {_due_text(normalized, as_of)}")
    else:
        meta.append(f"截止 {status_label}")
    if decision.get("needs_review"):
        meta.append("待人工复核")
    lines.append(f"   {' · '.join(meta)}")

    snippet = _evidence_snippet(item)
    if snippet:
        quote = "\n   > ".join(_wrap(snippet, 60))
        lines.append(f"   > {quote}")
    lines.append("")
    return lines


def _clean_title(value: str) -> str:
    """把预览压成一行标题，去掉换行与多余空格。"""
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    text = text.strip("。，, ")
    return text[:80] + "…" if len(text) > 80 else (text or "（无标题）")


def _wrap(text: str, width: int) -> list[str]:
    return [text[i:i + width] for i in range(0, len(text), width)] or [""]


# ------------------------------------------------------------------ HTML 渲染

# 卡片式排版用的配色，与工作台保持同一套视觉语言
HTML_PRIORITY_STYLE = {
    "P0": {"accent": "#e3232d", "soft": "#fbe7e7", "border": "#e3232d"},
    "P1": {"accent": "#c2410c", "soft": "#fff1e6", "border": "#e8a06a"},
    "P2": {"accent": "#2359d7", "soft": "#e8efff", "border": "#8fb0f0"},
    "P3": {"accent": "#62625d", "soft": "#f1f0ea", "border": "#cfcec6"},
}

FONT_STACK = (
    "-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',"
    "'Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif"
)


def _escape(value: Any, quote: bool = True) -> str:
    """转义 HTML 特殊字符，防止聊天原文里的尖括号破坏邮件结构。"""
    return escape(str(value if value is not None else ""), quote=quote)


def _due_html(normalized: str | None, as_of: date | None) -> str:
    """把截止时间渲染成带倒计时着色的 HTML 片段。"""
    if not normalized:
        return _escape(DEADLINE_LABEL["unknown"])
    try:
        moment = datetime.fromisoformat(normalized)
    except ValueError:
        return _escape(normalized)
    stamp = moment.strftime("%Y-%m-%d %H:%M") if (moment.hour or moment.minute) else moment.strftime("%Y-%m-%d")
    label = _escape(stamp)
    if not as_of:
        return label
    delta = (moment.date() - as_of).days
    if delta < 0:
        extra, color = f"已逾期 {abs(delta)} 天", "#e3232d"
    elif delta == 0:
        extra, color = "今天", "#e3232d"
    elif delta == 1:
        extra, color = "明天", "#c2410c"
    else:
        extra, color = f"还有 {delta} 天", "#62625d"
    return (
        f'{label}<span style="color:{color};font-weight:600">'
        f'（{_escape(extra)}）</span>'
    )


def _evidence_html(item: dict[str, Any], limit: int = 140) -> str:
    """证据原文摘要，比纯文本版放宽一些（HTML 换行不占位）。"""
    evidence = item.get("evidence") or []
    if not evidence:
        return ""
    raw = re.sub(r"\s+", " ", str(evidence[0].get("content") or "")).strip()
    if len(raw) > limit:
        raw = raw[:limit].rstrip() + "…"
    return _escape(raw)


def _item_card_html(index: int, item: dict[str, Any], as_of: date | None) -> str:
    """单条事项卡片。"""
    decision = item.get("decision") or {}
    judgments = item.get("judgments") or {}
    title = _clean_title(item.get("preview") or item.get("title") or "（无标题）")
    category = CATEGORY_LABEL.get((judgments.get("category") or {}).get("value"), "")
    priority = decision.get("priority", "P3")
    tone = HTML_PRIORITY_STYLE.get(priority, HTML_PRIORITY_STYLE["P3"])
    normalized, status_label = _deadline_of(item)

    meta_bits: list[str] = []
    if category:
        meta_bits.append(
            f'<span style="display:inline-block;padding:2px 8px;margin-right:6px;'
            f'background:{tone["soft"]};color:{tone["accent"]};'
            f'font-size:12px;border-radius:10px">{_escape(category)}</span>'
        )
    deadline_text = _due_html(normalized, as_of) if normalized else _escape(status_label)
    meta_bits.append(
        f'<span style="color:#62625d;font-size:12px">截止 {deadline_text}</span>'
    )
    if decision.get("needs_review"):
        meta_bits.append(
            '<span style="display:inline-block;padding:2px 8px;margin-left:6px;'
            'background:#fff6cc;color:#8a6d00;font-size:11px;border-radius:10px">待人工复核</span>'
        )

    snippet = _evidence_html(item)
    quote_html = ""
    if snippet:
        quote_html = (
            '<div style="margin:10px 0 0;padding:9px 12px;background:#fafaf7;'
            'border-left:3px solid #cfcec6;color:#62625d;font-size:12px;'
            f'line-height:1.6">{snippet}</div>'
        )

    return (
        f'<tr><td style="padding:0 0 10px">'
        f'<div style="border:1px solid {tone["border"]};border-radius:6px;'
        f'background:#ffffff;padding:12px 14px">'
        f'<div style="font-size:14px;font-weight:700;color:#111111;'
        f'line-height:1.5">{_escape(index)}. {_escape(title)}</div>'
        f'<div style="margin:6px 0 0">{ "".join(meta_bits) }</div>'
        f'{quote_html}'
        f'</div></td></tr>'
    )


def build_digest_html(
    result: dict[str, Any],
    recipients: list[str],
    as_of: date | None = None,
    sender: str | None = None,
) -> dict[str, str]:
    """把分拣结果渲染成卡片式 HTML 邮件。

    与 ``build_digest`` 共用 ``_digest_parts``，两者内容口径一致，
    只是视觉表达不同。用 table 布局 + 内联样式，兼容各邮件客户端。
    """
    parts = _digest_parts(result, as_of)
    source = parts["source"]
    as_of = parts["as_of"]
    active = parts["active"]
    overdue = parts["overdue"]
    pending = parts["pending"]
    active_counts = parts["active_counts"]
    conversation = parts["conversation"]
    total = len(active)

    # ---------------- 顶部概览 ----------------
    counts_html = " &nbsp;".join(
        f'<span style="display:inline-block;padding:2px 9px;border-radius:10px;'
        f'background:{HTML_PRIORITY_STYLE.get(k, HTML_PRIORITY_STYLE["P3"])["soft"]};'
        f'color:{HTML_PRIORITY_STYLE.get(k, HTML_PRIORITY_STYLE["P3"])["accent"]};'
        f'font-size:12px;font-weight:600">{_escape(k)} {active_counts[k]}</span>'
        for k in sorted(active_counts, key=lambda x: PRIORITY_ORDER.get(x, 9))
    ) or '<span style="color:#62625d;font-size:12px">无进行中事项</span>'

    header_block = (
        f'<div style="background:#111111;border-radius:8px 8px 0 0;padding:18px 20px">'
        f'<div style="color:#ffffff;font-size:11px;letter-spacing:1px;'
        f'opacity:.75">ATTENTION DESK · 待办提醒</div>'
        f'<div style="color:#ffffff;font-size:19px;font-weight:700;'
        f'margin:6px 0 0;line-height:1.4">{_escape(conversation)}</div>'
        f'<div style="color:#ffffff;font-size:12px;opacity:.7;margin:8px 0 0">'
        f'判断基准日 {_escape(as_of.isoformat())} · 归档区间 '
        f'{_escape(source.get("archive_start") or "—")} ~ {_escape(source.get("archive_end") or "—")}'
        f'</div></div>'
    )

    stat_block = (
        f'<div style="background:#f3f2ec;padding:14px 20px;border:1px solid #e8e7e0;'
        f'border-top:none">'
        f'<div style="font-size:20px;font-weight:700;color:#111111">'
        f'{total}<span style="font-size:13px;font-weight:400;color:#62625d"> 条进行中</span>'
        + (
            f'<span style="margin-left:12px;font-size:13px;color:#e3232d;font-weight:600">'
            f'{len(overdue)} 条已逾期</span>'
            if overdue else ""
        )
        + f'</div>'
        f'<div style="margin:10px 0 0">{counts_html}</div>'
        f'</div>'
    )

    # ---------------- 逾期警示 ----------------
    overdue_block = ""
    if overdue:
        cards = "".join(_item_card_html(i, item, as_of) for i, item in enumerate(overdue, start=1))
        overdue_block = (
            f'<tr><td style="padding:20px 20px 4px">'
            f'<div style="background:#fbe7e7;border:1px solid #e3232d;border-radius:6px;'
            f'padding:10px 14px;color:#e3232d;font-size:14px;font-weight:700">'
            f'⚠ 已逾期 · 请尽快确认</div>'
            f'</td></tr>'
            f'<tr><td style="padding:10px 20px 0">'
            f'<table style="width:100%;border-collapse:collapse">{cards}</table>'
            f'</td></tr>'
        )

    # ---------------- 按优先级分组 ----------------
    pending_block = ""
    if pending:
        chunks: list[str] = []
        current = None
        counter = 0
        for item in pending:
            priority = (item.get("decision") or {}).get("priority", "P3")
            if priority != current:
                if current is not None:
                    chunks.append("</table></td></tr>")
                current = priority
                counter = 0
                tone = HTML_PRIORITY_STYLE.get(priority, HTML_PRIORITY_STYLE["P3"])
                chunks.append(
                    f'<tr><td style="padding:18px 20px 6px">'
                    f'<span style="display:inline-block;padding:3px 12px;border-radius:12px;'
                    f'background:{tone["soft"]};color:{tone["accent"]};'
                    f'font-size:12px;font-weight:700">'
                    f'{_escape(PRIORITY_LABEL.get(priority, priority))}</span>'
                    f'</td></tr>'
                    f'<tr><td style="padding:2px 20px 0">'
                    f'<table style="width:100%;border-collapse:collapse">'
                )
            counter += 1
            chunks.append(_item_card_html(counter, item, as_of))
        chunks.append("</table></td></tr>")
        pending_block = "".join(chunks)

    empty_block = ""
    if not active:
        empty_block = (
            '<tr><td style="padding:24px 20px">'
            '<div style="text-align:center;color:#62625d;font-size:14px;'
            'padding:24px;border:1px dashed #cfcec6;border-radius:6px">'
            '当前没有进行中的待办事项</div></td></tr>'
        )

    footer_block = (
        f'<tr><td style="padding:16px 20px 20px">'
        f'<div style="border-top:1px solid #e8e7e0;padding-top:12px;'
        f'color:#8a8982;font-size:11px;line-height:1.7">'
        f'本邮件由 Attention Desk 生成（{_escape(datetime.now().strftime("%Y-%m-%d %H:%M"))}）。<br>'
        f'只包含截止时间与摘要，原文请回工作台查看。'
        f'</div></td></tr>'
    )

    body = (
        f'<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">'
        f'<meta name="viewport" content="width=device-width,initial-scale=1">'
        f'<title>{_escape(_subject_for(parts))}</title></head>'
        f'<body style="margin:0;padding:0;background:#e8e7e0">'
        f'<table role="presentation" width="100%" cellpadding="0" cellspacing="0" '
        f'style="background:#e8e7e0;padding:20px 0">'
        f'<tr><td align="center">'
        f'<table role="presentation" width="640" cellpadding="0" cellspacing="0" '
        f'style="width:640px;max-width:96%;background:#ffffff;border-radius:8px;'
        f'overflow:hidden;font-family:{FONT_STACK};box-shadow:0 1px 4px rgba(0,0,0,.08)">'
        f'<tr><td>{header_block}{stat_block}</td></tr>'
        f'{overdue_block}'
        f'{pending_block}'
        f'{empty_block}'
        f'{footer_block}'
        f'</table></td></tr></table></body></html>'
    )
    return {
        "subject": _subject_for(parts),
        "body": body,
        "html": body,
        "conversation": conversation,
    }


# ------------------------------------------------------------------ 两阶段发送

def prepare_send(
    result: dict[str, Any],
    recipients: Any,
    as_of: date | None = None,
    body_format: str = "html",
) -> dict[str, Any]:
    """第一阶段：渲染摘要并拿到确认令牌，不发送。

    ``body_format`` 取 ``"html"``（卡片式富文本）或 ``"text"``（纯文本降级）。
    """
    addresses = validate_recipients(recipients)
    status = mail_status()
    if not status.get("ready"):
        raise MailError(status.get("reason") or "邮件链路未就绪")

    use_html = str(body_format).strip().lower() != "text"
    digest = (
        build_digest_html(result, addresses, as_of, sender=status.get("sender"))
        if use_html
        else build_digest(result, addresses, as_of, sender=status.get("sender"))
    )
    body_path = _write_body_file(digest["body"], "html" if use_html else "txt")

    args = [
        "message", "+send",
        "--to", ",".join(addresses),
        "--subject", digest["subject"],
        # CLI 只接受相对路径，配合 cwd=OUTBOX 使用
        "--body-file", body_path.name,
    ]
    payload = run_cli(args, cwd=body_path.parent)
    data = payload.get("data") or {}
    token = data.get("confirmation_token") or data.get("confirmationToken")
    if not token:
        raise MailError("邮件服务没有返回确认令牌，无法进入确认流程")
    return {
        "confirmation_token": token,
        "summary": data.get("summary") or {},
        "recipients": addresses,
        "subject": digest["subject"],
        "body": digest["body"],
        "body_format": "html" if use_html else "text",
        "sender": status.get("sender"),
        "body_file": str(body_path),
    }


def send_confirmed(
    token: str,
    recipients: Any,
    subject: str,
    body: str,
    body_format: str = "html",
) -> dict[str, Any]:
    """第二阶段：带确认令牌真正发出。"""
    addresses = validate_recipients(recipients)
    if not str(token).startswith("ctk_"):
        raise MailError("确认令牌无效，请重新生成预览")
    if not str(subject).strip():
        raise MailError("邮件主题不能为空")
    if not str(body).strip():
        raise MailError("邮件正文不能为空")

    use_html = str(body_format).strip().lower() != "text"
    body_path = _write_body_file(body, "html" if use_html else "txt")
    payload = run_cli([
        "message", "+send",
        "--to", ",".join(addresses),
        "--subject", subject,
        "--body-file", body_path.name,
        "--confirmation-token", str(token),
    ], cwd=body_path.parent)
    data = payload.get("data") or {}
    return {
        "sent": True,
        "queued": bool(data.get("queued", True)),
        "message_id": data.get("message_id") or data.get("messageId"),
        "recipients": addresses,
        "subject": subject,
    }


OUTBOX = Path(__file__).resolve().parents[2] / "data" / "attention-desk" / "mail"


def _write_body_file(body: str, suffix: str = "txt") -> Path:
    """正文写本地文件后交给 CLI（--body-file 避免命令行转义问题）。

    后缀决定 CLI 是否按 HTML 渲染：``.html`` 走富文本，``.txt`` 走纯文本。
    """
    OUTBOX.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    path = OUTBOX / f"digest-{stamp}.{suffix}"
    path.write_text(body, encoding="utf-8")
    return path
