from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from threading import Lock
from typing import Any


STORE_PATH = Path(__file__).resolve().parents[2] / "data" / "attention-desk" / "completions.json"
STORE_LOCK = Lock()
ITEM_KEY_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def item_key(source: dict[str, Any], item: dict[str, Any]) -> str:
    source_id = source.get("contact_username") or (
        f"{source.get('contact_display') or 'uploaded'}:{source.get('archive_start') or ''}"
    )
    message_ids = item.get("message_ids") or []
    evidence_id = message_ids[0] if message_ids else f"{item.get('window', {}).get('end', '')}:{item.get('preview', '')}"
    return hashlib.sha256(f"{source_id}\0{evidence_id}".encode("utf-8")).hexdigest()


def load_completions(path: Path = STORE_PATH) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("completed"), dict):
        raise ValueError("完成记录文件格式不正确")
    return payload["completed"]


def set_completion(key: str, completed: bool, path: Path = STORE_PATH) -> dict[str, Any]:
    if not ITEM_KEY_PATTERN.fullmatch(key):
        raise ValueError("事项标识无效")
    if type(completed) is not bool:
        raise ValueError("completed 必须是布尔值")
    with STORE_LOCK:
        entries = load_completions(path)
        if completed:
            existing = entries.get(key)
            completed_at = existing.get("completed_at") if isinstance(existing, dict) else None
            completed_at = completed_at or datetime.now().astimezone().isoformat(timespec="seconds")
            entries[key] = {"completed_at": completed_at}
        else:
            entries.pop(key, None)
            completed_at = None
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(
            json.dumps({"version": 1, "completed": entries}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(path)
    return {"item_key": key, "completed": completed, "completed_at": completed_at}


def apply_completions(
    source: dict[str, Any],
    items: list[dict[str, Any]],
    entries: dict[str, dict[str, str]],
) -> list[dict[str, Any]]:
    active = []
    completed = []
    overdue = []
    for item in items:
        copy = dict(item)
        copy["decision"] = dict(item["decision"])
        copy["item_key"] = item_key(source, item)
        entry = entries.get(copy["item_key"])
        if isinstance(entry, dict) and entry.get("completed_at"):
            copy["decision"].update({
                "queue_state": "archived",
                "archive_reason": "completed",
                "completed_at": entry["completed_at"],
            })
            completed.append(copy)
        elif copy["decision"].get("queue_state") == "archived":
            overdue.append(copy)
        else:
            active.append(copy)
    completed.sort(key=lambda item: item["decision"]["completed_at"], reverse=True)
    return active + completed + overdue
