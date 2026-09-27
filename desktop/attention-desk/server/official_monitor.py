"""Read-only, serial monitoring of public Wenzhou University college notices.

The source catalogue and HTML parsers come from the repository's scraper. The
workbench stores only notice metadata, not article bodies or attachments.
"""

from __future__ import annotations

import json
import re
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from bs4 import BeautifulSoup


REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from _common import PAGE_DELAY, fetch, is_auth_wall, jitter, new_session, norm_date  # noqa: E402
from scrape import (  # noqa: E402
    build_date_map,
    clean_column,
    extract_items,
    find_notice_blocks,
    is_noise,
    pick_list_pages,
)


STORE_PATH = REPO_ROOT / "data" / "attention-desk" / "official_monitor.json"
CATALOG_PATH = REPO_ROOT / "data" / "sites.json"
CATALOG_URL = "https://www.wzu.edu.cn/xxgk/xysz.htm"
DIRECT_LISTS = {
    "ai.wzu.edu.cn": "https://ai.wzu.edu.cn/xwzx/xsgg.htm",
    "slxy.wzu.edu.cn": "https://slxy.wzu.edu.cn/xxzx/xsgg.htm",
    "shxy.wzu.edu.cn": "https://shxy.wzu.edu.cn/xwzx/xsgg.htm",
    "jdxy.wzu.edu.cn": "https://jdxy.wzu.edu.cn/xstz.htm",
    "eee.wzu.edu.cn": "https://eee.wzu.edu.cn/xwzx1/xsgg.htm",
    "chem.wzu.edu.cn": "https://chem.wzu.edu.cn/index/tzgg/xsgg1.htm",
    "cace.wzu.edu.cn": "https://cace.wzu.edu.cn/ywgk/xsgg.htm",
    "yyxy.wzu.edu.cn": "https://yyxy.wzu.edu.cn/xstz1/xstz.htm",
    "art.wzu.edu.cn": "https://art.wzu.edu.cn/xwzx/xsgg.htm",
    "hum.wzu.edu.cn": "https://hum.wzu.edu.cn/xysy/xstz.htm",
    "jsjyxy.wzu.edu.cn": "https://jsjyxy.wzu.edu.cn/index/xsgg.htm",
}
INTERVAL_OPTIONS = {0, 6, 12, 24}
CATEGORY_LABELS = {
    "competition_activity": "比赛 / 活动",
    "publicity": "公示",
    "other": "其他公告",
    "unknown": "待确认",
}
DEADLINE_PATTERNS = (
    re.compile(
        r"(?:报名|申请|提交|参赛|参评|反馈|确认|缴费|办理|截止)(?:时间|日期)?"
        r"\s*(?:为|至|到|：|:)?\s*"
        r"((?:20\d{2}[-/.年]\s*)?\d{1,2}(?:月|[-/.])\s*\d{1,2}(?:日)?"
        r"(?:\s*[上下]午)?(?:\s*\d{1,2}(?::|：|点)\d{2})?(?:\s*前)?)"
    ),
    re.compile(
        r"(?:截至|截止至|截止到|请于)\s*"
        r"((?:20\d{2}[-/.年]\s*)?\d{1,2}(?:月|[-/.])\s*\d{1,2}(?:日)?"
        r"(?:\s*[上下]午)?(?:\s*\d{1,2}(?::|：|点)\d{2})?(?:\s*前)?)"
    ),
)
DEADLINE_RANGE_PATTERN = re.compile(
    r"(?P<start>(?:20\d{2}\s*[年./-]\s*)?\d{1,2}\s*(?:月|[-/.])\s*\d{1,2}\s*日?)"
    r"\s*(?:至|到|—|–|-|~|～)\s*"
    r"(?P<end>(?:20\d{2}\s*[年./-]\s*)?\d{1,2}\s*(?:月|[-/.])\s*\d{1,2}\s*日?)"
    r"(?P<context>.{0,55}(?:逾期|截止|不再受理|受理|提交|送交|报名|申请).{0,35})"
)
CONTENT_SELECTORS = (
    "#vsb_content",
    ".v_news_content",
    "div.v_news_content",
    "#vsb_content_2",
    ".article-content",
    ".news_content",
    "#news_content",
    ".con_txt",
    ".article_con",
    "#content",
)


class AuthRequired(RuntimeError):
    pass


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def official_host(url: str) -> bool:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    return parsed.scheme == "https" and (host == "wzu.edu.cn" or host.endswith(".wzu.edu.cn"))


def load_catalog(path: Path = CATALOG_PATH) -> list[dict[str, str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    sources: list[dict[str, str]] = []
    seen: set[str] = set()
    for entry in payload.get("学院", []):
        home = str(entry.get("url") or "").rstrip("/") + "/"
        host = (urlparse(home).hostname or "").lower()
        if not official_host(home) or host in seen:
            continue
        seen.add(host)
        sources.append({
            "id": host,
            "name": str(entry.get("name") or host),
            "home": home,
            "list_url": DIRECT_LISTS.get(host, ""),
        })
    return sources


def parse_notice_page(html: str, url: str, column: str) -> list[dict[str, str]]:
    soup = BeautifulSoup(html, "lxml")
    return extract_items(soup, url, column, build_date_map(soup))


def classify_notice(title: str, column: str = "") -> tuple[str, float, str]:
    """Classify list metadata conservatively; article bodies are not fetched here."""
    text = f"{title} {column}".strip()
    publicity = (
        "公示名单", "名单公示", "公示期", "拟推荐", "拟确定", "拟录取",
        "拟获", "评审结果公示", "结果公示", "公示",
    )
    activity = (
        "比赛", "竞赛", "赛事", "活动", "报名", "参赛", "讲座", "培训",
        "评选", "创新", "实践", "志愿", "招募", "演讲", "运动会", "征集",
    )
    if any(keyword in text for keyword in publicity):
        return "publicity", 0.96, "标题 / 栏目包含公示信号"
    if any(keyword in title for keyword in activity):
        return "competition_activity", 0.86, "标题包含比赛或活动信号"
    if any(keyword in text for keyword in ("通知", "公告", "通告")):
        return "other", 0.68, "标题 / 栏目属于通知公告"
    return "unknown", 0.35, "缺少可靠分类信号"


def _content_node(soup: BeautifulSoup):
    for selector in CONTENT_SELECTORS:
        node = soup.select_one(selector)
        if node is not None:
            return node
    return soup.body


def _normalize_date_fragment(fragment: str, published_at: str = "") -> str | None:
    """Normalize a deadline fragment without treating publication metadata as a deadline."""
    raw = re.sub(r"\s+", " ", str(fragment or "")).strip()
    if not raw:
        return None
    # College sites often insert spaces between Chinese date units (e.g. "9 月 28 日").
    # This fragment is already bounded by the deadline matcher, so removing whitespace
    # here cannot merge unrelated prose and keeps the date parser format-agnostic.
    raw = re.sub(r"\s+", "", raw)
    raw = raw.replace("：", ":").replace("点", ":").replace("日", "")
    meridiem = ""
    if "下午" in raw:
        meridiem = "pm"
        raw = raw.replace("下午", "")
    elif "上午" in raw:
        meridiem = "am"
        raw = raw.replace("上午", "")
    raw = raw.replace("前", "").strip()
    has_clock = bool(re.search(r"\d{1,2}\s*(?::|：|点)\s*\d{2}$", raw))
    time_match = re.search(r"(\d{1,2})(?::(\d{2}))?$", raw) if has_clock else None
    hour = minute = None
    if time_match and ("月" in raw or re.search(r"\d{1,2}[-/.]\d{1,2}", raw)):
        hour = int(time_match.group(1))
        minute = int(time_match.group(2) or 0)
        raw = raw[:time_match.start()].strip()
    date_value = norm_date(raw)
    if not date_value:
        m = re.search(r"(?:(20\d{2})年\s*)?(\d{1,2})月\s*(\d{1,2})", raw)
        if not m:
            m = re.search(r"(?:(20\d{2})[-/.]\s*)?(\d{1,2})[-/.]\s*(\d{1,2})", raw)
        if not m:
            return None
        year = int(m.group(1) or 0)
        month = int(m.group(2))
        day = int(m.group(3))
        if not year:
            published_year = int(str(published_at)[:4]) if str(published_at)[:4].isdigit() else datetime.now().year
            year = published_year
            try:
                published_month = int(str(published_at)[5:7])
                if published_month and month < published_month:
                    year += 1
            except (TypeError, ValueError):
                pass
        try:
            date_value = datetime(year, month, day).date().isoformat()
        except ValueError:
            return None
    if hour is None:
        return date_value
    if meridiem == "pm" and hour < 12:
        hour += 12
    if meridiem == "am" and hour == 12:
        hour = 0
    if not 0 <= hour <= 23 or not 0 <= minute <= 59:
        return None
    return f"{date_value} {hour:02d}:{minute:02d}"


def extract_deadline(text: str, published_at: str = "") -> dict[str, Any] | None:
    """Extract only explicit deadline language from public article text."""
    body = re.sub(r"\s+", " ", str(text or "")).strip()
    if not body:
        return None
    range_match = DEADLINE_RANGE_PATTERN.search(body)
    if range_match:
        end_value = _normalize_date_fragment(range_match.group("end"), published_at)
        if end_value:
            return {
                "value": end_value,
                "raw": range_match.group(0).strip()[:180],
                "source": "article-body",
                "confidence": 0.88,
            }
    for pattern in DEADLINE_PATTERNS:
        match = pattern.search(body)
        if not match:
            continue
        raw = match.group(0).strip()
        value = _normalize_date_fragment(match.group(1), published_at)
        if value:
            return {
                "value": value,
                "raw": raw[:180],
                "source": "article-body",
                "confidence": 0.91,
            }
    return None


def _notice_defaults(item: dict[str, Any]) -> dict[str, Any]:
    title = " ".join(str(item.get("title") or "").split())[:240]
    column = clean_column(str(item.get("column") or "通知公告"))
    category, category_confidence, category_evidence = classify_notice(title, column)
    return {
        "title": title,
        "url": str(item.get("url") or ""),
        "published_at": str(item.get("published_at") or item.get("date") or ""),
        "column": column,
        "category": str(item.get("category") or category),
        "category_confidence": float(item.get("category_confidence") or category_confidence),
        "category_evidence": str(item.get("category_evidence") or category_evidence),
        "deadline": item.get("deadline") if isinstance(item.get("deadline"), dict) else None,
        "read": bool(item.get("read", True)),
        "completed": bool(item.get("completed", False)),
    }


def normalize_notices(raw: list[dict[str, str]]) -> list[dict[str, Any]]:
    notices: dict[str, dict[str, Any]] = {}
    for item in raw:
        normalized = _notice_defaults(item)
        title = normalized["title"]
        url = normalized["url"]
        date = normalized["published_at"]
        if not title or not official_host(url) or is_noise(title, date):
            continue
        current = notices.get(url)
        if current:
            if not current["published_at"] and date:
                current["published_at"] = date
            if current["category"] == "unknown" and normalized["category"] != "unknown":
                current["category"] = normalized["category"]
                current["category_confidence"] = normalized["category_confidence"]
                current["category_evidence"] = normalized["category_evidence"]
            continue
        notices[url] = normalized
    return sorted(notices.values(), key=lambda item: (item["published_at"], item["title"]), reverse=True)[:160]


def collect_source(source: dict[str, str]) -> dict[str, Any]:
    """Fetch one college at a time using the teammate scraper's parser/rate limit."""
    session = new_session()
    request_count = 0

    def read_page(url: str, referer: str | None = None) -> tuple[str, str]:
        nonlocal request_count
        if not official_host(url):
            raise ValueError("来源地址不是温州大学官方域名")
        status, final, html, error = fetch(session, url, referer=referer, timeout=12, tries=1)
        request_count += 1
        if error:
            raise RuntimeError(error[:180])
        if is_auth_wall(status, final, html):
            raise AuthRequired("页面需要认证，已跳过")
        if status != 200 or not html:
            raise RuntimeError(f"HTTP {status or 'unknown'}")
        if not official_host(final):
            raise RuntimeError("页面跳转到非温大域名，已跳过")
        return final, html

    raw: list[dict[str, str]] = []
    errors: list[Exception] = []
    successful_pages = 0

    def try_page(url: str, referer: str | None = None) -> tuple[str, str] | None:
        nonlocal successful_pages
        if request_count:
            jitter(PAGE_DELAY)
        try:
            page = read_page(url, referer)
        except (AuthRequired, RuntimeError, ValueError) as exc:
            errors.append(exc)
            return None
        successful_pages += 1
        return page

    if source["list_url"]:
        page = try_page(source["list_url"], referer=source["home"])
        if page:
            raw.extend(parse_notice_page(page[1], page[0], "学生公告"))

    home_page = try_page(source["home"])
    if home_page:
        final, html = home_page
        soup = BeautifulSoup(html, "lxml")
        date_map = build_date_map(soup)
        blocks = find_notice_blocks(soup, final)
        for block in blocks:
            raw.extend(extract_items(block["node"], final, "/".join(block["labels"]), date_map))
        if not raw:
            raw.extend(extract_items(soup, final, "首页公告", date_map))
        for candidate in pick_list_pages(soup, final, blocks)[:2]:
            if candidate["url"] == source["list_url"]:
                continue
            page = try_page(candidate["url"], referer=final)
            if page:
                raw.extend(parse_notice_page(page[1], page[0], candidate["label"] or "通知公告"))

    if not successful_pages:
        raise errors[0] if errors else RuntimeError("没有可访问的公开页面")

    notices = normalize_notices(raw)
    return {
        "status": "partial" if errors else ("ok" if notices else "empty"),
        "error": "; ".join(str(error) for error in errors[:2])[:240],
        "notices": notices,
        "request_count": request_count,
        "checked_at": now_iso(),
    }


def merge_notices(previous: dict[str, Any], fetched: list[dict[str, Any]], checked_at: str) -> dict[str, Any]:
    old = previous.get("notices") or {}
    first_scan = not previous.get("baseline_complete")
    merged = dict(old)
    for item in fetched:
        normalized = _notice_defaults(item)
        url = normalized["url"]
        existing = old.get(url) or {}
        merged_category = existing.get("category") or normalized["category"]
        merged_category_confidence = existing.get("category_confidence") or normalized["category_confidence"]
        merged_category_evidence = existing.get("category_evidence") or normalized["category_evidence"]
        if merged_category == "unknown" and normalized["category"] != "unknown":
            merged_category = normalized["category"]
            merged_category_confidence = normalized["category_confidence"]
            merged_category_evidence = normalized["category_evidence"]
        merged[url] = {
            **normalized,
            "first_seen": existing.get("first_seen") or checked_at,
            "last_seen": checked_at,
            "read": existing.get("read", True) if existing else first_scan,
            "completed": existing.get("completed", False) if existing else False,
            "category": merged_category,
            "category_confidence": merged_category_confidence,
            "category_evidence": merged_category_evidence,
            "deadline": existing.get("deadline") or normalized["deadline"],
            "detail_fetched_at": existing.get("detail_fetched_at"),
            "detail_summary": existing.get("detail_summary"),
            "detail_error": existing.get("detail_error"),
        }
    return merged


class OfficialMonitor:
    def __init__(self, store_path: Path = STORE_PATH, catalog_path: Path = CATALOG_PATH, start_scheduler: bool = True):
        self.store_path = store_path
        self.catalog = load_catalog(catalog_path)
        self.by_id = {source["id"]: source for source in self.catalog}
        self._state_lock = threading.Lock()
        self._run_lock = threading.Lock()
        self._run: dict[str, Any] = {"running": False, "done": 0, "total": 0, "failed": 0, "current": ""}
        self._state = self._load_state()
        if start_scheduler:
            threading.Thread(target=self._schedule_loop, name="official-monitor-scheduler", daemon=True).start()

    def _load_state(self) -> dict[str, Any]:
        if not self.store_path.exists():
            return {"version": 2, "auto_interval_hours": 12, "last_full_scan_at": None, "sources": {}}
        payload = json.loads(self.store_path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or not isinstance(payload.get("sources"), dict):
            raise ValueError("官网监测记录文件格式不正确")
        changed = False
        for snapshot in payload["sources"].values():
            if not isinstance(snapshot, dict):
                continue
            notices = snapshot.get("notices") or {}
            for url, notice in list(notices.items()):
                if not isinstance(notice, dict):
                    continue
                normalized = _notice_defaults({**notice, "url": notice.get("url") or url})
                for key, value in normalized.items():
                    if key not in notice:
                        notice[key] = value
                        changed = True
            if "baseline_complete" not in snapshot and notices:
                snapshot["baseline_complete"] = True
                changed = True
        if payload.get("version", 1) < 2:
            payload["version"] = 2
            changed = True
        if changed:
            self.store_path.parent.mkdir(parents=True, exist_ok=True)
            self.store_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return payload

    def _save_state(self) -> None:
        self.store_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.store_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self._state, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.store_path)

    def overview(self) -> dict[str, Any]:
        with self._state_lock:
            snapshots = json.loads(json.dumps(self._state))
        sources = []
        notices = []
        for source in self.catalog:
            snapshot = snapshots["sources"].get(source["id"], {})
            stored = list((snapshot.get("notices") or {}).values())
            unread = sum(notice.get("read") is False for notice in stored)
            completed = sum(notice.get("completed") is True for notice in stored)
            category_counts = {
                category: sum(notice.get("category", "unknown") == category for notice in stored)
                for category in CATEGORY_LABELS
            }
            sources.append({
                **source,
                "status": snapshot.get("status", "not_checked"),
                "checked_at": snapshot.get("checked_at"),
                "error": snapshot.get("error", ""),
                "notice_count": len(stored),
                "unread_count": unread,
                "read_count": len(stored) - unread,
                "completed_count": completed,
                "category_counts": category_counts,
                "latest_notice_date": max((notice.get("published_at") or "" for notice in stored), default=""),
            })
            notices.extend({**notice, "source_id": source["id"], "source_name": source["name"]} for notice in stored)
        notices.sort(key=lambda item: (item.get("read") is False, item.get("first_seen") or "", item.get("published_at") or ""), reverse=True)
        with self._run_lock:
            run = dict(self._run)
        return {
            "catalog_url": CATALOG_URL,
            "sources": sources,
            "notices": notices[:2000],
            "summary": {
                "source_count": len(sources),
                "checked_count": sum(source["status"] != "not_checked" for source in sources),
                "healthy_count": sum(source["status"] in {"ok", "empty"} for source in sources),
                "error_count": sum(source["status"] in {"error", "auth_required", "partial"} for source in sources),
                "notice_count": len(notices),
                "unread_count": sum(notice.get("read") is False for notice in notices),
                "read_count": sum(notice.get("read") is not False for notice in notices),
                "completed_count": sum(notice.get("completed") is True for notice in notices),
                "category_counts": {
                    category: sum(notice.get("category", "unknown") == category for notice in notices)
                    for category in CATEGORY_LABELS
                },
                "category_labels": CATEGORY_LABELS,
                "last_full_scan_at": snapshots.get("last_full_scan_at"),
                "auto_interval_hours": snapshots.get("auto_interval_hours", 12),
            },
            "scan": run,
        }

    def start_scan(self, source_ids: list[str] | None = None) -> dict[str, Any]:
        ids = source_ids if source_ids is not None else [source["id"] for source in self.catalog]
        if not ids or any(source_id not in self.by_id for source_id in ids):
            raise ValueError("请选择有效的学院来源")
        ids = list(dict.fromkeys(ids))
        with self._run_lock:
            if self._run["running"]:
                raise RuntimeError("已有检查任务正在运行")
            self._run = {
                "running": True,
                "done": 0,
                "total": len(ids),
                "failed": 0,
                "current": "",
                "started_at": now_iso(),
                "finished_at": None,
            }
        threading.Thread(target=self._run_scan, args=(ids,), name="official-monitor-scan", daemon=True).start()
        return dict(self._run)

    def _run_scan(self, ids: list[str]) -> None:
        try:
            self._scan_sources(ids)
        except Exception as exc:
            with self._run_lock:
                self._run["error"] = f"扫描中断：{str(exc)[:180]}"
        finally:
            with self._run_lock:
                self._run["running"] = False
                self._run["current"] = ""
                self._run["finished_at"] = now_iso()

    def _scan_sources(self, ids: list[str]) -> None:
        for index, source_id in enumerate(ids):
            source = self.by_id[source_id]
            with self._run_lock:
                self._run["current"] = source["name"]
            try:
                outcome = collect_source(source)
                with self._state_lock:
                    previous = self._state["sources"].get(source_id, {})
                    self._state["sources"][source_id] = {
                        **previous,
                        "status": outcome["status"],
                        "checked_at": outcome["checked_at"],
                        "error": outcome.get("error", ""),
                        "request_count": outcome["request_count"],
                        "baseline_complete": True,
                        "notices": merge_notices(previous, outcome["notices"], outcome["checked_at"]),
                    }
                    self._save_state()
            except Exception as exc:
                status = "auth_required" if isinstance(exc, AuthRequired) else "error"
                with self._state_lock:
                    previous = self._state["sources"].get(source_id, {})
                    self._state["sources"][source_id] = {
                        **previous,
                        "status": status,
                        "checked_at": now_iso(),
                        "error": str(exc)[:240],
                    }
                    self._save_state()
                with self._run_lock:
                    self._run["failed"] += 1
            with self._run_lock:
                self._run["done"] = index + 1
            if index + 1 < len(ids):
                jitter(PAGE_DELAY)
        if len(ids) == len(self.catalog):
            with self._state_lock:
                self._state["last_full_scan_at"] = now_iso()
                self._save_state()

    def set_read(self, source_id: str | None, url: str | None, read: bool) -> int:
        if (source_id is not None and source_id not in self.by_id) or type(read) is not bool or (url and not source_id):
            raise ValueError("无效的学院或已读状态")
        with self._state_lock:
            groups = [self._state["sources"].get(source_id, {}).get("notices") or {}] if source_id else [
                entry.get("notices") or {} for entry in self._state["sources"].values()
            ]
            if url and url not in groups[0]:
                raise ValueError("未找到公告")
            targets = [groups[0][url]] if url else [notice for group in groups for notice in group.values()]
            for notice in targets:
                notice["read"] = read
            self._save_state()
            return len(targets)

    def set_action(self, source_id: str, url: str, completed: bool) -> dict[str, Any]:
        if source_id not in self.by_id or not isinstance(url, str) or not url or type(completed) is not bool:
            raise ValueError("无效的学院、公告或完成状态")
        with self._state_lock:
            notices = self._state["sources"].get(source_id, {}).get("notices") or {}
            notice = notices.get(url)
            if notice is None:
                raise ValueError("未找到公告")
            notice["completed"] = completed
            notice["completed_at"] = now_iso() if completed else None
            self._save_state()
            return {
                "source_id": source_id,
                "url": url,
                "completed": completed,
                "completed_at": notice["completed_at"],
            }

    def notice_detail(self, source_id: str, url: str) -> dict[str, Any]:
        if source_id not in self.by_id or not isinstance(url, str) or not url:
            raise ValueError("无效的学院或公告地址")
        if not official_host(url):
            raise ValueError("公告地址不是温州大学官方域名")
        with self._state_lock:
            source_state = self._state["sources"].get(source_id, {})
            notice = (source_state.get("notices") or {}).get(url)
            if notice is None:
                raise ValueError("未找到公告")
            cached = dict(notice)
        session = new_session()
        status, final, html, error = fetch(session, url, referer=self.by_id[source_id]["home"], timeout=12, tries=1)
        if error:
            raise RuntimeError(f"正文读取失败：{error[:180]}")
        if is_auth_wall(status, final, html):
            raise AuthRequired("正文需要认证，已跳过")
        if status != 200 or not html:
            raise RuntimeError(f"正文 HTTP {status or 'unknown'}")
        if not official_host(final):
            raise RuntimeError("正文跳转到非温大域名，已跳过")
        soup = BeautifulSoup(html, "lxml")
        node = _content_node(soup)
        if node is None:
            raise RuntimeError("未找到公开正文容器")
        for element in node.select("script,style,noscript,iframe,form"):
            element.decompose()
        body_text = re.sub(r"\s+", " ", node.get_text(" ", strip=True)).strip()
        if not body_text:
            raise RuntimeError("公开正文为空")
        category, category_confidence, category_evidence = classify_notice(
            cached.get("title", ""), cached.get("column", "")
        )
        deadline = extract_deadline(body_text, cached.get("published_at", ""))
        detail_summary = body_text[:1200]
        with self._state_lock:
            current = self._state["sources"][source_id]["notices"][url]
            current.update({
                "category": category,
                "category_confidence": category_confidence,
                "category_evidence": category_evidence,
                "deadline": deadline,
                "detail_fetched_at": now_iso(),
                "detail_summary": detail_summary,
                "detail_error": None,
            })
            self._save_state()
            result = dict(current)
        result.update({
            "source_id": source_id,
            "source_name": self.by_id[source_id]["name"],
            "summary": detail_summary,
            "body_available": True,
            "detail_fetched_at": result.get("detail_fetched_at"),
        })
        return result

    def set_interval(self, hours: int) -> None:
        if hours not in INTERVAL_OPTIONS:
            raise ValueError("自动检查间隔只能是 0、6、12 或 24 小时")
        with self._state_lock:
            self._state["auto_interval_hours"] = hours
            self._save_state()

    def _schedule_loop(self) -> None:
        while True:
            time.sleep(60)
            with self._state_lock:
                hours = self._state.get("auto_interval_hours", 12)
                last = self._state.get("last_full_scan_at")
            if not hours or not last:
                continue
            try:
                due = datetime.fromisoformat(last) + timedelta(hours=hours)
                if datetime.now().astimezone() >= due:
                    self.start_scan()
            except (ValueError, RuntimeError):
                continue
