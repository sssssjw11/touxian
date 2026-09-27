# -*- coding: utf-8 -*-
"""附加工具：探测各子站在「当前网络环境」（如校外／外网）下的可达性。

用途：判断从当前网络能否直接抓取／轮询各站点。校园网内通常全部可达，
外网访问则会有若干站点因仅限校内而连不上或被拦 —— 这些站点在校外需要
走学校 VPN 才能访问，抓取脚本会如实记录为「访问失败」。

探测方式与主抓取器一致：串行 + 间隔限速 + 真实浏览器请求头，模拟真实用户。
不判定「需认证」为由 —— 403 可能是 WAF 拦截，也可能是真需要认证，
统一归入「需认证/被拦」，具体原因留给人看。

产出 out/site_reach.json，再由 build_reach_list.py 整理成清单。

用法：
  python probe_sites.py
  python probe_sites.py --timeout 15
"""
from __future__ import annotations

import argparse
import json
import time

from _common import DOWNLOAD, OUT, SITES_JSON, load_json, load_sites, setup_console


def probe(url, timeout=12):
    """返回 (status, elapsed, final_url, nbytes, err)。"""
    import requests
    try:
        r = requests.get(
            url,
            headers={"User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    "Chrome/128.0.0.0 Safari/537.36"),
                     "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                     "Accept-Language": "zh-CN,zh;q=0.9"},
            timeout=timeout, allow_redirects=True)
        return r.status_code, r.elapsed.total_seconds(), r.url, len(r.content), None
    except requests.exceptions.SSLError as e:
        return None, None, url, 0, f"SSL: {str(e)[:60]}"
    except requests.exceptions.ConnectTimeout:
        return None, None, url, 0, "连接超时"
    except requests.exceptions.ReadTimeout:
        return None, None, url, 0, "读取超时"
    except requests.exceptions.ConnectionError as e:
        msg = str(e)
        if any(k in msg for k in ("Failed to resolve", "Name or service not known", "getaddrinfo")):
            return None, None, url, 0, "DNS 解析失败"
        if "refused" in msg:
            return None, None, url, 0, "连接被拒绝"
        return None, None, url, 0, f"连接错误: {msg[:60]}"
    except Exception as e:
        return None, None, url, 0, f"异常: {str(e)[:60]}"


def classify(status, err):
    if err:
        return "连不上"
    if status == 200:
        return "可进"
    if status in (401, 403):
        return "需认证/被拦"
    if status == 404:
        return "404"
    return f"HTTP{status}"


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="探测各子站的可达性")
    ap.add_argument("--timeout", type=int, default=12)
    ap.add_argument("--interval", type=float, default=0.3, help="站点之间的间隔秒数")
    args = ap.parse_args()

    sites = load_sites()
    if not sites:
        print("缺少站点清单，先跑 python extract_footer.py")
        return

    targets = [("官网", "https://www.wzu.edu.cn/")]
    for group in ("学院", "部门", "其他"):
        for x in sites.get(group, []):
            if x.get("url"):
                targets.append((x["name"], x["url"]))
    # 招生与就业处页脚给的是「进入页」，真实首页是根域
    targets.append(("招生与就业处(真实首页)", "https://zs.wzu.edu.cn/"))

    print(f"待测 {len(targets)} 个站点（串行 + 间隔，模拟真实用户）\n")
    results, rows = {}, []
    for i, (name, url) in enumerate(targets, 1):
        st, el, final, nbytes, err = probe(url, timeout=args.timeout)
        cat = classify(st, err)
        results.setdefault(cat, []).append((name, url))
        rows.append((cat, name, url, st, el, err, final, nbytes))
        mark = {"可进": "✓", "需认证/被拦": "✗", "连不上": "·"}.get(cat, "?")
        extra = f"{st} {el:.1f}s" if st else (err or "")
        print(f"[{i:2d}/{len(targets)}] {mark} {cat:10s} {name[:18]:18s} {extra}  {url}")
        time.sleep(args.interval)

    print("\n" + "=" * 70)
    print("分类统计")
    print("=" * 70)
    for cat in ("可进", "需认证/被拦", "404", "连不上"):
        lst = results.get(cat, [])
        if not lst:
            continue
        print(f"\n■ {cat}（{len(lst)} 个）")
        for name, url in lst:
            print(f"    {name[:24]:26s} {url}")

    out = OUT / "site_reach.json"
    out.write_text(json.dumps({
        "tested_at": time.strftime("%Y-%m-%dT%H:%M"),
        "summary": {k: len(v) for k, v in results.items()},
        "detail": [{"cat": c, "name": n, "url": u, "status": s,
                    "elapsed": e, "err": er} for c, n, u, s, e, er, f2, nb in rows],
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out}")

    print("\n" + "=" * 70)
    print(f"结论：可进 {len(results.get('可进', []))} / {len(targets)} 个站点")
    print("=" * 70)
    print("\n下一步：python build_reach_list.py   # 整理成 Markdown + CSV 清单")


if __name__ == "__main__":
    main()
