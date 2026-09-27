# -*- coding: utf-8 -*-
"""第 1 步：从温州大学官网页脚提取各二级单位的官网链接 -> sites.json

页脚的四个 ul 与四个 tab 一一对应（这个结构是该校模板固定的）：
  Foot-box1-content2 -> 学院
  Foot-box1-content3 -> 部门
  Foot-box1-content4 -> 其他
  Foot-box1-content5 -> 其他（与 content4 部分重复，去重时合并）

页脚里 href="#" 的单位表示官网暂未挂站，如实记录下来（url 置 null），
后续抓取时跳过，但报告里会单列一张表，便于日后复查。

用法：
  python extract_footer.py              # 联网抓官网首页再解析
  python extract_footer.py --offline    # 用本地 index.html（离线复现）
"""
from __future__ import annotations

import argparse
import re
import sys

from _common import HERE, HEADERS, fetch, new_session, setup_console

HOME_URL = "https://www.wzu.edu.cn/"
LOCAL_HTML = HERE / "index.html"   # 首次运行的首页留档，供 --offline 使用

GROUP_MAP = [
    ("学院", "Foot-box1-content2"),
    ("部门", "Foot-box1-content3"),
    ("其他", "Foot-box1-content4"),
    ("其他", "Foot-box1-content5"),
]

# href="#" 之外的占位写法
PLACEHOLDER = ("#", "", "javascript:;", "javascript:void(0);")


def get_home_html(offline: bool) -> str:
    if offline:
        if not LOCAL_HTML.is_file():
            sys.exit(f"[错误] 缺少离线首页 {LOCAL_HTML}，去掉 --offline 联网抓取。")
        print(f"读取本地首页：{LOCAL_HTML}")
        return LOCAL_HTML.read_text(encoding="utf-8", errors="ignore")

    print(f"抓取官网首页：{HOME_URL}")
    sess = new_session()
    st, final, text, err = fetch(sess, HOME_URL)
    if err or st != 200 or not text:
        sys.exit(f"[错误] 首页抓取失败：HTTP{st} {err}\n"
                 f"      可先手动保存首页为 {LOCAL_HTML.name}，再用 --offline 运行。")
    LOCAL_HTML.write_text(text, encoding="utf-8")
    print(f"  已留档 -> {LOCAL_HTML.name}（{len(text)} 字符）")
    return text


def parse_footer(html: str) -> dict:
    from bs4 import BeautifulSoup

    soup = BeautifulSoup(html, "lxml")
    raw: dict[str, list] = {}

    for group, ul_id in GROUP_MAP:
        ul = soup.find("ul", id=ul_id)
        if ul is None:
            print(f"[警告] 未找到 ul#{ul_id}，页脚结构可能已改版")
            continue
        items = []
        for a in ul.find_all("a"):
            href = (a.get("href") or "").strip()
            name = (a.get("title") or a.get_text(strip=True) or "").strip()
            if not name:
                continue
            if href in PLACEHOLDER or not re.match(r"^https?://", href):
                items.append({"name": name, "url": None, "note": "无独立站点"})
                continue
            items.append({"name": name, "url": href})
        raw.setdefault(group, []).extend(items)

    # 组内去重：有 url 的按 url 去重，无 url 的按 name 去重
    result = {}
    for group, items in raw.items():
        seen_url, seen_name, out = set(), set(), []
        for it in items:
            if it["url"]:
                key = it["url"].rstrip("/")
                if key in seen_url:
                    continue
                seen_url.add(key)
                out.append(it)
            else:
                if it["name"] in seen_name:
                    continue
                seen_name.add(it["name"])
                out.append(it)
        result[group] = out
    return result


def main():
    setup_console()
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="用本地 index.html 解析，不联网")
    args = ap.parse_args()

    result = parse_footer(get_home_html(args.offline))

    out = HERE / "sites.json"
    from _common import dump_json
    dump_json(out, result)

    print("\n== 统计 ==")
    total = 0
    for g, items in result.items():
        with_url = [x for x in items if x["url"]]
        no_url = [x for x in items if not x["url"]]
        total += len(with_url)
        print(f"  {g}: 共 {len(items)} 条，有站点 {len(with_url)}，无站点 {len(no_url)}")

    print(f"\n可抓取站点总数：{total}")
    no_site = [(g, x["name"]) for g, items in result.items() for x in items if not x["url"]]
    if no_site:
        print("\n== 页脚未提供独立站点的单位 ==")
        for g, name in no_site:
            print(f"  - [{g}] {name}")

    print(f"\n已写入 {out}")
    print("下一步：python scrape.py")


if __name__ == "__main__":
    main()
