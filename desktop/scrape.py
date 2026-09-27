# -*- coding: utf-8 -*-
"""第 2 步：抓取各学院/部门官网的「通知公告」条目 -> out/notices.csv

针对该校站点群 CMS（博达系）的结构特点做了专门适配，这些技巧是可复用的：

  · 栏目标题是**纯 <li>/<span>，不含 <a>**
      例如 <li class="sh">教师公告</li>、<div class="tit">学生公告</div>、
      <div class="indexnews3_tit">教师公告 <font>/ TEACHER</font></div>
      判定：不含链接 + 不含块级子元素 + 去掉英文数字后中文长度 ≤12 且含「通知/公告/公示」
  · 栏目「更多 >」链接与标题**按顺序一一对应**，class 多为 more / se，锚文本可能为空
  · **日期藏在前置隐藏节点**：<div id="time<新闻ID>">2026年07月10日</div>
      用文章 URL 里的新闻 ID 反查回填日期，比按文本模糊匹配可靠得多。
      这一条是本次数据质量的关键。

运行约束（严格按用户要求）
  1. 单线程串行，绝不并发
  2. 请求间隔随机抖动，完整浏览器请求头 + Cookie 会话 + Referer 链
  3. 需登录 / 统一身份认证的页面识别后跳过，不做任何绕过
  4. 单站点最多 1 个首页 + N 个列表页，克制、不压站
  5. 支持断点续跑（每个站点的结果落 out/site_<域名>.json，重跑自动跳过）

用法：
  python scrape.py --limit 5          # 先小批试跑
  python scrape.py                    # 全量（可随时中断后续跑）
  python scrape.py --only ems         # 只抓域名含 ems 的站点
  python scrape.py --force            # 忽略已有结果，全部重抓
  python scrape.py --aggregate-only   # 跳过抓取，只用已有 site_*.json 重新汇总
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from collections import Counter
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

from _common import (CACHE, MAX_LIST_PAGES, OUT, PAGE_DELAY, SITES_JSON,
                     article_id, dump_json, fetch, host_key, is_article_link,
                     is_auth_wall, jitter, load_json, load_sites, new_session,
                     norm_date, setup_console)

NOTICE_KW = ["通知公告", "公告通知", "通知通告", "公示公告", "信息公告", "新闻公告",
             "通知公示", "通知", "公告", "公示"]
STRONG_KW = ["通知公告", "公告通知", "通知通告", "公示公告", "信息公告", "新闻公告", "通知公示"]
PATH_KW = ["tzgg", "gonggao", "ggtz", "gsgg", "xsgg", "jsgg", "xygg", "bgg",
           "notice", "notices", "announce"]

BLOCK_TAGS = ["li", "ul", "ol", "dl", "dt", "dd", "table", "tr", "td", "th",
              "div", "p", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6"]


# ------------------------------------------------------------ 日期映射
def build_date_map(soup):
    """收集 <X id="time<新闻ID>">日期</X> 映射：新闻ID -> YYYY-MM-DD

    这是博达系最可靠的日期来源 —— 列表页每篇文章前面都挂着一个隐藏节点，
    节点 id 里带着新闻 ID，与文章 URL 里的 ID 完全对应。
    """
    dm = {}
    for el in soup.find_all(id=True):
        m = re.match(r"^time(\d+)$", el.get("id") or "")
        if not m:
            continue
        d = norm_date(el.get_text(strip=True))
        if d:
            dm[m.group(1)] = d
    return dm


def ancestor_texts(a, levels=3):
    out = []
    node = a
    for _ in range(levels):
        node = node.parent
        if node is None or getattr(node, "name", None) in ("body", "html", "[document]"):
            break
        out.append(node.get_text(" ", strip=True))
    return out


# ------------------------------------------------------------ 条目抽取
def extract_items(scope, base_url, column, date_map):
    """从一段 DOM 里抽出所有「通知条目」。

    返回 [{"title","url","date","column"}]，日期取三重兜底：
      1. 用文章 ID 查隐藏节点映射（最准）
      2. 爬 4 层父级找前置兄弟节点的日期文本
      3. 向上 3 层祖先的整段文本里找日期
    """
    base_host = urlparse(base_url).netloc.lower()
    items, seen = [], set()

    for a in scope.find_all("a", href=True):
        href = a["href"].strip()
        if not href or href.startswith(("javascript:", "#", "mailto:", "tel:")):
            continue
        resolved = urljoin(base_url, href)
        if urlparse(resolved).netloc.lower() != base_host:
            continue
        if not is_article_link(resolved):
            continue

        title = re.sub(r"\s+", " ", (a.get("title") or a.get_text(strip=True) or "")).strip()
        if len(title) < 6 or title in ("更多", "查看更多"):
            continue
        if resolved in seen:
            continue
        seen.add(resolved)

        date = date_map.get(article_id(resolved), "")

        if not date:
            prev = a
            for _ in range(4):
                prev = prev.find_parent()
                if prev is None:
                    break
                sib = prev.find_previous_sibling()
                if sib is not None:
                    d = norm_date(sib.get_text(" ", strip=True))
                    if d:
                        date = d
                        break

        if not date:
            for t in ancestor_texts(a, 3):
                d = norm_date(t)
                if d:
                    date = d
                    break

        items.append({"title": title, "url": resolved, "date": date, "column": column})

    return items


def find_notice_blocks(soup, base_url):
    """定位首页里的「通知/公告」栏目块，返回 [{"labels", "node", "more"}]。

    步骤：先找栏目标题节点（纯文本、不含链接、含通知类关键词），
    再向上爬最多 7 层，找到第一个含 ≥2 个文章链接的祖先作为栏目容器。
    一个容器可能被多个标题命中（如「教师公告」和「学生公告」在同一个 tab 里），
    此时把标题合并进 labels。
    """
    headings = []
    for el in soup.find_all(True):
        if el.name in ("script", "style", "a", "html", "body", "head"):
            continue
        if el.find("a") is not None:
            continue
        if el.find(BLOCK_TAGS) is not None:
            continue
        raw = el.get_text(strip=True)
        if not raw or len(raw) > 24:
            continue
        t = re.sub(r"[A-Za-z0-9\s/|:：\-—·（）()\[\]<>#]+", "", raw)
        if not t or len(t) > 12:
            continue
        if not any(k in t for k in NOTICE_KW):
            continue
        headings.append((el, t))

    blocks, by_id = [], {}
    for h, htext in headings:
        node, container = h, None
        for _ in range(7):
            node = node.parent
            if node is None or getattr(node, "name", None) in ("body", "html", "[document]"):
                break
            arts = [x for x in node.find_all("a", href=True)
                    if is_article_link(urljoin(base_url, x["href"].strip()))]
            if len(arts) >= 2:
                container = node
                break
        if container is None:
            continue

        key = id(container)
        if key in by_id:
            blk = by_id[key]
            if htext not in blk["labels"]:
                blk["labels"].append(htext)
            continue

        # 栏目块里的「更多 >」链接（用于后续找列表页）
        more = []
        for a in container.find_all("a", href=True):
            txt = a.get_text(strip=True)
            cls = " ".join(a.get("class") or [])
            if re.search(r"更多|查看更多|more", txt, re.I) or re.search(r"\bmore\b|(^|\s)se(\s|$)", cls, re.I):
                u = urljoin(base_url, a["href"].strip())
                if u not in more and not is_article_link(u):
                    more.append(u)

        blk = {"labels": [htext], "node": container, "more": more}
        by_id[key] = blk
        blocks.append(blk)

    return blocks


def pick_list_pages(soup, home_url, blocks):
    """挑出候选的通知列表页，按可信度打分排序。

    三条线索：
      A. 栏目块里的「更多」链接（最可信，路径含 tzgg 等关键词加分）
      B. 全页扫描锚文本就是通知栏目名的链接
      C. 路径含 tzgg/gonggao/notice 等关键词的链接
    """
    home_host = urlparse(home_url).netloc.lower()
    cands = {}

    def add(url, score, label):
        pu = urlparse(url)
        if pu.netloc.lower() != home_host:
            return
        if is_article_link(url):
            return
        if not re.search(r"\.(htm|html|jsp)$", pu.path, re.I):
            return
        k = pu.path + ("?" + pu.query if pu.query else "")
        if k not in cands or cands[k]["score"] < score:
            cands[k] = {"url": url, "score": score, "label": label}

    for b in blocks:
        lbl = "/".join(b["labels"])
        for m in b["more"]:
            s = 11 if any(k in urlparse(m).path.lower() for k in PATH_KW) else 9
            add(m, s, lbl)

    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        if not href or href.startswith(("javascript:", "#", "mailto:", "tel:")):
            continue
        u = urljoin(home_url, href)
        own = (a.get("title") or a.get_text(strip=True) or "").strip()
        if re.search(r"更多|查看更多|more|>>", own, re.I) or len(own) > 12:
            own = ""
        pathl = urlparse(u).path.lower()
        if own and any(k in own for k in STRONG_KW):
            add(u, 10, own)
        elif own and any(k in own for k in NOTICE_KW):
            add(u, 7, own)
        elif any(k in pathl for k in PATH_KW):
            add(u, 8, own or pathl)

    return sorted(cands.values(), key=lambda x: -x["score"])


# ------------------------------------------------------------ 单站点
def scrape_site(site, force=False, max_lists=MAX_LIST_PAGES):
    home = site["home"]
    hk = host_key(home)
    rec_path = OUT / f"site_{hk}.json"

    if rec_path.is_file() and not force:
        cached = load_json(rec_path)
        if cached:
            return cached

    rec = {"group": site["group"], "name": site["name"], "home": home,
           "status": "", "requests": 0, "notices": [], "columns": [], "log": []}
    sess = new_session()

    st, final, text, err = fetch(sess, home)
    rec["requests"] += 1
    rec["home_status"] = st
    rec["home_final"] = final

    if err:
        rec["status"] = "访问失败"
        rec["log"].append(f"首页 {err}")
        return _save(rec, rec_path)
    if is_auth_wall(st, final, text):
        rec["status"] = "需认证-跳过"
        rec["log"].append(f"需认证 HTTP{st} {final}")
        return _save(rec, rec_path)
    if st != 200 or not text:
        rec["status"] = f"首页HTTP{st}"
        rec["log"].append(f"首页 HTTP {st}")
        return _save(rec, rec_path)

    (CACHE / f"home_{hk}.html").write_text(text, encoding="utf-8")

    soup = BeautifulSoup(text, "lxml")
    dmap = build_date_map(soup)

    # 1) 首页的公告栏目块
    blocks = find_notice_blocks(soup, final)
    home_items = []
    for b in blocks:
        home_items.extend(extract_items(b["node"], final, "/".join(b["labels"]), dmap))
    if not home_items:
        home_items = extract_items(soup, final, "首页栏目", dmap)
        rec["log"].append("未定位到公告栏目块，改用全页文章链接兜底")
    rec["notices"].extend(home_items)
    rec["log"].append(f"首页公告栏目块 {len(blocks)} 个 / 通知 {len(home_items)} 条")

    # 2) 通知列表页
    lists = pick_list_pages(soup, final, blocks)
    rec["columns"] = [{"label": c["label"], "url": c["url"], "score": c["score"]}
                      for c in lists[:8]]
    rec["log"].append(f"候选列表页 {len(lists)} 个")

    fetched = 0
    for c in lists:
        if fetched >= max_lists:
            break
        st2, final2, text2, err2 = fetch(sess, c["url"], referer=final)
        rec["requests"] += 1
        if err2:
            rec["log"].append(f"列表页失败 {c['url']} [{err2}]")
            continue
        if is_auth_wall(st2, final2, text2):
            rec["log"].append(f"列表页需认证，跳过 {c['url']}")
            fetched += 1
            continue
        if st2 != 200 or not text2:
            rec["log"].append(f"列表页 HTTP{st2} {c['url']}")
            continue
        (CACHE / f"list_{hk}_{fetched}.html").write_text(text2, encoding="utf-8")
        s2 = BeautifulSoup(text2, "lxml")
        its = extract_items(s2, final2, c["label"] or "通知公告", build_date_map(s2))
        rec["notices"].extend(its)
        rec["log"].append(f"列表页[{c['label']}] {len(its)} 条 <- {final2}")
        fetched += 1

    # 3) 去重：同 URL 合并，保留信息更全的一条
    seen, uniq = {}, []
    for it in rec["notices"]:
        k = it["url"]
        if k in seen:
            old = seen[k]
            if not old["date"] and it["date"]:
                old["date"] = it["date"]
            if it["column"] and it["column"] not in old["column"]:
                old["column"] = old["column"] + "/" + it["column"]
            continue
        seen[k] = it
        uniq.append(it)
    rec["notices"] = uniq

    rec["status"] = "OK" if fetched else ("仅首页通知" if uniq else "未取到通知")
    return _save(rec, rec_path)


def _save(rec, path):
    dump_json(path, rec)
    return rec


# ------------------------------------------------------------ 汇总与清洗
# 非通知类的「资料/表格/模板」，以及无日期的鸡汤短句，做一次清洗
JUNK_PAT = re.compile(
    r"(样表|模板|合同书|协议书|审批表|申请书|申请表|审核表|备案表|登记表|"
    r"清单$|指引$|操作说明|办事流程|表格$|下载$|图片$|视频$|图集$)")
NOTICE_TITLE_KW = ["通知", "公告", "公示", "申报", "评选", "评审", "招标", "采购",
                   "安排", "招聘", "报名", "认定", "立项", "结题", "答辩", "考试",
                   "开学", "放假", "值班", "会议", "日程", "讲座", "培训", "结果"]

# 媒体报道类标题，不属于学校自发的通知公告
MEDIA_PAT = re.compile(
    r"^(中国教育在线|温度新闻|学习强国|澎湃新闻|浙江新闻|温州新闻|"
    r"中国教育报|光明日报|中国青年报|潮新闻|浙江教育报|温州日报|"
    r"温都讯|瓯网|人民网|新华网|央视|浙江卫视)\s*[:：]")


def clean_column(col):
    """栏目名可能是多个标题拼起来的，只保留含通知类关键词的部分。"""
    parts = [p for p in re.split(r"[/、|]", col or "") if p.strip()]
    hit = [p for p in parts if any(k in p for k in ("通知", "公告", "公示"))]
    keep = hit or parts
    return "/".join(dict.fromkeys(keep)) or (col or "").strip()


def is_noise(title, date):
    if MEDIA_PAT.match(title):
        return True
    if not date and not any(k in title for k in NOTICE_TITLE_KW):
        return True
    if JUNK_PAT.search(title) and not any(k in title for k in ("通知", "公告", "公示")):
        return True
    return False


def aggregate(no_site):
    all_recs = []
    for f in sorted(os.listdir(OUT)):
        if f.startswith("site_") and f.endswith(".json"):
            r = load_json(OUT / f)
            if r:
                all_recs.append(r)

    rows, dropped = [], 0
    for r in all_recs:
        for n in r["notices"]:
            title = n["title"].strip()
            if is_noise(title, n["date"]):
                dropped += 1
                continue
            rows.append({"类别": r["group"], "单位": r["name"],
                         "栏目": clean_column(n["column"]),
                         "发布日期": n["date"], "标题": title, "链接": n["url"],
                         "站点": r["home"], "站点状态": r["status"]})
    rows.sort(key=lambda x: (x["类别"], x["单位"], x["发布日期"] or "", x["标题"]))

    csv_path = OUT / "notices.csv"
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["类别", "单位", "栏目", "发布日期", "标题",
                                          "链接", "站点", "站点状态"])
        w.writeheader()
        w.writerows(rows)

    dump_json(OUT / "sites_result.json", {"sites": all_recs, "no_site": no_site})
    print(f"（清洗掉 {dropped} 条非通知类噪声）")
    return all_recs, rows


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="抓取温州大学各二级单位官网的通知公告")
    ap.add_argument("--limit", type=int, default=0, help="只抓前 N 个站点")
    ap.add_argument("--only", default="", help="只抓域名含该子串的站点")
    ap.add_argument("--force", action="store_true", help="忽略缓存，重新抓取")
    ap.add_argument("--max-lists", type=int, default=MAX_LIST_PAGES,
                    help=f"单站点最多抓几个列表页（默认 {MAX_LIST_PAGES}）")
    ap.add_argument("--aggregate-only", action="store_true", help="跳过抓取，只重新汇总")
    args = ap.parse_args()

    sites = load_sites()
    if not sites:
        sys.exit("缺少站点清单，请先运行 python extract_footer.py")

    flat, no_site = [], []
    for group, items in sites.items():
        for it in items:
            if it.get("url"):
                flat.append({"group": group, "name": it["name"], "home": it["url"]})
            else:
                no_site.append({"group": group, "name": it["name"]})

    if not args.aggregate_only:
        todo = flat
        if args.only:
            todo = [s for s in todo if args.only in s["home"]]
        if args.limit:
            todo = todo[: args.limit]

        print(f"本次抓取 {len(todo)} 个站点（页脚未挂站 {len(no_site)} 个）")
        print(f"模式：串行 1 并发 · 页面间隔 {PAGE_DELAY[0]}~{PAGE_DELAY[1]}s · "
              f"单站最多 {args.max_lists} 个列表页\n")
        t0 = time.time()
        for i, site in enumerate(todo, 1):
            rec = scrape_site(site, force=args.force, max_lists=args.max_lists)
            flag = {"OK": "OK  ", "需认证-跳过": "AUTH", "访问失败": "FAIL",
                    "仅首页通知": "HOME", "未取到通知": "NONE"}.get(rec["status"], "??  ")
            print(f"[{i}/{len(todo)}] {flag} {rec['group']}·{rec['name']:<24s} "
                  f"通知{len(rec['notices']):>3d}条 请求{rec['requests']}  {rec['status']}")
            for l in rec["log"]:
                print(f"          · {l}")
            sys.stdout.flush()
            if not args.only and i < len(todo):
                jitter(PAGE_DELAY)
        print(f"\n抓取耗时 {time.time() - t0:.0f} 秒")

    all_recs, rows = aggregate(no_site)
    print("\n== 站点状态汇总 ==")
    for k, v in Counter(r["status"] for r in all_recs).most_common():
        print(f"  {k}: {v}")
    withdate = sum(1 for r in rows if r["发布日期"])
    print(f"\n通知条目总数：{len(rows)}（其中有日期 {withdate}）")
    print(f"输出：{OUT / 'notices.csv'}")
    print("下一步：python report.py     # 生成索引报告\n"
          "        python download.py   # 下载正文图文与附件")


if __name__ == "__main__":
    main()
