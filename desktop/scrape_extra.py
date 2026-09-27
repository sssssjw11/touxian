# -*- coding: utf-8 -*-
"""第 3 步（补抓）：处理首轮取不到通知的站点。

为什么需要单独一步：这几个站点的入口不能用页脚给的那个地址，原因已逐个核实 ——

  招生与就业处      页脚给的是「进入页」-> 真实首页 https://zs.wzu.edu.cn/
  档案馆            页脚给的是手机版 /touch/，桌面版首页返回 401 需认证
                    -> 采公开的手机版（该站无独立通知栏，属档案动态/校史栏目）
  发绣研究院        站点没有通知栏 -> 采「新闻动态」/xwdt.htm
  计划财务处        站点没有通知栏 -> 采「工作动态」/gzdt.htm
  心理健康教育中心   站点没有通知栏 -> 采「心闻快递」/xwkd.htm
  温州女子学院      全站 HTTP 403，确认需认证 -> 保持跳过

对「本身没有通知栏」的站点，采集替代栏目，并在栏目名后标注
「（该站无独立通知栏）」，避免误导读者以为那是通知。

遵循同样的串行、限速、真实请求头、Cookie 会话、Referer 链约定。

用法：
  python scrape_extra.py
  python scrape_extra.py --only 档案馆
"""
from __future__ import annotations

import argparse
import sys
from urllib.parse import urljoin

from bs4 import BeautifulSoup

import scrape as S
from _common import CACHE, OUT, PAGE_DELAY, dump_json, fetch, host_key, jitter, \
    new_session, setup_console

TARGETS = [
    {"name": "招生与就业处", "group": "部门",
     "home": "https://zs.wzu.edu.cn/",
     "kw": ["通知公告", "通知", "公告", "公示", "招生", "信息公示"],
     "cols": [("信息公示", "https://zs.wzu.edu.cn/xxgk/xxgs.htm"),
              ("招生动态", "https://zs.wzu.edu.cn/zlxx/zsdt.htm"),
              ("招生政策", "https://zs.wzu.edu.cn/zlxx/zszc.htm")]},
    {"name": "档案馆", "group": "部门",
     "home": "https://daxt.wzu.edu.cn/touch/",
     "kw": ["通知公告", "通知", "公告", "公示", "档案动态", "校史"],
     "cols": [("档案动态", "https://daxt.wzu.edu.cn/touch/child?types=4&mid=1"),
              ("校史钩沉", "https://daxt.wzu.edu.cn/touch/child?types=4&mid=4")]},
    {"name": "发绣研究院", "group": "其他",
     "home": "https://faxiu.wzu.edu.cn/",
     "kw": ["通知公告", "通知", "公告", "公示", "新闻动态"],
     "cols": [("新闻动态", "https://faxiu.wzu.edu.cn/xwdt.htm")]},
    {"name": "计划财务处", "group": "部门",
     "home": "https://jcc.wzu.edu.cn/",
     "kw": ["通知公告", "通知", "公告", "公示", "工作动态"],
     "cols": [("工作动态", "https://jcc.wzu.edu.cn/gzdt.htm")]},
    {"name": "心理健康教育中心", "group": "其他",
     "home": "https://psy.wzu.edu.cn/",
     "kw": ["通知公告", "通知", "公告", "公示", "心闻快递"],
     "cols": [("心闻快递", "https://psy.wzu.edu.cn/xwkd.htm")]},
    {"name": "温州女子学院", "group": "其他",
     "home": "https://wzwoman.wzu.edu.cn/",
     "kw": ["通知公告", "通知", "公告", "公示"],
     "cols": []},
]

MAX_MORE = 5   # 单站最多再跟进几个「更多」页


def run(t, max_more=MAX_MORE):
    print(f"\n===== {t['group']}·{t['name']}  {t['home']}")
    sess = new_session()
    st, final, text, err = fetch(sess, t["home"])
    print(f"  首页 HTTP{st} -> {final}  {err or ''}")

    if err or st != 200 or not text:
        return None
    if S.is_auth_wall(st, final, text):
        print("  需认证，跳过")
        return {"group": t["group"], "name": t["name"], "home": t["home"],
                "status": "需认证-跳过", "requests": 1, "notices": [], "columns": [],
                "log": [f"补抓：HTTP{st}，确认需登录/认证"],
                "home_status": st, "home_final": final}

    (CACHE / f"home_{host_key(t['home'])}.html").write_text(text, encoding="utf-8")

    soup = BeautifulSoup(text, "lxml")
    items = []
    blocks = S.find_notice_blocks(soup, final)
    for b in blocks:
        items.extend(S.extract_items(b["node"], final, "/".join(b["labels"]),
                                     S.build_date_map(soup)))

    cols, fetched = [], 0

    # 已人工核实的直采栏目
    for label, url in t["cols"]:
        st2, f2, t2, e2 = fetch(sess, url, referer=final)
        fetched += 1
        if e2 or st2 != 200 or not t2:
            print(f"    直采栏目[{label}] 不可用 HTTP{st2} {e2 or ''} -> {url}")
            continue
        s2 = BeautifulSoup(t2, "lxml")
        its = S.extract_items(s2, f2, label, S.build_date_map(s2))
        print(f"    直采栏目[{label}] {len(its)} 条 <- {f2}")
        items.extend(its)
        cols.append({"label": label, "url": f2, "score": 9})
        jitter((1.5, 3.0))

    # 栏目块自带的「更多」链接
    for b in blocks:
        for m in b.get("more", []):
            if fetched >= max_more:
                break
            st2, f2, t2, e2 = fetch(sess, m, referer=final)
            fetched += 1
            if e2 or st2 != 200 or not t2:
                continue
            s2 = BeautifulSoup(t2, "lxml")
            its = S.extract_items(s2, f2, "/".join(b["labels"]), S.build_date_map(s2))
            if its:
                print(f"    更多页[{'/'.join(b['labels'])}] {len(its)} 条 <- {f2}")
                items.extend(its)
                cols.append({"label": "/".join(b["labels"]), "url": f2, "score": 8})
            jitter((1.5, 3.0))

    if not items:
        items = S.extract_items(soup, final, "首页栏目", S.build_date_map(soup))
        print(f"    未定位栏目块，全页兜底 {len(items)} 条")

    seen, uniq = set(), []
    for it in items:
        if it["url"] in seen:
            continue
        seen.add(it["url"])
        uniq.append(it)

    # 替代栏目打标，避免误导
    for it in uniq:
        c = it["column"]
        if not any(k in c for k in ("通知", "公告", "公示")):
            it["column"] = c + "（该站无独立通知栏）"

    print(f"  => {len(uniq)} 条")
    return {"group": t["group"], "name": t["name"], "home": t["home"],
            "status": "OK" if uniq else "未取到通知", "requests": 1 + fetched,
            "notices": uniq, "columns": cols,
            "log": [f"补抓入口 {t['home']}", f"栏目块 {len(blocks)} 个 / 共 {len(uniq)} 条"],
            "home_status": st, "home_final": final}


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="补抓首轮未取到通知的站点")
    ap.add_argument("--only", default="", help="只处理名称含该子串的站点")
    ap.add_argument("--max-more", type=int, default=MAX_MORE)
    args = ap.parse_args()

    targets = [t for t in TARGETS if not args.only or args.only in t["name"]]
    print(f"补抓 {len(targets)} 个站点")

    for t in targets:
        rec = run(t, max_more=args.max_more)
        if rec:
            key = host_key(t["home"])
            dump_json(OUT / f"site_{key}.json", rec)
        jitter(PAGE_DELAY)

    print("\n完成。结果已写入 out/site_*.json")
    print("下一步：python scrape.py --aggregate-only   # 重新汇总进 notices.csv")


if __name__ == "__main__":
    main()
