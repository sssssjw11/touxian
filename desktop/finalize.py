# -*- coding: utf-8 -*-
"""第 6 步：收尾整理 —— manifest 去重 + 附件真伪甄别

做三件事：

1. **manifest 按 URL 去重**。原文件可能出现同一 URL 记两次（本项目实测 4 行重复，
   期刊社 2 个 URL 各记 2 次），原文件备份为 _manifest.raw.jsonl。
2. **甄别「附件」里其实是中间页的文件**，这是博达系附件下载的两道坎中的第二道：
     · CAS 统一身份认证 —— 整页跳转，download.py 已处理（标 need_captcha 之外）
     · **图形验证码** —— 只在单文件下载时才弹，返回「请输入验证码下载附件」HTML
   最可靠的预警信号：**落盘文件名是 download__<hash>.jsp**。
   扩展名取不到 = 响应头 Content-Disposition 缺失 = 拿到的必然是中间页而非真文件。
   以后凡见 download__*.jsp，直接判定为未取得真附件。
3. 把甄别结果写回每篇的 meta.json 与 _manifest.jsonl，产出明细 out/captcha_attach.json。

用法：
  python finalize.py
  python finalize.py -h          # 查看用法（任何前置检查之前生效）
"""
from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter, OrderedDict

from _common import DOWNLOAD, OUT, dump_json, load_json, setup_console, write_jsonl

MANIFEST = DOWNLOAD / "_manifest.jsonl"
RAW = DOWNLOAD / "_manifest.raw.jsonl"

CAPTCHA_MARK = "请输入验证码"
CAPTCHA_NOTE = "原站要求输入图形验证码，未能下载真文件"
DEAD_NOTE = "原站返回空内容，该附件在服务器上已不存在"


def classify(p):
    """返回 None / 'captcha' / 'dead' / 'missing'。

    captcha : download.jsp 返回的「请输入验证码下载附件」中间页
    dead    : 服务端返回空壳（如 <div>null</div>），原站文件已失效
    missing : 文件根本没落地
    """
    if not p.is_file():
        return "missing"
    try:
        head = p.read_bytes()[:2000]
    except OSError:
        return None
    low = head.lower()
    if b"<html" in low or b"<!doctype" in low:
        txt = head.decode("utf-8", "replace")
        if CAPTCHA_MARK in txt or "附件下载" in txt:
            return "captcha"
    if p.stat().st_size < 200 and b"null" in low:
        return "dead"
    return None


def backfill_counts(r, d):
    """补齐资源计数（images_ref / images_failed / attach_ref）。

    早期版本只记了「成功下载数」，导致核对时报不出「有几张图是原站本来就 404」，
    于是把已知失效的引用误判成「本地缺失」。这里从 meta.json 的图片清单反推：
    images_ref = 引用总数；落地的去掉「本地路径填的就是原始 URL」的那些。
    """
    mj = load_json(d / "meta.json") or {}
    img_map = mj.get("图片清单") or {}
    if img_map:
        r["images_ref"] = len(img_map)
        # 值为 http(s) 开头 = 当时没下下来，保留了原始外链
        r["images_failed"] = sum(1 for v in img_map.values() if str(v).startswith("http"))
    else:
        r.setdefault("images_ref", len(r.get("assets", {}).get("images", [])))
        r.setdefault("images_failed", 0)

    atts = r.get("assets", {}).get("attachments", [])
    r["attach_ref"] = len(atts)
    r["attach_failed"] = sum(1 for a in atts if not a.get("file"))


def build_parser():
    return argparse.ArgumentParser(
        description="第 6 步：收尾整理 —— manifest 按 URL 去重 + 附件真伪甄别"
                    "（可反复执行，标记不会叠加）",
        epilog="下一步：python postprocess.py   # 本地化图片 + 生成离线索引页",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )


def main():
    setup_console()
    # 先解析参数：--help 必须在任何前置检查之前生效，否则新 clone 里看不到用法。
    build_parser().parse_args()

    if not MANIFEST.is_file():
        print("download/_manifest.jsonl 不存在，先跑 python download.py")
        return

    # 首次运行时把原始 manifest 另存一份，之后每次都基于原始文件重算，
    # 这样 finalize 可以反复执行而不会叠加标记。
    if not RAW.is_file():
        shutil.copyfile(MANIFEST, RAW)

    raw_lines = [json.loads(l) for l in RAW.read_text(encoding="utf-8").splitlines() if l.strip()]
    print(f"原始记录        : {len(raw_lines)}")

    # 1) 按 URL 去重，保留最后一条
    by = OrderedDict()
    for r in raw_lines:
        by[r["url"]] = r
    dedup = list(by.values())
    print(f"去重后          : {len(dedup)}  (原始 {len(raw_lines)} - 重复 {len(raw_lines) - len(dedup)})")
    for u, c in Counter(r["url"] for r in raw_lines).items():
        if c > 1:
            print(f"    重复 URL ×{c}: {u}")

    # 2) 逐附件甄别
    cap_items, dead_items = [], []
    n_valid = n_cap = n_dead = 0
    for r in dedup:
        if r.get("status") != "OK":
            continue
        d = DOWNLOAD / r["dir"]
        for a in r.get("assets", {}).get("attachments", []):
            cls = classify(d / a["file"])
            if cls == "captcha":
                a["need_captcha"] = True
                a["note"] = CAPTCHA_NOTE
                n_cap += 1
                cap_items.append((r["unit"], r["title"], r["url"], a["url"]))
            elif cls in ("dead", "missing"):
                a["dead"] = True
                a["note"] = DEAD_NOTE if cls == "dead" else "文件未落地"
                n_dead += 1
                dead_items.append((r["unit"], r["title"], r["url"], a["url"]))
            else:
                n_valid += 1
        r["attach"] = len(r.get("assets", {}).get("attachments", []))
        backfill_counts(r, d)

    print(f"有效附件        : {n_valid}")
    print(f"需验证码附件    : {n_cap}")
    print(f"已失效附件      : {n_dead}")

    # 3) 写回每篇的 meta.json
    for r in dedup:
        mp = DOWNLOAD / r["dir"] / "meta.json"
        if not mp.is_file():
            continue
        mj = load_json(mp)
        if not mj:
            continue
        byfile = {a["file"]: a for a in r.get("assets", {}).get("attachments", [])}
        for a in mj.get("附件清单", []):
            src = byfile.get(a.get("file"))
            if not src:
                continue
            if src.get("need_captcha"):
                a["need_captcha"] = True
                a["note"] = src["note"]
            elif src.get("dead"):
                a["dead"] = True
                a["note"] = src["note"]
        mj["attach"] = len(mj.get("附件清单", []))
        dump_json(mp, mj)

    # 4) 写回 manifest
    write_jsonl(MANIFEST, dedup)
    print(f"已重写          : {MANIFEST}")

    # 5) 汇总
    st = Counter(r.get("status") for r in dedup)
    units = sorted({r["unit"] for r in dedup if r.get("status") == "OK"})
    print()
    print("=" * 60)
    print(f"最终记录        : {len(dedup)}  {dict(st)}")
    print(f"覆盖单位        : {len(units)}")
    print(f"通知篇数(OK)    : {st.get('OK', 0)}")
    print(f"有效附件        : {n_valid}")
    print(f"需验证码附件    : {n_cap}")
    print(f"已失效附件      : {n_dead}")
    print("=" * 60)

    if cap_items:
        print("\n需验证码附件明细（前 20）：")
        for u, t, _, au in cap_items[:20]:
            print(f"  [{u}] {t[:34]}")
            print(f"      {au[:120]}")
    if dead_items:
        print("\n已失效附件明细：")
        for u, t, _, au in dead_items:
            print(f"  [{u}] {t[:34]}")
            print(f"      {au[:120]}")

    dump_json(OUT / "captcha_attach.json", {
        "需验证码": [{"unit": u, "title": t, "page": pu, "attach": au} for u, t, pu, au in cap_items],
        "已失效": [{"unit": u, "title": t, "page": pu, "attach": au} for u, t, pu, au in dead_items],
    })
    print(f"\n[明细已写入] {OUT / 'captcha_attach.json'}")
    print("\n下一步：python verify.py   # 完整性核对\n"
          "        python pack.py     # 打包交付")


if __name__ == "__main__":
    main()
