"""转发收件箱：监视 WorkBuddy 收到的微信聊天记录分享，自动落到本地收件箱。

WorkBuddy 收到「微信转发聊天记录」事件时，会把 zip 存到
``~/.workbuddy/app/tmp/chat-history/``（文件名带时间戳），并预解压到
``wechat-chat-history-*`` 目录。本模块只关心 zip 文件本身：

- 工作台启动时自建收件箱目录 ``data/attention-desk/inbox/``；
- 后台线程按固定间隔扫描 WorkBuddy 分享目录；
- 新出现的 zip 通过完整性校验后拷入收件箱，并登记到 manifest.json；
- 拷贝只读原始 zip，不动、不删 WorkBuddy 的任何文件。

manifest 条目结构::

    {
      "id": "a6ae8c12",            # 内容哈希前 8 位，稳定去重键
      "filename": "xxx.zip",       # 收件箱内的文件名
      "original_path": "...",      # WorkBuddy 落盘的原始路径
      "received_at": "2026-09-27T16:12:16",  # 取自文件 mtime
      "copied_at": "...",          # 拷入收件箱的时间
      "size": 572,
      "status": "new",             # new / imported / error
      "imported_at": null,
      "error": ""
    }
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import threading
import time
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any


def default_watch_dir() -> Path:
    """默认监视 WorkBuddy 微信分享落盘目录。

    换机器或 WorkBuddy 改版本导致落盘位置不同时，不需要改代码，
    设环境变量 ``ATTENTION_INBOX_WATCH_DIR`` 指向新目录即可。
    """
    override = os.environ.get("ATTENTION_INBOX_WATCH_DIR", "").strip()
    if override:
        return Path(override).expanduser()
    return Path.home() / ".workbuddy" / "app" / "tmp" / "chat-history"


# 默认值（可被 ATTENTION_INBOX_WATCH_DIR 覆盖）；InboxStore 不传 watch_dir 时使用
WORKBUDDY_CHAT_DIR = default_watch_dir()

DEFAULT_POLL_SECONDS = 10
# 只拷 mtime 已稳定超过该秒数的文件，避免读到写到一半的 zip
STABLE_SECONDS = 3


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


class InboxStore:
    """收件箱：自建目录 + manifest 登记 + WorkBuddy 分享目录轮询。"""

    def __init__(
        self,
        inbox_root: Path,
        watch_dir: Path | None = None,
        poll_seconds: int = DEFAULT_POLL_SECONDS,
    ) -> None:
        self.inbox_root = Path(inbox_root)
        # 不传 watch_dir 时按环境变量 / 默认位置解析，便于跨机器部署
        self.watch_dir = Path(watch_dir) if watch_dir is not None else default_watch_dir()
        self.poll_seconds = max(0, int(poll_seconds))
        self.manifest_path = self.inbox_root / "manifest.json"
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._watcher: threading.Thread | None = None
        # 目录由工作台本身创建
        self.inbox_root.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------- manifest

    def _load_manifest(self) -> list[dict[str, Any]]:
        if not self.manifest_path.exists():
            return []
        try:
            data = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return []
        return data.get("items", []) if isinstance(data, dict) else []

    def _save_manifest(self, items: list[dict[str, Any]]) -> None:
        payload = {"version": 1, "updated_at": _now(), "items": items}
        self.manifest_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    # ---------------------------------------------------------------- scan

    def _candidate_zips(self) -> list[Path]:
        """WorkBuddy 分享目录里 mtime 已稳定的 zip 文件。"""
        if not self.watch_dir.is_dir():
            return []
        cutoff = time.time() - STABLE_SECONDS
        result: list[Path] = []
        for entry in sorted(self.watch_dir.iterdir()):
            if not entry.is_file() or entry.suffix.lower() != ".zip":
                continue
            try:
                stat = entry.stat()
            except OSError:
                continue
            if stat.st_mtime > cutoff:
                continue
            if not zipfile.is_zipfile(entry):
                continue
            result.append(entry)
        return result

    def scan(self) -> list[dict[str, Any]]:
        """扫描分享目录，把新 zip 拷入收件箱；返回本次新增的条目。"""
        with self._lock:
            items = self._load_manifest()
            known_ids = {item.get("id") for item in items}
            added: list[dict[str, Any]] = []
            for source in self._candidate_zips():
                try:
                    raw = source.read_bytes()
                except OSError:
                    continue
                entry_id = hashlib.md5(raw).hexdigest()[:8]
                if entry_id in known_ids:
                    continue
                target = self.inbox_root / source.name
                # 同名不同内容的极端情况：带上 id 前缀避免覆盖
                if target.exists():
                    target = self.inbox_root / f"{entry_id}_{source.name}"
                shutil.copyfile(source, target)
                record = {
                    "id": entry_id,
                    "filename": target.name,
                    "original_path": str(source),
                    "received_at": datetime
                        .fromtimestamp(source.stat().st_mtime)
                        .strftime("%Y-%m-%dT%H:%M:%S"),
                    "copied_at": _now(),
                    "size": len(raw),
                    "status": "new",
                    "imported_at": None,
                    "error": "",
                }
                items.append(record)
                added.append(record)
                known_ids.add(entry_id)
            if added:
                self._save_manifest(items)
            return added

    # ------------------------------------------------------------- 状态查询

    def list_items(self) -> list[dict[str, Any]]:
        with self._lock:
            return self._load_manifest()

    def status(self) -> dict[str, Any]:
        return {
            "inbox_dir": str(self.inbox_root),
            "watch_dir": str(self.watch_dir),
            "watch_dir_exists": self.watch_dir.is_dir(),
            "poll_seconds": self.poll_seconds,
            "watcher_running": self._watcher is not None and self._watcher.is_alive(),
        }

    # ------------------------------------------------------------ 导入状态

    def mark_imported(self, filename: str, error: str | None = None) -> None:
        with self._lock:
            items = self._load_manifest()
            for item in items:
                if item.get("filename") == filename:
                    if error:
                        item["status"] = "error"
                        item["error"] = error[:300]
                    else:
                        item["status"] = "imported"
                        item["imported_at"] = _now()
                        item["error"] = ""
                    break
            self._save_manifest(items)

    def read_zip(self, filename: str) -> bytes:
        """读取收件箱内指定 zip 的字节。找不到或不合法时抛 ValueError。"""
        path = self.inbox_root / filename
        if not path.is_file():
            raise ValueError(f"收件箱里没有 {filename}")
        raw = path.read_bytes()
        if not zipfile.is_zipfile(path):
            raise ValueError(f"{filename} 不是有效的 zip 压缩包")
        return raw

    # -------------------------------------------------------------- watcher

    def start_watcher(self) -> None:
        """启动后台轮询线程（幂等；poll_seconds=0 视为关闭）。"""
        if self.poll_seconds <= 0:
            return
        if self._watcher is not None and self._watcher.is_alive():
            return
        self._stop_event.clear()
        self._watcher = threading.Thread(
            target=self._watch_loop, name="attention-inbox-watcher", daemon=True
        )
        self._watcher.start()

    def stop_watcher(self) -> None:
        self._stop_event.set()
        watcher = self._watcher
        if watcher is not None and watcher.is_alive():
            watcher.join(timeout=2)
        self._watcher = None

    def _watch_loop(self) -> None:
        while not self._stop_event.wait(self.poll_seconds):
            try:
                self.scan()
            except Exception:  # noqa: BLE001 - 轮询线程不允许退出
                continue
