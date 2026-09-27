# -*- coding: utf-8 -*-
"""公共基础层：路径、请求会话、限速、日期解析、日志。

所有脚本统一从这里取路径与请求设施，因此**没有任何硬编码的绝对路径** ——
项目可以 clone 到任意位置直接运行。

术语约定
  站点(site)  温州大学官网页脚里的一个二级单位官网
  通知(notice) 站点上的一个文章/公告条目
  条目(record) 下载器处理一条通知后写入 manifest 的一条记录

时间范围由命令行传入（--since/--until/--days），本模块只做解析与比较。
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import random
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

# ---------------------------------------------------------------- 路径
# 全部相对本文件定位，clone 到哪都能跑
HERE = Path(__file__).resolve().parent
CACHE = HERE / "cache"          # 首页/列表页 HTML 留档，便于复现与调试
OUT = HERE / "out"              # 抓取结果：notices.csv / sites_result.json / 报告
PROBE = HERE / "probe"          # 探测样例
DOWNLOAD = HERE / "download"    # 图文离线库
DATA = HERE / "data"            # 版本化的事实数据（站点清单等）
SITES_JSON = HERE / "sites.json"
SITES_DEFAULT = DATA / "sites.json"

for _d in (CACHE, OUT, PROBE, DOWNLOAD, DATA):
    _d.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- 抓取礼貌约定
# 用户明确要求：绝不并发、模拟真实用户。这两个区间是所有脚本的公共默认值。
PAGE_DELAY = (1.8, 4.0)     # 页面与页面之间
ASSET_DELAY = (0.3, 0.9)    # 同一页面内的图片/附件之间
MAX_LIST_PAGES = 3          # 单站点最多额外抓几个列表页，克制、不压站

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip, deflate",
    "Cache-Control": "max-age=0",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "same-origin",
    "Sec-Fetch-User": "?1",
}

IMG_EXT = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tif", ".tiff"}
DOC_EXT = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".rar",
           ".txt", ".csv", ".wps", ".et", ".dps", ".7z", ".mp3", ".mp4", ".wmv", ".avi"}

# ---------------------------------------------------------------- 日期
RE_FULL = re.compile(r"(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?")
RE_SHORT = re.compile(r"(?<!\d)(\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)")
RE_MD = re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)")
NOW_YEAR = _dt.date.today().year


def norm_date(txt):
    """把各种日期写法归一化成 YYYY-MM-DD，识别不出返回空串。

    支持：2026年7月10日 / 2026-07-10 / 2026/7/10 / 26.07.10 / 11-09（补当前年）
    """
    if not txt:
        return ""
    m = RE_FULL.search(txt)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if 2000 <= y <= 2100 and 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{y:04d}-{mo:02d}-{d:02d}"
    m = RE_SHORT.search(txt)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        y += 2000 if y < 70 else 1900
        if 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{y:04d}-{mo:02d}-{d:02d}"
    m = RE_MD.search(txt)
    if m:
        mo, d = int(m.group(1)), int(m.group(2))
        if 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{NOW_YEAR}-{mo:02d}-{d:02d}"
    return ""


def date_window(days=0, since="", until=""):
    """把 --days/--since/--until 归算成 (since, until)。

    未指定范围时返回 ('', '')，表示不按时间过滤。
    """
    today = _dt.date.today()
    if since:
        return since, (until or today.isoformat())
    if days:
        return (today - _dt.timedelta(days=days)).isoformat(), (until or today.isoformat())
    return "", ""


def add_date_args(ap):
    """给各脚本统一挂上时间范围参数。"""
    ap.add_argument("--days", type=int, default=0, help="只处理最近 N 天")
    ap.add_argument("--since", default="", help="起始日期 YYYY-MM-DD")
    ap.add_argument("--until", default="", help="截止日期 YYYY-MM-DD（默认今天）")
    return ap


# ---------------------------------------------------------------- 请求
def new_session():
    """带完整浏览器请求头的会话；Cookie 由 Session 自动维持。"""
    import requests
    s = requests.Session()
    s.headers.update(HEADERS)
    return s


def jitter(delay):
    """随机抖动延时，返回实际睡了多久（秒）。"""
    t = random.uniform(*delay)
    time.sleep(t)
    return t


def fetch(sess, url, referer=None, timeout=25, tries=2):
    """抓 HTML 页面，返回 (status, final_url, text, err)。

    出错重试 tries 次，每次重试前随机等待，避免把偶发抖动当失败。
    """
    last = None
    for i in range(tries):
        try:
            h = {"Referer": referer} if referer else {}
            r = sess.get(url, headers=h, timeout=timeout, allow_redirects=True)
            try:
                r.encoding = r.apparent_encoding or r.encoding
            except Exception:
                pass
            return r.status_code, r.url, r.text, None
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
            if i < tries - 1:
                time.sleep(random.uniform(2.0, 4.0))
    return None, None, None, last


def host_key(url):
    return re.sub(r"[^\w]", "_", urlparse(url).netloc.lower())


def is_auth_wall(status, final_url, text):
    """判断是否撞上了登录/统一身份认证墙 —— 撞上就跳过，绝不做任何绕过。

    三条命中其一即判定：
      1. HTTP 401 / 403
      2. 落地 URL 路径含 login / cas / sso / auth / passport / oauth
      3. 短页面的标题或正文出现「统一身份认证」「请先登录」等字样
    """
    if status in (401, 403):
        return True
    p = urlparse(final_url or "").path.lower()
    if re.search(r"/(login|cas|sso|auth|passport|oauth)(/|\.|$)", p):
        return True
    if text and len(text) < 5000:
        m = re.search(r"<title[^>]*>(.*?)</title>", text, re.S | re.I)
        title = re.sub(r"\s+", "", m.group(1)) if m else ""
        if re.search(r"(统一身份认证|用户登录|账号登录|login)", title, re.I):
            return True
        if re.search(r"(统一身份认证登录|请先登录后访问|请登录后查看|请登录后继续)", text):
            return True
    return False


def is_cas_redirect(text):
    """正文页是「整页 JS 跳到 CAS 登录」的形态（比 302 更隐蔽）。"""
    if not text:
        return False
    head = text[:1200]
    return ("clogin.jsp" in head or "cas/clogin" in head
            or re.search(r"location\.href\s*=\s*['\"][^'\"]*(?:login|cas)", head, re.I) is not None)


# ---------------------------------------------------------------- 文章链接识别
def article_id(resolved):
    """从文章 URL 提到新闻 ID —— 用于回填发布日期（博达系的关键技巧）。"""
    q = urlparse(resolved)
    m = re.search(r"wbnewsid=(\d+)", q.query or "")
    if m:
        return m.group(1)
    m = re.search(r"/(\d{4,})\.(?:htm|html|jsp)$", q.path)
    if m:
        return m.group(1)
    m = re.search(r"(?:^|&)aid=(\d+)", q.query or "")
    if m:
        return m.group(1)
    return ""


def is_article_link(resolved):
    """判断一个 URL 是不是通知正文页（而不是栏目列表页或导航链接）。

    博达系实测有四种形态：
      /info/<栏目ID>/<新闻ID>.htm         静态路径，最常见
      ?wbnewsid=<新闻ID>                  动态查询
      ?aid=<ID>                           档案馆手机版
      /<新闻ID>.htm                       少数站的简化路径
    """
    p = urlparse(resolved)
    if re.search(r"/(info|news|content)/\d+/\d+\.(htm|html|jsp)$", p.path):
        return True
    if "wbnewsid=" in (p.query or ""):
        return True
    if re.search(r"/(\d{4,})\.htm$", p.path):
        return True
    if re.search(r"(^|&)aid=\d+", p.query or ""):
        return True
    return False


# ---------------------------------------------------------------- 落盘
def dump_json(path, obj):
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def load_json(path, default=None):
    p = Path(path)
    if not p.is_file():
        return default
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return default


def load_sites():
    """读站点清单。

    优先用 data/sites.json（版本化的事实数据，随仓库分发），
    其次用运行时生成的 sites.json，都没有才返回 None。
    这样 clone 之后不必先联网跑一遍 extract_footer 就能用。
    """
    return load_json(SITES_DEFAULT) or load_json(SITES_JSON)


def load_jsonl(path):
    """读逐行 JSON 文件，坏行跳过但不静默 —— 返回 (记录列表, 坏行列表)。"""
    recs, bad = [], []
    p = Path(path)
    if not p.is_file():
        return recs, bad
    with open(p, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                recs.append(json.loads(line))
            except Exception as e:
                bad.append((i, str(e)))
    return recs, bad


def append_jsonl(path, obj):
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def write_jsonl(path, recs):
    """原子重写：先写临时文件再替换，中断不会留下半截文件。"""
    p = Path(path)
    tmp = p.with_suffix(p.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    os.replace(tmp, p)


def safe_name(s, maxlen=60):
    """清洗成安全的文件/目录名（Windows 保留字符、结尾点空格都要处理）。"""
    s = re.sub(r"[\\/:*?\"<>|\r\n\t]", "_", s or "")
    s = re.sub(r"\s+", " ", s).strip(" .")
    return (s[:maxlen] or "untitled").strip(" .")


def human_size(n):
    if n > 1048576:
        return f"{n / 1048576:.1f} MB"
    if n > 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


def setup_console():
    """Windows 控制台默认 GBK，输出中文会炸 —— 统一切成 UTF-8。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
