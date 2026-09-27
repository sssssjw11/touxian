"""Optional Windows WeChat exporters used by the local read-only bridge.

The current she-love-me workflow prefers a supported exporter (weflow-cli or
CipherTalk) and then normalizes its JSON output.  This module only discovers
and invokes an already installed exporter; it never sends a message or
automates a chat window.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


TYPE_MAP = {
    1: "text",
    3: "image",
    34: "voice",
    42: "card",
    43: "video",
    47: "emoji",
    48: "location",
    49: "link",
    50: "call",
    10000: "system",
    10002: "quote",
}

TEXT_TYPE_MAP = {
    "text": "text", "文本": "text", "文本消息": "text",
    "image": "image", "图片": "image", "图片消息": "image",
    "voice": "voice", "语音": "voice", "语音消息": "voice",
    "video": "video", "视频": "video", "视频消息": "video",
    "emoji": "emoji", "表情": "emoji", "动画表情": "emoji",
    "link": "link", "链接": "link", "链接消息": "link",
    "file": "file", "文件": "file", "文件消息": "file",
    "system": "system", "系统": "system", "系统消息": "system",
    "quote": "quote", "引用": "quote", "引用消息": "quote",
    "card": "card", "名片": "card", "location": "location", "位置": "location",
}

PLACEHOLDERS = {
    "image": "[图片]",
    "voice": "[语音消息]",
    "video": "[视频]",
    "emoji": "[表情]",
    "file": "[文件]",
    "card": "[名片]",
    "location": "[位置]",
    "call": "[通话]",
}


def _command_path(name: str) -> str | None:
    return shutil.which(name) or shutil.which(f"{name}.cmd")


def _safe_slug(value: str) -> str:
    text = re.sub(r"[\\/:*?\"<>|]+", "_", str(value or "").strip())
    text = re.sub(r"\s+", "_", text)
    return (text[:48] or "wechat_session").strip("._") or "wechat_session"


def _json_from_output(text: str) -> Any:
    """Decode JSON even when a CLI prefixes logs or progress lines."""
    text = str(text or "").strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char not in "[{":
            continue
        try:
            value, _ = decoder.raw_decode(text[index:])
            return value
        except json.JSONDecodeError:
            continue
    return None


def _first(value: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        item = value.get(key)
        if item not in (None, ""):
            return item
    return None


def _timestamp(value: Any) -> int:
    if value in (None, ""):
        return 0
    try:
        number = float(value)
        if number > 100_000_000_000:
            number /= 1000
        return int(number)
    except (TypeError, ValueError):
        pass
    text = str(value).strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())
    except ValueError:
        return 0


def _message_type(value: Any) -> str:
    if isinstance(value, bool):
        return "other"
    if isinstance(value, (int, float)):
        return TYPE_MAP.get(int(value), "other")
    text = str(value or "").strip().lower()
    if text.isdigit():
        return TYPE_MAP.get(int(text), "other")
    return TEXT_TYPE_MAP.get(text, "other")


def _content(value: Any, message_type: str) -> str:
    if isinstance(value, (dict, list)):
        text = json.dumps(value, ensure_ascii=False)
    else:
        text = str(value or "")
    return text or PLACEHOLDERS.get(message_type, "")


class ExternalWeChatExporter:
    """Discover and use an installed exporter without installing anything."""

    PROVIDER_ORDER = ("weflow-cli", "ciphertalk")

    def __init__(self, repo_root: Path, config: dict[str, Any] | None = None) -> None:
        self.repo_root = Path(repo_root)
        self.config = config or {}
        raw_dir = self.config.get("raw_export_dir") or os.environ.get("WECHAT_RAW_EXPORT_DIR")
        self.raw_dir = Path(raw_dir) if raw_dir else self.repo_root / "data" / "raw"
        self.last_error: str | None = None
        self._status_cache: tuple[float, dict[str, Any]] | None = None

    def _run(self, command: list[str], timeout: int = 60) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )

    def _provider_status(self, provider: str) -> dict[str, Any]:
        if provider == "weflow-cli":
            command_name = "weflow-cli"
        else:
            command_name = "miyu"
        executable = _command_path(command_name)
        node = _command_path("node")
        npm = _command_path("npm")
        report: dict[str, Any] = {
            "provider": provider,
            "command": Path(executable).name if executable else None,
            "installed": bool(executable),
            "node": bool(node),
            "npm": bool(npm),
            "ready": False,
            "supported_platform": sys.platform == "win32",
        }
        if executable:
            args = [executable, "--version"]
            if provider == "ciphertalk":
                args = [executable, "--format=json", "--quiet", "--version"]
            try:
                result = self._run(args, timeout=15)
                report["command_works"] = result.returncode == 0
                report["command_version"] = (result.stdout or result.stderr).strip()[:120]
            except (OSError, subprocess.SubprocessError) as exc:
                report["command_works"] = False
                report["error"] = str(exc)
        else:
            report["command_works"] = False
        report["ready"] = bool(
            report["supported_platform"]
            and report["installed"]
            and report["command_works"]
        )
        return report

    def status(self) -> dict[str, Any]:
        now = time.monotonic()
        if self._status_cache and now - self._status_cache[0] < 5:
            return dict(self._status_cache[1])
        preferred = str(
            self.config.get("wechat_exporter_provider")
            or os.environ.get("WECHAT_EXPORTER_PROVIDER")
            or "auto"
        ).strip().lower()
        order = [preferred] if preferred in self.PROVIDER_ORDER else list(self.PROVIDER_ORDER)
        attempts = [self._provider_status(provider) for provider in order]
        selected = next((item for item in attempts if item.get("ready")), None)
        result = {
            "strategy": "external-exporter-first",
            "provider": selected.get("provider") if selected else None,
            "ready": bool(selected),
            "attempts": attempts,
            "install_hint": "运行 scripts/setup_chat_exporter.py --provider auto --install",
        }
        self._status_cache = (now, result)
        return dict(result)

    def _selected(self) -> tuple[str, str] | None:
        status = self.status()
        provider = status.get("provider")
        command = _command_path("weflow-cli" if provider == "weflow-cli" else "miyu") if provider else None
        if provider and command:
            return str(provider), str(command)
        return None

    @staticmethod
    def _session_items(payload: Any) -> list[Any]:
        if isinstance(payload, list):
            return payload
        if isinstance(payload, dict):
            for key in ("sessions", "items", "conversations", "data"):
                value = payload.get(key)
                if isinstance(value, list):
                    return value
                if isinstance(value, dict):
                    nested = ExternalWeChatExporter._session_items(value)
                    if nested:
                        return nested
        return []

    def sessions(self, limit: int = 80) -> list[dict[str, Any]]:
        selected = self._selected()
        if not selected:
            return []
        provider, command = selected
        if provider == "weflow-cli":
            args = [command, "sessions", "-n", str(max(1, min(limit, 300)))]
        else:
            args = [command, "--format=json", "--quiet", f"--limit={max(1, min(limit, 300))}", "sessions"]
        try:
            result = self._run(args, timeout=90)
        except (OSError, subprocess.SubprocessError) as exc:
            self.last_error = str(exc)
            return []
        if result.returncode != 0:
            self.last_error = (result.stderr or result.stdout or "读取微信会话失败").strip()[-500:]
            return []
        payload = _json_from_output(result.stdout) or _json_from_output(result.stderr)
        values: list[dict[str, Any]] = []
        for raw in self._session_items(payload):
            if isinstance(raw, str):
                session_id, display = raw, raw
            elif isinstance(raw, dict):
                session_id = _first(raw, "sessionId", "session_id", "id", "username", "wxid")
                display = _first(raw, "displayName", "display_name", "remark", "nickname", "name")
            else:
                continue
            if not session_id:
                continue
            values.append({
                "username": str(session_id),
                "display_name": str(display or session_id),
                "source_kind": "wechat-exporter",
                "provider": provider,
                "has_messages": True,
                "message_count": None,
                "latest_timestamp": None,
                "latest_date": None,
                "is_group": True,
            })
        deduped: dict[str, dict[str, Any]] = {}
        for value in values:
            deduped[value["username"]] = value
        return list(deduped.values())[: max(1, min(limit, 300))]

    def _find_export_path(self, before: set[Path], started: float, output_hint: Any = None) -> Path | None:
        if output_hint:
            candidate = Path(str(output_hint))
            if not candidate.is_absolute():
                candidate = self.repo_root / candidate
            if candidate.is_file():
                return candidate
        candidates = []
        try:
            candidates = [path for path in self.raw_dir.glob("*.json") if path.is_file()]
        except OSError:
            candidates = []
        fresh = [path for path in candidates if path not in before or path.stat().st_mtime >= started - 1]
        return max(fresh or candidates, key=lambda path: path.stat().st_mtime, default=None)

    def export_raw(self, session_id: str) -> tuple[str, Path]:
        selected = self._selected()
        if not selected:
            raise RuntimeError("未检测到可用的微信导出器；请先安装并登录微信导出工具")
        provider, command = selected
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        before = set(self.raw_dir.glob("*.json"))
        started = time.time()
        output_hint: str | None = None
        if provider == "weflow-cli":
            args = [command, "export", str(session_id), "json", "--output", str(self.raw_dir)]
        else:
            output_hint = str(self.raw_dir / f"ciphertalk-{hashlib.md5(str(session_id).encode()).hexdigest()[:12]}.json")
            args = [command, "--format=json", "--quiet", "export", str(session_id), "--output", output_hint]
        try:
            result = self._run(args, timeout=600)
        except (OSError, subprocess.SubprocessError) as exc:
            raise RuntimeError(f"{provider} 导出失败：{exc}") from exc
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "导出失败").strip()[-800:]
            raise RuntimeError(f"{provider} 导出失败：{detail}")
        payload = _json_from_output(result.stdout) or _json_from_output(result.stderr)
        hinted = payload.get("output") if isinstance(payload, dict) else None
        path = self._find_export_path(before, started, output_hint or hinted)
        if path is None:
            raise RuntimeError(f"{provider} 已返回成功，但没有找到 JSON 导出文件")
        return provider, path

    @staticmethod
    def _raw_messages(data: Any) -> list[dict[str, Any]]:
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            for key in ("messages", "data", "items"):
                value = data.get(key)
                if isinstance(value, list):
                    return [item for item in value if isinstance(item, dict)]
                if isinstance(value, dict):
                    nested = ExternalWeChatExporter._raw_messages(value)
                    if nested:
                        return nested
        return []

    def normalize_export(
        self,
        raw_path: Path,
        contact: str,
        session_id: str,
        provider: str,
        output_root: Path,
    ) -> dict[str, Any]:
        try:
            data = json.loads(raw_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"无法读取导出 JSON：{exc}") from exc
        session = data.get("session") if isinstance(data, dict) else {}
        session = session if isinstance(session, dict) else {}
        display = str(_first(session, "remark", "nickname", "displayName", "name") or contact)
        own_wxid = str(_first(data, "own_wxid", "ownWxid", "selfWxid") or "unknown") if isinstance(data, dict) else "unknown"
        messages = []
        for index, raw in enumerate(self._raw_messages(data), start=1):
            local_id = _first(raw, "localId", "local_id", "id") or index
            local_type = _first(raw, "localType", "local_type", "type")
            message_type = _message_type(local_type)
            if isinstance(raw.get("type"), str) and not str(raw.get("type")).isdigit():
                message_type = _message_type(raw.get("type"))
            sent = raw.get("isSend", raw.get("is_send"))
            sender_name = _first(raw, "senderName", "sender_name", "senderNickName", "nickname", "displayName")
            sender_username = _first(raw, "senderUsername", "sender_username", "senderId", "sender_id")
            if sent in (1, True, "1", "true", "True"):
                sender = "me"
            else:
                sender = str(sender_name or sender_username or "them")
            content = _first(raw, "parsedContent", "content", "rawContent", "text")
            record: dict[str, Any] = {
                "local_id": local_id,
                "message_uid": str(_first(raw, "messageUid", "message_uid") or f"{provider}:{session_id}:{local_id}"),
                "timestamp": _timestamp(_first(raw, "createTime", "create_time", "timestamp", "time")),
                "sender": sender,
                "sender_username": str(sender_username or ""),
                "content": _content(content, message_type),
                "type": message_type,
                "local_type": local_type,
                "source_db": provider,
            }
            transcript = _first(raw, "transcript", "voiceTranscript", "voice_transcript", "recognitionText")
            if transcript:
                record["transcript"] = str(transcript)
            if isinstance(raw.get("file"), dict):
                record["file"] = raw["file"]
            messages.append(record)
        messages.sort(key=lambda item: (item["timestamp"], str(item["message_uid"])))
        bundle_dir = output_root / f"{_safe_slug(display)}__{hashlib.md5(session_id.encode()).hexdigest()[:8]}"
        bundle_dir.mkdir(parents=True, exist_ok=True)
        try:
            raw_display = str(raw_path.relative_to(self.repo_root))
        except ValueError:
            raw_display = raw_path.name
        source = {
            "contact_username": session_id,
            "contact_display": display,
            "own_wxid": own_wxid,
            "bundle_dir": str(bundle_dir),
            "total": len(messages),
            "file_total": sum(1 for item in messages if item.get("file")),
            "source_kind": "wechat-exporter",
            "provider": provider,
            "source_strategy": "external-exporter",
            "raw_export_path": raw_display,
            "messages": messages,
        }
        messages_path = bundle_dir / "messages.json"
        messages_path.write_text(json.dumps(source, ensure_ascii=False, indent=2), encoding="utf-8")
        return {
            "source": source,
            "messages_path": str(messages_path),
            "files_path": None,
            "file_count": source["file_total"],
            "message_count": len(messages),
            "files": [],
            "provider": provider,
            "raw_export_path": raw_display,
        }

    def export_session(self, session_id: str, contact: str, output_root: Path) -> dict[str, Any]:
        provider, raw_path = self.export_raw(session_id)
        return self.normalize_export(raw_path, contact, session_id, provider, output_root)
