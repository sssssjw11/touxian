# -*- coding: utf-8 -*-
"""附加工具：把可达性探测结果整理成便于阅读的清单（Markdown + CSV）。

输入 out/site_reach.json（由 probe_sites.py 产出）
输出 out/外网可访问站点清单.md、out/site_reach.csv

用法：
  python build_reach_list.py
  python build_reach_list.py -h  # 查看用法（任何前置检查之前生效）
"""
from __future__ import annotations

import argparse
import csv

from _common import OUT, SITES_JSON, load_json, load_sites, setup_console

CAT_ORDER = {"可进": 0, "需认证/被拦": 1, "404": 2, "连不上": 3}
GORDER = ["官网", "学院", "部门", "其他"]
GNAME = {"官网": "官网", "学院": "学院", "部门": "部门 / 机关", "其他": "其他单位"}


def build_parser():
    return argparse.ArgumentParser(
        description="把可达性探测结果（out/site_reach.json）整理成 Markdown + CSV 清单",
        epilog="前置：python probe_sites.py   # 先生成探测结果",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )


def main():
    setup_console()
    # 先解析参数：--help 必须在任何前置检查之前生效，否则新 clone 里看不到用法。
    build_parser().parse_args()

    reach = load_json(OUT / "site_reach.json")
    if not reach:
        print("缺少 out/site_reach.json，先跑 python probe_sites.py")
        return
    sites = load_sites() or {}

    group_of = {}
    for g in ("学院", "部门", "其他"):
        for x in sites.get(g, []):
            group_of[x["name"]] = g

    detail = reach["detail"]
    for d in detail:
        n = d["name"]
        if n.startswith("官网"):
            d["group"] = "官网"
        elif n.startswith("招生与就业处"):
            d["group"] = "部门"
        else:
            d["group"] = group_of.get(n, "其他")

    def cat_of(d):
        if d.get("err"):
            return "连不上"
        if d.get("status") == 200:
            return "可进"
        if d.get("status") in (401, 403):
            return "需认证/被拦"
        if d.get("status") == 404:
            return "404"
        return f"HTTP{d.get('status')}"

    for d in detail:
        d["cat"] = d.get("cat") or cat_of(d)

    ok = [d for d in detail if d["cat"] == "可进"]
    bad = [d for d in detail if d["cat"] != "可进"]

    L = ["# 温州大学站点 · 可达性清单", "",
         f"- 探测时间：{reach.get('tested_at', '—')}",
         "- 探测方式：串行 + 间隔限速 + 完整浏览器请求头，模拟真实用户",
         f"- **结果：可访问 {len(ok)} 个 / 共 {len(detail)} 个**", "",
         "> 说明：标记「不可访问」的站点并非抓取脚本的问题，而是当前网络环境",
         "> （校外／外网）到不了，通常需要连接校园网或学校 VPN 才能访问。", ""]

    L.append(f"## 一、可访问的站点（{len(ok)} 个）")
    L.append("")
    for g in GORDER:
        items = [d for d in ok if d["group"] == g]
        if not items:
            continue
        L += [f"### {GNAME[g]}（{len(items)} 个）", "",
              "| # | 单位 | 网址 | 响应 |", "|---|---|---|---|"]
        for i, d in enumerate(items, 1):
            el = f"{d['elapsed']:.2f}s" if d.get("elapsed") else ""
            L.append(f"| {i} | {d['name']} | {d['url']} | {d.get('status')} {el} |")
        L.append("")

    L += [f"## 二、不可访问的站点（{len(bad)} 个）", "",
          "| 单位 | 网址 | 情况 | 说明 |", "|---|---|---|---|"]
    for d in sorted(bad, key=lambda x: CAT_ORDER.get(x["cat"], 9)):
        if d["cat"] == "连不上":
            note = "连接被拒 / 解析失败，疑为仅限校内网络访问"
        elif d["cat"] == "需认证/被拦":
            note = "HTTP 403，需认证或被拦截"
        else:
            note = d["cat"]
        L.append(f"| {d['name']} | {d['url']} | {d['cat']} | {note} |")
    L.append("")

    p_md = OUT / "外网可访问站点清单.md"
    p_md.write_text("\n".join(L), encoding="utf-8")

    p_csv = OUT / "site_reach.csv"
    with open(p_csv, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["分组", "单位", "网址", "状态", "HTTP状态码", "耗时秒", "错误"])
        for d in detail:
            w.writerow([d["group"], d["name"], d["url"], d["cat"],
                        d.get("status", ""), f"{d.get('elapsed') or 0:.2f}", d.get("err") or ""])

    print(f"已生成：\n  {p_md}\n  {p_csv}\n")
    print("=" * 66)
    print(f"可访问 {len(ok)} / {len(detail)}")
    print("=" * 66)
    for g in GORDER:
        items = [d for d in ok if d["group"] == g]
        if items:
            print(f"\n【{GNAME[g]}】{len(items)} 个")
            print("  " + "、".join(d["name"] for d in items))
    print(f"\n【不可访问】{len(bad)} 个")
    for d in sorted(bad, key=lambda x: CAT_ORDER.get(x["cat"], 9)):
        print(f"  {d['cat']:8s} {d['name']}  {d['url']}")


if __name__ == "__main__":
    main()
