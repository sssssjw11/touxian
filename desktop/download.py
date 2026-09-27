# -*- coding: utf-8 -*-
"""第 4 步：把通知的「正文图文 + 附件」下载到本地 -> download/

严格约束（按用户要求）
  1. 单线程串行，绝不并发；每个请求之间随机抖动延时
  2. 完整浏览器请求头 + Cookie 会话；抓资源时带 Referer = 文章地址
  3. 命中统一身份认证 / CAS 登录跳转的页面直接跳过并记录，不做任何绕过
  4. 断点续跑：每条通知的结果追加进 _manifest.jsonl，重跑自动跳过已完成项

产出目录结构
  download/
    _manifest.jsonl                       每行一条结果（含状态、文件数、资源清单）
    _download.log                         运行日志
    README.txt                            交付说明（由 pack.py 生成）
    索引.html                             离线浏览页（由 postprocess.py 生成）
    <单位>/<日期>__<编号>__<标题片段>/
        meta.json          元数据（标题/日期/单位/栏目/来源链接/资源清单）
        内容.md            正文（Markdown，图片指向本地）
        正文.html          正文 HTML 快照（保真，图片已本地化）
        图片/
        附件/

两个正文页的坑（都在本项目实测遇到，已内置处理）
  · <div id="vsb_content"> 存在但取不到文字 —— 内容其实是 showVsbpdfIframe 内嵌的
    PDF，预览图在 vsb_pdf_image_data 里。这类页面必须把那个 PDF 当附件抓下来，
    否则等于没抓到正文（本项目命中 2 条，期刊社）。代码里走 mode="pdf" 分支。
  · 附件链接统一走 /system/_content/download.jsp?urltype=news.DownloadAttachUrl&...，
    文件名只能从响应头 Content-Disposition 取（URL 本身没有扩展名）。
    取不到扩展名 = 落盘成 download__<hash>.jsp = 中间页（验证码墙或已失效），
    由 finalize.py 统一甄别。

用法：
  python download.py --limit 8          # 先小批试跑
  python download.py                    # 全量（可随时中断后续跑）
  python download.py --days 30          # 只下载最近 30 天
  python download.py --since 2026-08-22 --until 2026-09-22
  python download.py --prune            # 把范围外的已下载目录移到 _范围外/（不删除）
  python download.py --only rsc         # 只处理域名含 rsc 的单位
  python download.py --no-assets        # 只存正文，不下载图片附件
  python download.py --retry-failed     # 重试上次失败/跳过的条目
  python download.py --dry-run          # 只统计不下载
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sys
import time
from collections import Counter
from urllib.parse import unquote, urljoin, urlparse

from bs4 import BeautifulSoup, NavigableString, Tag

from _common import (ASSET_DELAY, DOC_EXT, DOWNLOAD, IMG_EXT, OUT, PAGE_DELAY,
                     add_date_args, date_window, dump_json, fetch, human_size,
                     is_cas_redirect, jitter, load_jsonl, new_session,
                     safe_name, setup_console, write_jsonl)

MANIFEST = DOWNLOAD / "_manifest.jsonl"
LOGFILE = DOWNLOAD / "_download.log"

CONTENT_SELECTORS = [
    "#vsb_content", ".v_news_content", "div.v_news_content",
    "#vsb_content_2", ".content", "#content", ".article-content",
    ".news_content", "#news_content", ".con_txt", ".article_con",
]

BLOCK_TAGS = {"div", "p", "li", "ul", "ol", "table", "tr", "td", "th",
              "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "blockquote", "pre"}


# ------------------------------------------------------------ 基础
def log(msg, echo=True):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    if echo:
        print(line, flush=True)
    with open(LOGFILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def file_ext(url, content_type=""):
    """从 URL 路径或 Content-Type 推断扩展名。"""
    import mimetypes
    ext = os.path.splitext(urlparse(url).path)[1].lower()
    if ext in IMG_EXT or ext in DOC_EXT:
        return ext
    if content_type:
        ct = content_type.split(";")[0].strip().lower()
        return {v: k for k, v in mimetypes.types_map.items()}.get(ct, "")
    return ""


def download_file(sess, url, dest_dir, referer, timeout=40, tries=2):
    """下载单个资源，返回 (本地文件名, 字节数, 错误)。

    文件名策略：优先 Content-Disposition，其次 URL 末段，最后兜底 "file"；
    再加上 URL 的 md5 前 8 位防重名（同一页面可能有两个同名附件）。
    """
    os.makedirs(dest_dir, exist_ok=True)
    for i in range(tries):
        try:
            r = sess.get(url, headers={"Referer": referer}, timeout=timeout,
                         allow_redirects=True, stream=True)
            if r.status_code != 200:
                if i < tries - 1:
                    time.sleep(1.0 + i)
                    continue
                return None, 0, f"HTTP{r.status_code}"

            ct = r.headers.get("Content-Type", "")
            if "text/html" in ct.lower() and "download" not in url.lower():
                return None, 0, "返回的是HTML不是文件"

            name = None
            cd = r.headers.get("Content-Disposition", "")
            m = re.search(r"filename\*?=(?:UTF-8''|utf-8'')?\"?([^\";]+)", cd, re.I)
            if m:
                name = unquote(m.group(1).strip().strip('"'))
            if not name:
                name = unquote(os.path.basename(urlparse(url).path)) or "file"
            if not os.path.splitext(name)[1]:
                e = file_ext(url, ct)
                if e:
                    name += e

            hh = hashlib.md5(url.encode("utf-8")).hexdigest()[:8]
            base, ext = os.path.splitext(name)
            fname = f"{safe_name(base, 60)}__{hh}{ext}"
            path = os.path.join(dest_dir, fname)

            n = 0
            with open(path, "wb") as f:
                for chunk in r.iter_content(65536):
                    if chunk:
                        f.write(chunk)
                        n += len(chunk)
            return fname, n, None
        except Exception as e:
            if i < tries - 1:
                time.sleep(1.0 + i)
                continue
            return None, 0, f"{type(e).__name__}: {e}"
    return None, 0, "unknown"


# ------------------------------------------------------------ 正文抽取
def find_content_node(soup):
    """按选择器列表找正文容器；都不中就用「最大且不含嵌套 div 的文本块」兜底。"""
    for sel in CONTENT_SELECTORS:
        try:
            node = soup.select_one(sel)
        except Exception:
            node = None
        if node is not None and len(node.get_text(strip=True)) >= 40:
            return node
    best, best_len = None, 0
    for div in soup.find_all("div"):
        if div.find("div"):
            continue
        L = len(div.get_text(strip=True))
        if L > best_len:
            best, best_len = div, L
    return best if best_len >= 100 else None


def md_from_node(node, img_map, link_keep=True):
    """把正文节点转成 Markdown。img_map: 绝对图片URL -> 本地相对路径"""
    out = []
    node_base = getattr(md_from_node, "_base", "")

    def inline(el):
        parts = []
        for c in el.children:
            if isinstance(c, NavigableString):
                parts.append(re.sub(r"\s+", " ", str(c)))
            elif isinstance(c, Tag):
                if c.name in ("script", "style"):
                    continue
                if c.name == "br":
                    parts.append("\n")
                elif c.name == "img":
                    src = urljoin(node_base, c.get("src") or c.get("data-src") or "")
                    if src in img_map:
                        parts.append(f"![]({img_map[src]})")
                    elif src:
                        parts.append(f"![]({src})")
                elif c.name == "a":
                    t = re.sub(r"\s+", " ", c.get_text(" ", strip=True))
                    href = urljoin(node_base, c.get("href") or "")
                    if t:
                        parts.append(f"[{t}]({href})" if link_keep else t)
                else:
                    parts.append(inline(c))
        return "".join(parts)

    def walk(el, depth=0):
        for c in el.children:
            if isinstance(c, NavigableString):
                t = re.sub(r"\s+", " ", str(c)).strip()
                if t:
                    out.append(t)
                continue
            if not isinstance(c, Tag) or c.name in ("script", "style"):
                continue
            if c.name in ("h1", "h2", "h3", "h4", "h5", "h6"):
                t = inline(c).strip()
                if t:
                    out.append("\n" + "#" * int(c.name[1]) + " " + t + "\n")
            elif c.name == "p":
                t = inline(c).strip()
                if t:
                    out.append("\n" + t + "\n")
            elif c.name == "li":
                t = inline(c).strip()
                if t:
                    out.append("- " + t)
            elif c.name == "table":
                rows = []
                for tr in c.find_all("tr"):
                    cells = [re.sub(r"\s+", " ", td.get_text(" ", strip=True))
                             for td in tr.find_all(["td", "th"])]
                    if any(cells):
                        rows.append("| " + " | ".join(cells) + " |")
                if rows:
                    out.append("\n" + "\n".join(rows) + "\n")
            elif c.name == "img":
                src = urljoin(node_base, c.get("src") or c.get("data-src") or "")
                if src in img_map:
                    out.append(f"\n![]({img_map[src]})\n")
                elif src:
                    out.append(f"\n![]({src})\n")
            elif c.name == "a":
                t = inline(c).strip()
                if t:
                    out.append(t)
            elif c.name in BLOCK_TAGS:
                walk(c, depth + 1)
                out.append("")
            else:
                t = inline(c).strip()
                if t:
                    out.append(t)

    walk(node)
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(out))
    return text.strip()


# ------------------------------------------------------------ 单条处理
def article_no(url):
    """从 URL 取一个稳定的编号，用于目录命名（取不到就用 URL 哈希）。"""
    m = re.search(r"wbnewsid=(\d+)", urlparse(url).query or "")
    if m:
        return m.group(1)
    m = re.search(r"/(\d{4,})\.htm$", urlparse(url).path)
    if m:
        return m.group(1)
    m = re.search(r"(?:^|&)aid=(\d+)", urlparse(url).query or "")
    if m:
        return m.group(1)
    return hashlib.md5(url.encode()).hexdigest()[:8]


def process_one(sess, row, root, do_assets=True, as_delay=ASSET_DELAY):
    """处理一条通知，返回 (记录, 1/0)。记录会被追加进 manifest。"""
    url, unit, title = row["链接"], row["单位"], row["标题"]
    date = row["发布日期"] or "0000-00-00"
    p = urlparse(url)

    folder = f"{safe_name(unit, 24)}/{date}__{article_no(url)}__{safe_name(title, 44)}"
    dest = os.path.join(root, folder)

    rec = {"url": url, "unit": unit, "title": title, "date": date, "dir": folder,
           "status": "", "images": 0, "attach": 0, "bytes": 0, "chars": 0, "note": ""}

    st, final, text, err = fetch(sess, url, referer=f"{p.scheme}://{p.netloc}/")
    if err:
        rec["status"] = "失败"
        rec["note"] = err
        return rec, 0
    if st in (401, 403) or is_cas_redirect(text):
        rec["status"] = "需认证-跳过"
        return rec, 0
    if st != 200 or not text:
        rec["status"] = f"HTTP{st}"
        return rec, 0

    soup = BeautifulSoup(text, "lxml")
    node = find_content_node(soup)

    # 博达的「PDF 正文」形态
    pdf_body = None
    m = re.search(r'showVsbpdfIframe\(\s*["\']([^"\']+?\.pdf)["\']', text, re.I)
    if m:
        pdf_body = urljoin(final, m.group(1))
    pdf_imgs = []
    m2 = re.search(r"vsb_pdf_image_data\s*=\s*(\[[^\]]*\])", text, re.I)
    if m2:
        try:
            pdf_imgs = [urljoin(final, u) for u in json.loads(m2.group(1)) if isinstance(u, str)]
        except Exception:
            pdf_imgs = []

    node_len = len(node.get_text(strip=True)) if node is not None else 0
    if pdf_body and node_len < 40:
        mode = "pdf"
    elif node is None:
        rec["status"] = "无正文"
        return rec, 0
    else:
        mode = "html"

    tf = soup.find("title")
    rec["page_title"] = re.sub(r"\s+", " ", tf.get_text(strip=True)) if tf else ""
    rec["mode"] = mode
    os.makedirs(dest, exist_ok=True)

    # ---- 收集资源
    imgs, atts = [], []
    if mode == "pdf":
        imgs, atts = list(pdf_imgs), [pdf_body]
    else:
        for img in node.find_all("img"):
            src = img.get("src") or img.get("data-src") or img.get("data-original") or ""
            if not src or src.startswith("data:"):
                continue
            absu = urljoin(final, src)
            if urlparse(absu).scheme in ("http", "https") and absu not in imgs:
                imgs.append(absu)

        for a in node.find_all("a", href=True):
            href = a["href"].strip()
            if not href or href.startswith(("javascript:", "#", "mailto:", "tel:")):
                continue
            absu = urljoin(final, href)
            if urlparse(absu).scheme not in ("http", "https"):
                continue
            path_l = urlparse(absu).path.lower()
            is_att = (os.path.splitext(path_l)[1] in DOC_EXT
                      or "downloadattachurl" in absu.lower()
                      or "download.jsp" in absu.lower()
                      or "wbfileid=" in absu.lower()
                      or ("/_upload/" in absu.lower()
                          and os.path.splitext(path_l)[1] not in IMG_EXT))
            if is_att and absu not in atts:
                atts.append(absu)

    img_map, att_list, total_bytes = {}, [], 0

    n_img_fail = n_att_fail = 0

    if do_assets:
        for i, iu in enumerate(imgs, 1):
            fn, n, e = download_file(sess, iu, os.path.join(dest, "图片"), referer=final)
            if fn:
                img_map[iu] = f"图片/{fn}"
                total_bytes += n
                rec["images"] += 1
            else:
                img_map[iu] = iu       # 失败就保留原始外链，正文仍可读
                n_img_fail += 1
                rec["note"] = (rec["note"] + f" 图失败({e})").strip()
            if i < len(imgs):
                jitter(as_delay)

        for i, au in enumerate(atts, 1):
            fn, n, e = download_file(sess, au, os.path.join(dest, "附件"), referer=final)
            if fn:
                att_list.append({"url": au, "file": f"附件/{fn}", "size": n})
                total_bytes += n
                rec["attach"] += 1
            else:
                att_list.append({"url": au, "file": None, "size": 0, "error": e})
                n_att_fail += 1
            if i < len(atts):
                jitter(as_delay)
    else:
        n_img_fail, n_att_fail = len(imgs), len(atts)

    # 资源计数必须写回，否则后面的核对脚本会误判成「文件缺失」
    rec["images_failed"] = n_img_fail
    rec["attach_failed"] = n_att_fail
    rec["images_ref"] = len(imgs)
    rec["attach_ref"] = len(atts)

    # ---- 写正文
    if mode == "pdf":
        pdf_local = next((a["file"] for a in att_list
                          if a.get("url") == pdf_body and a.get("file")), None)
        md = "本通知的正文是 PDF 文件（原页面用内嵌 PDF 方式发布），已一并下载到「附件/」目录。\n"
        if pdf_local:
            md += f"\n- [{os.path.basename(pdf_local)}]({pdf_local})\n"
        elif pdf_body:
            md += f"\n- 下载失败，请访问原页面：{pdf_body}\n"
    else:
        md_from_node._base = final
        md = md_from_node(node, img_map) or node.get_text("\n", strip=True)

    header = [f"# {title}", "",
              f"- 单位：{unit}（{row['类别']}）",
              f"- 栏目：{row['栏目']}",
              f"- 发布日期：{date or '—'}",
              f"- 来源：{url}", ""]
    if att_list:
        header += ["## 附件", ""]
        for a in att_list:
            header.append(f"- [{os.path.basename(a['file'])}]({a['file']})" if a["file"]
                          else f"- [下载失败] {a['url']}")
        header.append("")

    (dest / "内容.md").write_text("\n".join(header) + "---\n\n" + md + "\n", encoding="utf-8")

    body_html = str(node) if node is not None else (
        "<p>本通知正文为 PDF 文件，请见「附件」目录。</p>"
        + "".join(f'<img src="{img_map.get(u, u)}">' for u in imgs))
    (dest / "正文.html").write_text(
        '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
        f"<title>{title}</title></head><body>\n{body_html}\n</body></html>",
        encoding="utf-8")

    rec.update(chars=len(md), bytes=total_bytes, status="OK",
               assets={"images": imgs, "attachments": att_list})

    meta = {k: rec[k] for k in ("url", "unit", "title", "date", "dir", "status", "images",
                                "attach", "bytes", "chars", "page_title",
                                "images_ref", "images_failed",
                                "attach_ref", "attach_failed") if k in rec}
    meta["类别"] = row["类别"]
    meta["栏目"] = row["栏目"]
    meta["图片清单"] = img_map
    meta["附件清单"] = att_list
    dump_json(dest / "meta.json", meta)

    return rec, 1


# ------------------------------------------------------------ manifest
def load_done():
    """已完成记录，按 URL 索引（用于断点续跑）。"""
    recs, bad = load_jsonl(MANIFEST)
    return {r["url"]: r for r in recs if "url" in r}, bad


def append_manifest(rec):
    from _common import append_jsonl
    append_jsonl(MANIFEST, rec)


def prune_out_of_range(rows, since, until):
    """把范围外的已下载目录移到 download/_范围外/，并重写 manifest。

    这是「安全」的清理：只移动不删除，随时可以搬回来。
    """
    keep_urls = {r["链接"] for r in rows if in_range(r, since, until)}
    done, _ = load_done()

    keep, move = [], []
    for r in done.values():
        (keep if r["url"] in keep_urls else move).append(r)

    aside = DOWNLOAD / "_范围外"
    n_moved = n_missing = 0
    for r in move:
        src = DOWNLOAD / r.get("dir", "")
        if r.get("dir") and src.is_dir():
            dst = aside / r["dir"]
            dst.parent.mkdir(parents=True, exist_ok=True)
            if dst.exists():
                dst = dst.with_name(dst.name + "__dup")
            try:
                shutil.move(str(src), str(dst))
                n_moved += 1
            except Exception as e:
                log(f"  移动失败 {src}: {e}")
        else:
            n_missing += 1
    write_jsonl(MANIFEST, keep)
    log(f"范围清理：保留 {len(keep)} 条，移出 {n_moved} 个目录"
        f"（另有 {n_missing} 条无目录）-> {aside}")


def in_range(row, since, until):
    if not since:
        return True
    d = row.get("发布日期") or ""
    return bool(d) and since <= d <= until


# ------------------------------------------------------------ 主流程
def main():
    setup_console()
    global DOWNLOAD, MANIFEST, LOGFILE

    ap = argparse.ArgumentParser(description="下载通知的正文图文与附件")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--only", default="", help="只处理站点 URL 含该子串的单位")
    ap.add_argument("--no-assets", action="store_true", help="只存正文")
    ap.add_argument("--retry-failed", action="store_true")
    ap.add_argument("--force-match", default="", help="URL 含该子串的条目强制重下")
    ap.add_argument("--outdir", default="", help="输出目录（默认 ./download）")
    ap.add_argument("--prune", action="store_true",
                    help="把时间范围外的已下载目录移到 _范围外/（只移动不删除）")
    ap.add_argument("--dry-run", action="store_true", help="只统计不下载")
    add_date_args(ap)
    args = ap.parse_args()

    if args.outdir:
        DOWNLOAD = __import__("pathlib").Path(args.outdir).resolve()
        MANIFEST = DOWNLOAD / "_manifest.jsonl"
        LOGFILE = DOWNLOAD / "_download.log"
    DOWNLOAD.mkdir(parents=True, exist_ok=True)

    csv_path = OUT / "notices.csv"
    if not csv_path.is_file():
        sys.exit("缺少 out/notices.csv，请先运行 python scrape.py")
    rows = list(csv.DictReader(open(csv_path, encoding="utf-8-sig")))
    if args.only:
        rows = [r for r in rows if args.only in r["站点"]]

    since, until = date_window(days=args.days, since=args.since, until=args.until)
    if since:
        before = len(rows)
        rows = [r for r in rows if in_range(r, since, until)]
        log(f"时间范围 {since} ~ {until}：{before} 条 -> {len(rows)} 条"
            f"（覆盖 {len({r['单位'] for r in rows})} 个单位）")

    if args.prune:
        prune_out_of_range(rows, since, until)
        # prune 后重新读一遍，范围可能已被收敛
        rows = [r for r in rows if in_range(r, since, until)]

    if args.limit:
        rows = rows[: args.limit]

    done, bad = load_done()
    if bad:
        log(f"[警告] manifest 有 {len(bad)} 行解析失败，已跳过")

    if args.retry_failed:
        todo = [r for r in rows
                if done.get(r["链接"], {}).get("status") in (None, "", "失败", "无正文")
                or str(done.get(r["链接"], {}).get("status", "")).startswith("HTTP")]
    else:
        todo = [r for r in rows if r["链接"] not in done]

    if args.force_match:
        forced = [r for r in rows if args.force_match in r["链接"]]
        fset = {f["链接"] for f in forced}
        todo = forced + [r for r in todo if r["链接"] not in fset]
        log(f"--force-match {args.force_match!r}：强制重下 {len(forced)} 条")

    if args.dry_run:
        log(f"[dry-run] 范围内 {len(rows)} 条，已完成 {len(rows) - len(todo)} 条，"
            f"待下载 {len(todo)} 条")
        return

    log(f"===== 开始：范围内 {len(rows)} 条，已完成 {len(rows) - len(todo)} 条，"
        f"本次待处理 {len(todo)} 条 =====")
    log(f"资源下载：{'关闭' if args.no_assets else '开启'}；"
        f"页面延时 {PAGE_DELAY}；资源延时 {ASSET_DELAY}；并发 1")

    sess = new_session()
    t0 = time.time()
    stat, nbytes = Counter(), 0

    for i, row in enumerate(todo, 1):
        rec, _ = process_one(sess, row, str(DOWNLOAD), do_assets=not args.no_assets)
        append_manifest(rec)
        stat[rec["status"]] += 1
        nbytes += rec.get("bytes", 0)

        flag = {"OK": "OK  ", "需认证-跳过": "AUTH", "失败": "FAIL",
                "无正文": "NONE"}.get(rec["status"], "??  ")
        el = time.time() - t0
        eta = (el / i) * (len(todo) - i)
        log(f"[{i}/{len(todo)}] {flag} {rec['unit'][:10]:<12s} "
            f"图{rec['images']:>2d} 附{rec['attach']:>2d} 字{rec['chars']:>5d} | "
            f"{rec['title'][:30]:<32s} {rec['status']} | "
            f"已用{el / 60:.1f}分 预计剩余{eta / 60:.1f}分")

        if not args.only and i < len(todo):
            jitter(PAGE_DELAY)

    log(f"===== 结束：{dict(stat)} | 本次下载 {human_size(nbytes)} | "
        f"耗时 {(time.time() - t0) / 60:.1f} 分钟 =====")

    allrec, _ = load_done()
    log("累计状态：" + json.dumps(dict(Counter(v.get("status", "") for v in allrec.values())),
                                 ensure_ascii=False))
    print("\n下一步：python postprocess.py   # 本地化图片 + 生成索引页\n"
          "        python finalize.py      # 去重 + 甄别验证码/失效附件")


if __name__ == "__main__":
    main()
