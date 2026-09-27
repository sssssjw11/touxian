"""Read-only bridge from the local decrypted WeChat SQLite archive.

The bridge intentionally does not automate WeChat or send anything back to a
chat.  It only reads contact/message tables that are already present under the
repository's ``vendor/wechat-decrypt`` directory and converts one selected
group into the workbench ``messages.json`` contract.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET

try:
    import zstandard as zstd
except ImportError:  # pragma: no cover - surfaced through status()
    zstd = None

try:
    from .wechat_exporter import ExternalWeChatExporter
except ImportError:  # pragma: no cover - direct module execution fallback
    from wechat_exporter import ExternalWeChatExporter


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "vendor" / "wechat-decrypt" / "config.json"
MESSAGE_DB_PATTERN = re.compile(r"message_\d+\.db$")
MSG_TABLE_PATTERN = re.compile(r"Msg_[0-9a-f]{32}$")

TYPE_NAMES = {
    1: "text",
    3: "image",
    34: "voice",
    42: "contact_card",
    43: "video",
    47: "sticker",
    48: "location",
    49: "link_or_file",
    50: "call",
    10000: "system",
    10002: "recall",
}


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _resolve_config_path(value: str | None, config_path: Path) -> Path | None:
    if not value:
        return None
    path = Path(value)
    return path if path.is_absolute() else (config_path.parent / path).resolve()


def _display_name(value: Any, fallback: str) -> str:
    text = str(value or "").strip()
    return text or fallback


def _safe_slug(value: str) -> str:
    text = re.sub(r"[\\/:*?\"<>|]+", "_", (value or "").strip())
    text = re.sub(r"\s+", "_", text)
    text = re.sub(r"_+", "_", text).strip("._")
    return (text[:48] or "wechat_group")


def _base_type(value: Any) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return number & 0xFFFFFFFF if number > 0xFFFFFFFF else number


def _parse_int(value: Any) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _collapse(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


class WeChatBridge:
    def __init__(self, config_path: Path | None = None) -> None:
        self.config_path = config_path or Path(os.environ.get("WECHAT_BRIDGE_CONFIG", DEFAULT_CONFIG))
        self.config = _read_json(self.config_path)
        configured_decrypted = os.environ.get("WECHAT_DECRYPTED_DIR") or self.config.get("decrypted_dir")
        self.decrypted_dir = _resolve_config_path(configured_decrypted, self.config_path)
        self.process_name = self.config.get("wechat_process") or "Weixin.exe"
        self.external_exporter = ExternalWeChatExporter(REPO_ROOT, self.config)
        self._group_cache: tuple[float, list[dict[str, Any]]] | None = None
        self._local_file_index: dict[tuple[str, int], list[Path]] = {}
        self._local_file_index_months: frozenset[str] = frozenset()
        self._refresh_lock = threading.Lock()
        self._refresh_checked_at = 0.0
        self._refresh_state: dict[str, Any] = {
            "source_latest": None,
            "decrypted_latest": None,
            "stale": False,
            "refreshed": False,
            "in_progress": False,
            "error": None,
        }

    @property
    def contact_db(self) -> Path | None:
        if not self.decrypted_dir:
            return None
        path = self.decrypted_dir / "contact" / "contact.db"
        return path if path.is_file() else None

    @property
    def message_dir(self) -> Path | None:
        if not self.decrypted_dir:
            return None
        path = self.decrypted_dir / "message"
        return path if path.is_dir() else None

    @property
    def source_db_dir(self) -> Path | None:
        """The encrypted XWeChat database root, if configured and readable."""
        configured = self.config.get("db_dir")
        path = _resolve_config_path(configured, self.config_path)
        return path if path and path.is_dir() else None

    @staticmethod
    def _latest_db_mtime(root: Path | None) -> float | None:
        if root is None or not root.is_dir():
            return None
        latest: float | None = None
        try:
            for path in root.rglob("*.db"):
                if not path.is_file() or path.name.endswith(("-wal", "-shm")):
                    continue
                try:
                    mtime = path.stat().st_mtime
                except OSError:
                    continue
                latest = mtime if latest is None else max(latest, mtime)
        except OSError:
            return latest
        return latest

    @staticmethod
    def _format_mtime(value: float | None) -> str | None:
        if value is None:
            return None
        return datetime.fromtimestamp(value).isoformat(timespec="seconds")

    def _freshness_snapshot(self) -> dict[str, Any]:
        source_latest = self._latest_db_mtime(self.source_db_dir)
        decrypted_latest = self._latest_db_mtime(self.decrypted_dir)
        stale = bool(
            source_latest is not None
            and (decrypted_latest is None or source_latest > decrypted_latest + 0.001)
        )
        return {
            "source_latest": self._format_mtime(source_latest),
            "decrypted_latest": self._format_mtime(decrypted_latest),
            "stale": stale,
        }

    def _refresh_if_stale(self, *, force: bool = False) -> dict[str, Any]:
        """Refresh decrypted SQLite files before a read when the source is newer.

        This mirrors the she-love-me workflow (decrypt first, extract second),
        while keeping the bridge read-only with respect to WeChat itself.
        """
        now = time.monotonic()
        if not force and now - self._refresh_checked_at < 2.0:
            return dict(self._refresh_state)

        with self._refresh_lock:
            now = time.monotonic()
            if not force and now - self._refresh_checked_at < 2.0:
                return dict(self._refresh_state)

            snapshot = self._freshness_snapshot()
            state = {
                **snapshot,
                "refreshed": False,
                "in_progress": False,
                "error": None,
            }
            if not snapshot["stale"]:
                self._refresh_state = state
                self._refresh_checked_at = now
                return dict(state)

            decryptor = REPO_ROOT / "vendor" / "wechat-decrypt" / "decrypt_db.py"
            if not decryptor.is_file():
                state["error"] = "未找到增量解密脚本 decrypt_db.py"
                self._refresh_state = state
                self._refresh_checked_at = now
                return dict(state)

            state["in_progress"] = True
            self._refresh_state = dict(state)
            try:
                completed = subprocess.run(
                    [sys.executable, str(decryptor), "-i"],
                    cwd=str(decryptor.parent),
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=1800,
                    check=False,
                )
            except (OSError, subprocess.SubprocessError) as exc:
                state["error"] = f"增量解密未执行：{exc}"
            else:
                if completed.returncode != 0:
                    detail = (completed.stderr or completed.stdout or "").strip().splitlines()
                    state["error"] = detail[-1] if detail else f"增量解密退出码 {completed.returncode}"
                else:
                    state["refreshed"] = True
                    self._group_cache = None
                    self._local_file_index.clear()
                    self._local_file_index_months = frozenset()

            after = self._freshness_snapshot()
            state.update(after)
            state["in_progress"] = False
            if state["error"] is None and state["stale"]:
                state["error"] = "增量解密完成，但解密副本仍落后于原始数据库"
            self._refresh_state = state
            self._refresh_checked_at = time.monotonic()
            return dict(state)

    def _ensure_fresh_for_read(self) -> dict[str, Any]:
        state = self._refresh_if_stale()
        # An installed exporter is independent of the legacy decrypted copy.
        # Keep it usable even when an old decryptor cannot refresh.
        if state.get("error") and not self.external_exporter.status().get("ready"):
            raise RuntimeError(str(state["error"]))
        return state

    def _message_dbs(self) -> list[Path]:
        if not self.message_dir:
            return []
        return sorted(path for path in self.message_dir.iterdir() if path.is_file() and MESSAGE_DB_PATTERN.fullmatch(path.name))

    def _process_running(self) -> bool:
        if os.name != "nt":
            return False
        try:
            result = subprocess.run(
                ["tasklist", "/FI", f"IMAGENAME eq {self.process_name}", "/NH"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=3,
                check=False,
            )
        except (OSError, ValueError, subprocess.SubprocessError):
            # ValueError 兜住解码异常（tasklist 在中文系统输出 GBK，被当
            # UTF-8 解码会抛 UnicodeDecodeError——ValueError 的子类）。
            return False
        if not result.stdout:
            return False
        return self.process_name.lower() in result.stdout.lower()

    def status(self) -> dict[str, Any]:
        contact_readable = bool(self.contact_db and os.access(self.contact_db, os.R_OK))
        message_dbs = self._message_dbs()
        freshness = self._refresh_if_stale()
        local_ready = contact_readable and bool(message_dbs) and zstd is not None
        exporter = self.external_exporter.status()
        return {
            "process_name": self.process_name,
            "process_running": self._process_running(),
            "decrypted_available": bool(self.decrypted_dir and self.decrypted_dir.is_dir()),
            "contact_db_readable": contact_readable,
            "message_db_count": len(message_dbs),
            "zstandard_available": zstd is not None,
            "read_only": True,
            "source_latest": freshness.get("source_latest"),
            "decrypted_latest": freshness.get("decrypted_latest"),
            "decrypted_stale": bool(freshness.get("stale")),
            "refresh_in_progress": bool(freshness.get("in_progress")),
            "refresh_error": freshness.get("error"),
            "last_refresh": bool(freshness.get("refreshed")),
            "local_ready": local_ready,
            "exporter": exporter,
            "source_strategy": "external-exporter-first-with-legacy-decrypt-fallback",
            "ready": local_ready or bool(exporter.get("ready")),
        }

    def _load_names(self) -> dict[str, str]:
        if not self.contact_db:
            return {}
        names: dict[str, str] = {}
        try:
            with sqlite3.connect(self.contact_db) as conn:
                rows = conn.execute("SELECT username, nick_name, remark FROM contact").fetchall()
        except sqlite3.Error:
            return names
        for username, nickname, remark in rows:
            if username:
                names[str(username)] = _display_name(remark, _display_name(nickname, str(username)))
        return names

    def _load_groups(self) -> list[dict[str, Any]]:
        if not self.contact_db:
            return []
        names = self._load_names()
        try:
            with sqlite3.connect(self.contact_db) as conn:
                rows = conn.execute(
                    "SELECT username, nick_name, remark FROM contact "
                    "WHERE username LIKE '%@chatroom'"
                ).fetchall()
        except sqlite3.Error:
            return []

        # Listing group names should be instant.  Do not run COUNT/MAX for all
        # 400+ groups here; those aggregate queries are deferred to an exact
        # search/export path.
        table_presence: set[str] = set()
        for db_path in self._message_dbs():
            try:
                with sqlite3.connect(db_path) as conn:
                    table_presence.update(
                        row[0]
                        for row in conn.execute(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%'"
                        ).fetchall()
                    )
            except sqlite3.Error:
                continue

        groups = []
        for username, nickname, remark in rows:
            username = str(username or "").strip()
            if not username:
                continue
            display = _display_name(remark, _display_name(nickname, names.get(username, username)))
            table = "Msg_" + hashlib.md5(username.encode("utf-8")).hexdigest()
            has_messages = table in table_presence
            groups.append({
                "username": username,
                "display_name": display,
                "message_count": None,
                "latest_timestamp": None,
                "latest_date": None,
                "has_messages": has_messages,
                "is_group": True,
                "source_kind": "local-decrypted",
            })
        return groups

    def groups(self, query: str = "", limit: int = 80) -> list[dict[str, Any]]:
        self._ensure_fresh_for_read()
        now = time.monotonic()
        if self._group_cache is None or now - self._group_cache[0] > 10:
            local_groups = self._load_groups()
            external_groups = self.external_exporter.sessions(limit=300)
            # Keep local statistics when available, but let the exporter own
            # the selected source.  This preserves the upstream workflow:
            # exporter JSON first, legacy SQLite only as a fallback.
            merged: dict[str, dict[str, Any]] = {
                value["username"]: value for value in local_groups
            }
            for value in external_groups:
                local = merged.get(value["username"])
                if local:
                    value = {
                        **local,
                        **value,
                        "message_count": local.get("message_count"),
                        "latest_timestamp": local.get("latest_timestamp"),
                        "latest_date": local.get("latest_date"),
                    }
                merged[value["username"]] = value
            values = list(merged.values())
            values.sort(key=lambda value: (value["display_name"].lower(), value["username"]))
            self._group_cache = (now, values)
        needle = (query or "").strip().lower()
        values = self._group_cache[1]
        if needle:
            values = [
                value for value in values
                if needle in value["display_name"].lower() or needle in value["username"].lower()
            ]
            # A narrowed search usually returns only a handful of groups, so
            # enrich those rows with counts without blocking the empty search.
            enriched = []
            for value in values:
                value = dict(value)
                if value.get("source_kind") == "local-decrypted":
                    count, latest = self._message_stats(value["username"])
                    value["message_count"] = count
                    value["latest_timestamp"] = latest
                    value["latest_date"] = datetime.fromtimestamp(latest).date().isoformat() if latest else None
                    value["has_messages"] = count > 0
                enriched.append(value)
            values = enriched
        return values[: max(1, min(int(limit or 80), 300))]

    def _message_stats(self, username: str) -> tuple[int, int | None]:
        table = "Msg_" + hashlib.md5(username.encode("utf-8")).hexdigest()
        if not MSG_TABLE_PATTERN.fullmatch(table):
            return 0, None
        total = 0
        latest: int | None = None
        for db_path in self._message_dbs():
            try:
                with sqlite3.connect(db_path) as conn:
                    exists = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
                    ).fetchone()
                    if not exists:
                        continue
                    row = conn.execute(f"SELECT COUNT(*), MAX(create_time) FROM [{table}]").fetchone()
            except sqlite3.Error:
                continue
            total += int(row[0] or 0)
            if row[1] is not None:
                latest = max(latest or 0, int(row[1]))
        return total, latest

    def resolve_group(self, identifier: str | None) -> dict[str, Any] | None:
        query = (identifier or "").strip()
        if not query:
            return None
        values = self.groups(query, limit=300)
        for value in values:
            if value["username"] == query:
                return value
        exact_names = [value for value in values if value["display_name"] == query]
        if len(exact_names) == 1:
            return exact_names[0]
        return values[0] if len(values) == 1 else None

    def _resolve_local_group(self, username: str, display: str) -> dict[str, Any] | None:
        """Find the legacy SQLite copy used when an exporter export fails."""
        for value in self._load_groups():
            if value.get("username") == username or value.get("display_name") == display:
                return value
        return None

    def _own_wxid(self) -> str | None:
        configured = self.config.get("wxid")
        if configured:
            return str(configured)
        if self.decrypted_dir:
            db_dir = self.config.get("db_dir") or ""
            account = Path(db_dir).parent.name if db_dir else ""
            if "_" in account:
                candidate = account.rsplit("_", 1)[0]
                if candidate:
                    return candidate
        return None

    def _decompress(self, content: Any, compression: Any) -> str:
        if compression == 4 and isinstance(content, bytes):
            if zstd is None:
                raise RuntimeError("当前 Python 环境缺少 zstandard，无法读取压缩消息")
            try:
                return zstd.ZstdDecompressor().decompress(content).decode("utf-8", errors="replace")
            except Exception:
                return ""
        if isinstance(content, bytes):
            return content.decode("utf-8", errors="replace")
        return str(content or "")

    @staticmethod
    def _split_group_content(content: str) -> tuple[str, str]:
        if ":\n" in content:
            return content.split(":\n", 1)
        match = re.match(r"^([A-Za-z0-9_\-@.]+):(<\?xml|<msg|<sysmsg|<voipmsg)", content)
        if match:
            sender = match.group(1)
            return sender, content[len(sender) + 1:]
        return "", content

    @staticmethod
    def _xml_root(content: str) -> ET.Element | None:
        if not content or "<" not in content:
            return None
        start = content.find("<")
        try:
            return ET.fromstring(content[start:])
        except ET.ParseError:
            return None

    def _render_app(self, content: str) -> tuple[str, dict[str, Any] | None]:
        root = self._xml_root(content)
        if root is None:
            return "[链接/文件]", None
        appmsg = root.find(".//appmsg")
        if appmsg is None and root.tag == "appmsg":
            appmsg = root
        if appmsg is None:
            return "[链接/文件]", None
        title = _collapse(appmsg.findtext("title"))
        app_type = _parse_int(_collapse(appmsg.findtext("type"))) or 0
        url = _collapse(appmsg.findtext("url"))
        attach = appmsg.find("appattach")
        file_name = _collapse(attach.findtext("fileext")) if attach is not None else ""
        total_size = _parse_int(attach.findtext("totallen")) if attach is not None else None
        if app_type == 6:
            file_meta = {
                "name": title,
                "extension": file_name,
                "size": total_size,
                "url": url or None,
                "md5": _collapse(appmsg.findtext("md5")) or None,
                "attach_id": _collapse(attach.findtext("attachid")) if attach is not None else None,
            }
            return (f"[文件] {title}" if title else "[文件]"), file_meta
        if app_type == 5:
            return (f"[链接] {title}" if title else "[链接]"), None
        return (f"[链接/文件] {title}" if title else "[链接/文件]"), None

    def _file_roots(self) -> list[Path]:
        roots: list[Path] = []
        base = self.config.get("wechat_base_dir")
        attach = self.config.get("xwechat_attach_dir")
        for value in (base, attach):
            path = _resolve_config_path(value, self.config_path)
            if path and path.exists():
                roots.append(path)
        if base:
            base_path = _resolve_config_path(base, self.config_path)
            if base_path:
                roots.append(base_path / "msg" / "file")
        return list(dict.fromkeys(roots))

    @staticmethod
    def _md5_file(path: Path) -> str | None:
        digest = hashlib.md5()
        try:
            with path.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        except OSError:
            return None
        return digest.hexdigest()

    def _resolve_local_file(self, file_meta: dict[str, Any], timestamp: int) -> str | None:
        title = str(file_meta.get("name") or "").strip()
        if not title or Path(title).name != title or ".." in title:
            return None
        expected_md5 = str(file_meta.get("md5") or "").lower()
        month = datetime.fromtimestamp(timestamp).strftime("%Y-%m") if timestamp else ""
        months = {month}
        if timestamp:
            for delta in (-31, 31):
                months.add(datetime.fromtimestamp(timestamp + delta * 86400).strftime("%Y-%m"))
        self._ensure_file_index(months)
        expected_size = _parse_int(file_meta.get("size"))
        candidates = list(self._local_file_index.get((title, expected_size if expected_size is not None else -1), []))
        if not candidates and expected_size is None:
            candidates = [
                path for (name, _size), paths in self._local_file_index.items()
                if name == title for path in paths
            ]
        for candidate in candidates:
            if expected_size is not None and candidate.stat().st_size != expected_size:
                continue
            if expected_md5 and len(expected_md5) == 32 and self._md5_file(candidate) != expected_md5:
                continue
            return str(candidate)
        return None

    def _ensure_file_index(self, months: set[str]) -> None:
        missing_months = frozenset(months) - self._local_file_index_months
        if not missing_months:
            return
        for root in self._file_roots():
            if not root.is_dir():
                continue
            month_dirs: list[Path] = []
            if root.name == "attach":
                try:
                    month_dirs = [
                        child / month
                        for child in root.iterdir() if child.is_dir()
                        for month in missing_months
                    ]
                except OSError:
                    month_dirs = []
            else:
                month_dirs = [root / month for month in missing_months]
            for month_dir in month_dirs:
                if not month_dir.is_dir():
                    continue
                try:
                    for path_string, _, filenames in os.walk(month_dir):
                        for filename in filenames:
                            path = Path(path_string) / filename
                            try:
                                size = path.stat().st_size
                            except OSError:
                                continue
                            self._local_file_index.setdefault((filename, size), []).append(path)
                except OSError:
                    continue
        self._local_file_index_months = frozenset(set(self._local_file_index_months) | set(missing_months))

    def _render_message(self, local_type: Any, content: str) -> tuple[str, str, dict[str, Any] | None]:
        sender, body = self._split_group_content(content)
        base = _base_type(local_type)
        type_name = TYPE_NAMES.get(base, f"type_{base}")
        if base == 1:
            return sender, body, None
        if base == 49:
            text, file_meta = self._render_app(body)
            return sender, text, file_meta
        if base == 10000:
            root = self._xml_root(body)
            message = _collapse(root.findtext(".//content") if root is not None else body)
            return sender, f"[系统消息] {message}".strip(), None
        if base == 10002:
            return sender, "[撤回消息]", None
        labels = {
            "image": "[图片]",
            "voice": "[语音消息]",
            "video": "[视频]",
            "sticker": "[表情]",
            "location": "[位置]",
            "contact_card": "[名片]",
            "call": "[通话]",
        }
        return sender, labels.get(type_name, f"[{type_name}]"), None

    def export_group(
        self,
        identifier: str,
        output_root: Path | None = None,
        resolve_files: bool = False,
    ) -> dict[str, Any]:
        freshness = self._ensure_fresh_for_read()
        group = self.resolve_group(identifier)
        if group is None:
            raise ValueError(f"找不到唯一微信群：{identifier}")
        username = group["username"]
        display = group["display_name"]
        if group.get("source_kind") == "wechat-exporter":
            output_root = output_root or (REPO_ROOT / "data" / "contacts")
            try:
                exported = self.external_exporter.export_session(username, display, output_root)
                exported["refresh"] = freshness
                exported["source_strategy"] = "external-exporter"
                return exported
            except (RuntimeError, OSError) as exc:
                # An installed exporter can still fail because WeChat is not
                # logged in or its session is unavailable.  Fall back only to
                # a matching local archive, and surface the reason in metadata.
                local_group = self._resolve_local_group(username, display)
                if local_group is None:
                    raise
                group = local_group
                username = group["username"]
                display = group["display_name"]
                exporter_error = str(exc)[:300]
        else:
            exporter_error = None
        table = "Msg_" + hashlib.md5(username.encode("utf-8")).hexdigest()
        names = self._load_names()
        own_wxid = self._own_wxid()
        rows: list[tuple[Any, ...]] = []
        for db_index, db_path in enumerate(self._message_dbs()):
            try:
                with sqlite3.connect(db_path) as conn:
                    id_map = {rowid: value for rowid, value in conn.execute("SELECT rowid, user_name FROM Name2Id") if value}
                    exists = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
                    ).fetchone()
                    if not exists:
                        continue
                    selected = conn.execute(
                        f"SELECT local_id, local_type, create_time, real_sender_id, message_content, WCDB_CT_message_content FROM [{table}] ORDER BY create_time ASC"
                    ).fetchall()
            except sqlite3.Error:
                continue
            for row in selected:
                rows.append((*row, id_map, db_path.name, db_index))
        rows.sort(key=lambda row: (int(row[2] or 0), int(row[0] or 0), row[-1]))

        messages: list[dict[str, Any]] = []
        files: list[dict[str, Any]] = []
        for local_id, local_type, timestamp, real_sender_id, raw_content, compression, id_map, db_name, db_index in rows:
            content = self._decompress(raw_content, compression)
            sender_from_content, rendered, file_meta = self._render_message(local_type, content)
            sender_username = sender_from_content or id_map.get(real_sender_id, "")
            sender = "me" if own_wxid and sender_username == own_wxid else names.get(sender_username, sender_username or "unknown")
            message_uid = f"{db_name}:{local_id}"
            record: dict[str, Any] = {
                "local_id": local_id,
                "message_uid": message_uid,
                "timestamp": int(timestamp or 0),
                "sender": sender,
                "sender_username": sender_username,
                "content": rendered,
                "type": TYPE_NAMES.get(_base_type(local_type), f"type_{_base_type(local_type)}"),
                "local_type": _base_type(local_type),
                "source_db": db_name,
            }
            if file_meta is not None:
                if resolve_files:
                    local_path = self._resolve_local_file(file_meta, int(timestamp or 0))
                    if local_path:
                        file_meta = dict(file_meta)
                        file_meta["local_path"] = local_path
                record["file"] = file_meta
                files.append({
                    "message_uid": message_uid,
                    "timestamp": int(timestamp or 0),
                    "sender": sender,
                    **file_meta,
                })
            messages.append(record)

        messages.sort(key=lambda item: (item["timestamp"], item["message_uid"]))
        output_root = output_root or (REPO_ROOT / "data" / "contacts")
        bundle_dir = output_root / f"{_safe_slug(display)}__{hashlib.md5(username.encode('utf-8')).hexdigest()[:8]}"
        bundle_dir.mkdir(parents=True, exist_ok=True)
        source = {
            "contact_username": username,
            "contact_display": display,
            "own_wxid": own_wxid or "unknown",
            "bundle_dir": str(bundle_dir),
            "total": len(messages),
            "file_total": len(files),
            "source_kind": "local-decrypted",
            "provider": "legacy-decrypted-sqlite",
            "source_strategy": "legacy-decrypted-fallback",
            "data_freshness": freshness,
            "messages": messages,
        }
        if exporter_error:
            source["exporter_error"] = exporter_error
        messages_path = bundle_dir / "messages.json"
        files_path = bundle_dir / "files.json"
        self._atomic_write(messages_path, source)
        self._atomic_write(files_path, {
            "contact_username": username,
            "contact_display": display,
            "total": len(files),
            "files": files,
        })
        return {
            "source": source,
            "messages_path": str(messages_path),
            "files_path": str(files_path),
            "file_count": len(files),
            "message_count": len(messages),
            "files": files,
            "refresh": freshness,
        }

    @staticmethod
    def _atomic_write(path: Path, value: dict[str, Any]) -> None:
        fd, temp_name = tempfile.mkstemp(prefix=".wechat-export-", suffix=".tmp", dir=path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(value, handle, ensure_ascii=False, indent=2)
            os.replace(temp_name, path)
        except Exception:
            try:
                os.unlink(temp_name)
            except OSError:
                pass
            raise
