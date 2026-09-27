"""微信「聊天记录」压缩包解析器。

第三方链路：把本机微信「导出聊天记录」得到的 zip 解成 Attention Desk
可以直接消费的 ``messages.json`` 结构。

设计边界（与 ``wechat_bridge`` 的本机链路完全独立，只读、不触碰微信）：

- 输入是**用户主动导出并上传**的 zip；本模块只解析内存里的字节，
  不扫描磁盘、不读解密库、不发送任何请求。
- zip 内文件名的编码不确定：Windows 资源管理器压缩的中文名通常以
  cp437 存放实为 GBK 的字节，需要尝试多种编码还原。
- 文本格式形如::

      ·发送者
      2026年9月23日 07:52
      正文（可多行）

- 附件在同级目录 ``聊天记录内的图片、视频和文件/``，按文件名与
  ``[文件] xxx`` / ``[图片] xxx`` 标记对应。

产出遵循仓库既有的消息契约：``local_id`` / ``message_uid`` / ``sender``
/ ``timestamp`` / ``type`` / ``content``，并额外附 ``file`` 元数据。
"""

from __future__ import annotations

import hashlib
import io
import re
import zipfile
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

# 聊天记录.txt 的候选名称（不同版本导出器命名略有出入）
CHAT_TEXT_NAMES = ("聊天记录.txt", "聊天记录.TXT", "chat.txt", "聊天记录")
# 附件目录的候选前缀
ATTACHMENT_DIR_HINTS = ("聊天记录内的图片", "图片、视频和文件", "聊天记录内的文件")

# 「·发送者」行：行首的点可能是 · ・ ． 或 ·
SENDER_LINE = re.compile(r"^[·・．\u00b7\u2022]\s*(?P<sender>.+?)\s*$")
# 「2026年9月23日 07:52」/「2026-09-23 07:52」/「2026/9/23 7:52」
DATETIME_LINE = re.compile(
    r"^(?P<year>\d{4})\s*[年\-/.]\s*(?P<month>\d{1,2})\s*[月\-/.]\s*(?P<day>\d{1,2})\s*日?"
    r"(?:\s+(?P<hour>\d{1,2}):(?P<minute>\d{1,2})(?::(?P<second>\d{1,2}))?)?\s*$"
)
# 纯时间行（部分导出器只在会话首条给出日期，之后只给时间）
TIME_ONLY_LINE = re.compile(r"^(?P<hour>\d{1,2}):(?P<minute>\d{1,2})(?::(?P<second>\d{1,2}))?\s*$")
# 消息类型标记：既可像真实导出那样独占一行，也可与内容同行
MARKER_LINE = re.compile(
    r"^\[(?P<kind>文件|图片|视频|语音|链接|小程序|表情|动画表情|位置|名片|转账|红包|音乐|聊天记录|引用)\]\s*(?P<body>.*)$"
)

FILE_ID_HINTS = {"文件", "图片", "视频", "语音", "聊天记录", "音乐"}
IMAGE_ID_HINTS = {"图片", "表情", "动画表情"}
MEDIA_MARKER_KINDS = FILE_ID_HINTS | IMAGE_ID_HINTS | {"视频", "语音"}

# 文件名里不能出现的字符（用于安全落盘）
_UNSAFE_NAME = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


class ChatArchiveError(RuntimeError):
    """压缩包无法解析成聊天记录。"""


def _decode_zip_name(raw: bytes) -> str:
    """zip 条目名编码还原：cp437→GBK 是 Windows 压缩中文名的常见形态。

    先试 UTF-8；不成立时按原始字节走 cp437→GBK。注意 zipfile 已经用 cp437
    把字节解成了字符串，所以这里必须用 ``info.orig_filename``（保留原始字节），
    而不是对已解码字符串再 encode——那一步会丢字节。
    """
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    for encoding in ("gbk", "cp936", "big5"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def _entry_display_name(info: zipfile.ZipInfo) -> str:
    """还原条目名，兼容 zip 库已按 cp437 解出的情形。"""
    if info.flag_bits & 0x800:
        # 明确标记 UTF-8，zipfile 已正确解码
        return info.filename
    raw = getattr(info, "orig_filename", None)
    if isinstance(raw, bytes):
        return _decode_zip_name(raw)
    # 回退：把 cp437 解出的字符串还原成字节（不丢字节的前提是全部可映射）
    try:
        return _decode_zip_name(info.filename.encode("cp437"))
    except UnicodeEncodeError:
        return info.filename


def _read_text(raw: bytes) -> str:
    """聊天记录通常是 UTF-8，少量导出器用 GBK。"""
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig")
    for encoding in ("utf-8", "gbk", "cp936"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def _clean_content(lines: list[str]) -> str:
    """去掉首尾空行，压缩连续空行，保留段内换行。"""
    text = "\n".join(lines).strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def parse_chat_text(text: str, base_year: int | None = None) -> tuple[list[dict[str, Any]], str]:
    """把聊天记录文本解析成消息列表。

    返回 ``(messages, contact_display)``。时间戳缺失时用上一条消息的时间兜底，
    始终无法确定时间的消息会被丢弃（分拣依赖时间排序）。
    """
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    messages: list[dict[str, Any]] = []
    contact = "聊天记录"
    current_sender: str | None = None
    current_time: datetime | None = None
    buffer: list[str] = []
    last_dt: datetime | None = None
    seq = 0

    def flush() -> None:
        nonlocal buffer, current_sender, current_time
        content = _clean_content(buffer)
        buffer = []
        if current_sender is None or current_time is None or not content:
            return
        nonlocal seq
        seq += 1
        kind, body, file_name = classify_content(content)
        message: dict[str, Any] = {
            "local_id": seq,
            "message_uid": f"zip-{seq}",
            "sender": current_sender,
            "timestamp": int(current_time.timestamp()),
            "type": kind,
            "content": body if kind != "text" else content,
            "source": "chat-archive",
        }
        if file_name:
            message["file_name"] = file_name
        messages.append(message)

    for index, raw_line in enumerate(lines):
        line = raw_line.rstrip()
        if index == 0:
            # 首行常是群名
            stripped = line.strip()
            if stripped and not SENDER_LINE.match(stripped) and not DATETIME_LINE.match(stripped):
                contact = stripped
                continue

        sender_match = SENDER_LINE.match(line)
        dt_match = DATETIME_LINE.match(line.strip())
        time_match = None if dt_match else TIME_ONLY_LINE.match(line.strip())

        if not line.strip():
            buffer.append("")
            continue

        if sender_match and not dt_match:
            # 发送者行只在「下一条是时间行」时才成立，避免把正文里的 · 列表项误判
            lookahead = _next_nonempty(lines, index + 1)
            if lookahead is not None and (
                DATETIME_LINE.match(lookahead) or TIME_ONLY_LINE.match(lookahead)
            ):
                flush()
                current_sender = sender_match.group("sender")
                continue
            buffer.append(line)
            continue

        if dt_match:
            flush()
            current_time = _build_datetime(dt_match, base_year)
            last_dt = current_time
            continue

        if time_match:
            hour = int(time_match.group("hour"))
            minute = int(time_match.group("minute"))
            second = int(time_match.group("second") or 0)
            anchor = last_dt or datetime.now()
            candidate = anchor.replace(hour=hour, minute=minute, second=second, microsecond=0)
            if candidate < anchor - timedelta(hours=1):
                candidate += timedelta(days=1)
            current_time = candidate
            last_dt = candidate
            # 纯时间行前若有正文，说明是新消息起点
            if buffer and current_sender is not None:
                flush()
            continue

        buffer.append(line)

    flush()
    return messages, contact


def _next_nonempty(lines: list[str], start: int) -> str | None:
    for cursor in range(start, min(start + 3, len(lines))):
        candidate = lines[cursor].strip()
        if candidate:
            return candidate
    return None


def _build_datetime(match: re.Match[str], base_year: int | None) -> datetime:
    year = int(match.group("year")) if match.group("year") else (base_year or datetime.now().year)
    return datetime(
        year,
        int(match.group("month")),
        int(match.group("day")),
        int(match.group("hour") or 0),
        int(match.group("minute") or 0),
        int(match.group("second") or 0),
    )


def classify_content(content: str) -> tuple[str, str, str | None]:
    """识别消息类型，返回 ``(type, content, file_name)``。

    与 ``wechat_bridge`` 的映射保持一致：``[链接] xxx url`` → ``link``，
    ``[文件] xxx`` → ``file``，其余非文本标记 → ``system``。
    """
    first_line = content.splitlines()[0].strip() if content else ""
    marker = MARKER_LINE.match(first_line)
    if not marker:
        return "text", content, None

    kind = marker.group("kind")
    body = marker.group("body").strip()
    rest = content.splitlines()[1:]

    if kind == "链接":
        url = _first_url(body) or _first_url("\n".join(rest))
        title = body or (rest[0].strip() if rest else "链接")
        if url:
            return "link", f"{title} {url}".strip(), None
        return "text", content, None

    # 图片类标记要先判，否则会被 FILE_ID_HINTS 的兜底分支吃掉
    if kind in IMAGE_ID_HINTS:
        return "image", content, (body or None)

    if kind in FILE_ID_HINTS:
        name = body or (rest[0].strip() if rest else "")
        return ("file" if kind == "文件" else "system"), content, name or None

    return "system", content, None


def _first_url(value: str) -> str | None:
    match = re.search(r"https?://\S+", value or "")
    return match.group(0) if match else None


def safe_filename(name: str, fallback: str) -> str:
    """把附件名规整成可安全落盘的文件名。"""
    cleaned = _UNSAFE_NAME.sub("_", (name or "").strip()).strip(". ")
    if not cleaned:
        cleaned = fallback
    return cleaned[:120]


def parse_archive(data: bytes, base_year: int | None = None) -> dict[str, Any]:
    """解析压缩包字节流，返回 source bundle 与待落盘附件清单。

    ``source`` 结构可直接喂给 Attention Desk 的 ``prepare_source``：
    含 ``contact_display`` / ``source_kind`` / ``messages``。
    """
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as exc:
        raise ChatArchiveError("不是有效的 zip 压缩包") from exc

    entries: list[tuple[zipfile.ZipInfo, str]] = []
    for info in archive.infolist():
        if info.is_dir():
            continue
        display = _entry_display_name(info)
        # 防目录穿越：只保留相对路径中安全的部分
        parts = [part for part in re.split(r"[\\/]+", display) if part not in ("", ".", "..")]
        if not parts:
            continue
        entries.append((info, "/".join(parts)))

    if not entries:
        raise ChatArchiveError("压缩包里没有文件")

    text_entry = _pick_text_entry(entries)
    if text_entry is None:
        raise ChatArchiveError("压缩包里找不到聊天记录文本（如「聊天记录.txt」）")

    raw_text = archive.read(text_entry[0])
    text = _read_text(raw_text)
    messages, contact = parse_chat_text(text, base_year)

    attachments = _collect_attachments(archive, entries, text_entry[1], messages)
    _attach_files_to_messages(messages, attachments)

    if not messages:
        raise ChatArchiveError("聊天记录里没有解析出任何带时间的消息")

    resolved = sum(1 for item in attachments if item["data"] is not None)
    return {
        "source": {
            "contact_display": contact,
            "contact_username": "",
            "source_kind": "archive-upload",
            "source_strategy": "chat-archive-zip",
            "messages": messages,
        },
        "attachments": attachments,
        "summary": {
            "message_count": len(messages),
            "attachment_count": len(attachments),
            "resolved_attachment_count": resolved,
            "archive_start": _iso_date(min((m["timestamp"] for m in messages), default=None)),
            "archive_end": _iso_date(max((m["timestamp"] for m in messages), default=None)),
        },
    }


def _pick_text_entry(entries: list[tuple[zipfile.ZipInfo, str]]) -> tuple[zipfile.ZipInfo, str] | None:
    """选出聊天记录文本条目：优先同名 .txt，其次根目录下唯一的 txt。"""
    candidates = [(info, name) for info, name in entries if name.lower().endswith(".txt")]
    for info, name in candidates:
        if Path(name).name in CHAT_TEXT_NAMES:
            return info, name
    if candidates:
        # 附件目录里的 txt 不算聊天记录，优先取层级最浅的
        candidates.sort(key=lambda item: (item[1].count("/"), len(item[1])))
        shallowest = candidates[0]
        if shallowest[1].count("/") == 0:
            return shallowest
        return candidates[0] if len(candidates) == 1 else shallowest
    return None


def _collect_attachments(
    archive: zipfile.ZipFile,
    entries: list[tuple[zipfile.ZipInfo, str]],
    text_name: str,
    messages: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """把附件目录里的文件读出来，与消息里的 ``[文件]`` 标记按文件名对应。"""
    text_dir = Path(text_name).parent
    wanted: dict[str, int] = {}
    for index, message in enumerate(messages):
        name = message.pop("file_name", None)
        if name:
            wanted.setdefault(Path(name).name, index)

    attachments: list[dict[str, Any]] = []
    for info, name in entries:
        if name == text_name:
            continue
        path = Path(name)
        base = path.name
        in_attachment_dir = any(hint in name for hint in ATTACHMENT_DIR_HINTS)
        if not in_attachment_dir and path.parent == text_dir:
            # 根目录下的杂项文件不算附件
            continue
        data = None
        try:
            data = archive.read(info)
        except (zipfile.BadZipFile, RuntimeError):
            data = None
        size = info.file_size or (len(data) if data is not None else 0)
        attachments.append({
            "name": base,
            "path": name,
            "extension": path.suffix.lower().lstrip("."),
            "size": size,
            "md5": hashlib.md5(data).hexdigest() if data else None,
            "data": data,
            "message_index": wanted.get(base),
        })
    return attachments


def _attach_files_to_messages(messages: list[dict[str, Any]], attachments: list[dict[str, Any]]) -> None:
    """补充 ``file`` 元数据，让分拣脚本能识别附件证据。"""
    for item in attachments:
        index = item["message_index"]
        if index is None or index >= len(messages):
            continue
        message = messages[index]
        if isinstance(message.get("file"), dict):
            continue
        message["file"] = {
            "name": item["name"],
            "extension": item["extension"],
            "size": item["size"],
            "md5": item["md5"],
        }
        message.setdefault("type", "file")


def _iso_date(timestamp: int | None) -> str | None:
    if not timestamp:
        return None
    return datetime.fromtimestamp(timestamp).date().isoformat()
