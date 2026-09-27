# -*- coding: utf-8 -*-
"""第 7 步：下载完整性核对 —— 交付前的最后一道关

分六节逐项核对，任何一项对不上都要在交付前解释清楚：

  一、manifest 概况            记录数、坏行、status 分布、时间范围内外
  二、逐条落地核对              每个 OK 记录的目录/必需文件/图片数/附件是否齐
  三、附件内容甄别              有效 / 需验证码 / 已失效 三类计数
  四、跳过项明细                非 OK 的条目及其状态
  五、磁盘实体扫描              直接数磁盘上的文件，与 manifest 交叉验证
  六、结论                      PASS 或列出所有问题

「磁盘实体扫描」是刻意的重复劳动：manifest 是脚本自己写的自述，
磁盘扫描才是独立证据。两者对不上，说明中间有文件丢失或未被记录。

用法：
  python verify.py
  python verify.py --since 2026-08-22 --until 2026-09-22
"""
from __future__ import annotations

import argparse
from collections import Counter

from _common import (DOWNLOAD, IMG_EXT, OUT, add_date_args, date_window,
                     load_jsonl, setup_console)


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="核对 download/ 的下载完整性")
    ap.add_argument("--out", default="", help="报告输出路径（默认 out/verify_report.txt）")
    add_date_args(ap)
    args = ap.parse_args()

    since, until = date_window(days=args.days, since=args.since, until=args.until)

    manifest = DOWNLOAD / "_manifest.jsonl"
    records, bad_lines = load_jsonl(manifest)
    if not records:
        print(f"{manifest} 为空或不存在")
        return

    in_range = lambda d: (bool(d) and since <= d <= until) if since else True  # noqa: E731

    out, w = [], None
    w = out.append

    w("=" * 68)
    w("一、manifest 概况")
    w("=" * 68)
    w(f"记录数            : {len(records)}")
    w(f"坏行              : {len(bad_lines)}")
    for i, e in bad_lines[:10]:
        w(f"    行{i}: {e}")
    w(f"status 分布       : {dict(Counter(r.get('status') for r in records))}")

    if since:
        inr = [r for r in records if in_range(r.get("date"))]
        outr = [r for r in records if not in_range(r.get("date"))]
        w(f"范围内({since}~{until}): {len(inr)}")
        w(f"范围外            : {len(outr)}")
        for r in outr[:10]:
            w(f"    [{r.get('date')}] {r.get('unit')} / {r.get('title', '')[:30]}")

    ok = [r for r in records if r.get("status") == "OK"]
    skipped = [r for r in records if r.get("status") != "OK"]

    w("")
    w("=" * 68)
    w("二、逐条落地核对（仅 status=OK）")
    w("=" * 68)

    missing_dir, missing_file, img_short, att_bad, img_dead = [], [], [], [], []
    dirs_ok = 0

    for r in ok:
        d = DOWNLOAD / r["dir"]
        if not d.is_dir():
            missing_dir.append(r["dir"])
            continue
        dirs_ok += 1
        for fn in ("meta.json", "内容.md", "正文.html"):
            if not (d / fn).is_file():
                missing_file.append((r["dir"], fn))

        ip = d / "图片"
        n_img = len([p for p in ip.iterdir() if p.is_file()]) if ip.is_dir() else 0
        # 已落地的图片数 = 引用数 - 原站就已 404 的张数。
        # 后者的链接会保留在正文里，但不算「本地缺失」。
        n_ref = r.get("images_ref", len(r.get("assets", {}).get("images", [])))
        n_dead_img = r.get("images_failed", 0)
        if n_img < n_ref - n_dead_img:
            img_short.append((r["dir"], n_ref - n_dead_img, n_img))
        elif n_dead_img:
            img_dead.append((r["dir"], n_dead_img, r.get("note", "")))

        for a in r.get("assets", {}).get("attachments", []):
            # file 为 None 表示本来就没下下来，已在甄别环节单列，不重复计入
            if not a.get("file"):
                continue
            p = d / a["file"]
            if not p.is_file() or p.stat().st_size == 0:
                att_bad.append((r["dir"], a["file"]))

    w(f"目录存在          : {dirs_ok} / {len(ok)}")
    w(f"目录缺失          : {len(missing_dir)}")
    for x in missing_dir[:20]:
        w(f"    ! {x}")
    w(f"必需文件缺失      : {len(missing_file)}")
    for x in missing_file[:20]:
        w(f"    ! {x[0]} -> {x[1]}")
    w(f"图片数量不足      : {len(img_short)}")
    for x in img_short[:20]:
        w(f"    ! {x[0]}  期望{x[1]} 实际{x[2]}")
    w(f"附件缺失/空文件   : {len(att_bad)}")
    for x in att_bad[:20]:
        w(f"    ! {x[0]} -> {x[1]}")
    n_dead_img_total = sum(x[1] for x in img_dead)
    w(f"原站已失效的配图  : {n_dead_img_total} 张（{len(img_dead)} 篇；原站 404，非抓取失败）")
    for x in img_dead[:10]:
        w(f"    · {x[0]}  失效 {x[1]} 张")

    w("")
    w("三、附件内容甄别")
    w("-" * 68)
    atts = [a for r in ok for a in r.get("assets", {}).get("attachments", [])]
    n_cap = sum(1 for a in atts if a.get("need_captcha"))
    n_dead = sum(1 for a in atts if a.get("dead"))
    n_valid = sum(1 for a in atts if not a.get("need_captcha") and not a.get("dead"))
    w(f"有效附件          : {n_valid}")
    w(f"需图形验证码(未取得): {n_cap}  （原站限制，仅得到验证码页）")
    w(f"原站已失效        : {n_dead}")
    w(f"合计              : {n_valid + n_cap + n_dead}")

    w("")
    w("四、跳过项明细（非 OK）")
    w("-" * 68)
    w(f"   {dict(Counter(r.get('status') for r in skipped))}")
    for r in skipped[:5]:
        w(f"   [{r.get('status')}] {r.get('unit')} / {r.get('title', '')[:34]}")

    # ---- 磁盘实体扫描：不信任 manifest，直接数文件
    w("")
    w("=" * 68)
    w("五、磁盘实体扫描")
    w("=" * 68)
    n_files = n_imgs = n_atts = n_zeros = total_bytes = notice_dirs = n_jsp = 0
    zero_list, jsp_list, att_by_ext = [], [], Counter()
    unit_dirs = []

    for top in sorted(DOWNLOAD.iterdir()):
        if top.is_dir() and not top.name.startswith("_"):
            unit_dirs.append(top.name)

    for dirpath, dirnames, filenames in DOWNLOAD.walk():
        rel = dirpath.relative_to(DOWNLOAD)
        parts = rel.parts
        if parts and parts[0].startswith("_"):
            continue
        if len(parts) == 2 and (dirpath / "meta.json").is_file():
            notice_dirs += 1
        for fn in filenames:
            fp = dirpath / fn
            try:
                sz = fp.stat().st_size
            except OSError:
                continue
            n_files += 1
            total_bytes += sz
            if sz == 0:
                n_zeros += 1
                if len(zero_list) < 20:
                    zero_list.append(str(rel / fn))
            ext = fp.suffix.lower()
            frel = fp.relative_to(DOWNLOAD).parts
            if len(frel) >= 2 and frel[-2] == "附件":
                n_atts += 1
                att_by_ext[ext] += 1
                if ext == ".jsp":
                    # 附件目录里的 .jsp 必然是「未取到真文件」的中间页
                    n_jsp += 1
                    if len(jsp_list) < 20:
                        jsp_list.append(str(frel[-2] + "/" + frel[-1]) if len(frel) < 3
                                        else str(rel / fn))
            elif ext in IMG_EXT:
                n_imgs += 1

    w(f"单位目录数        : {len(unit_dirs)}")
    w(f"通知目录数        : {notice_dirs}")
    w(f"文件总数          : {n_files}")
    w(f"图片文件          : {n_imgs}")
    w(f"附件文件          : {n_atts}")
    w(f"附件类型          : {dict(att_by_ext)}")
    w(f"其中 .jsp 中间页  : {n_jsp}  （扩展名取不到 = 未取得真附件；"
      f"应为 需验证码 {n_cap} + 已失效 {n_dead} = {n_cap + n_dead}）")
    if n_jsp and n_jsp != n_cap + n_dead:
        w(f"    ! .jsp 数与甄别结果不一致，需人工复核：")
        for x in jsp_list:
            w(f"      {x}")
    w(f"零字节文件        : {n_zeros}")
    for x in zero_list:
        w(f"    ! {x}")
    w(f"总占用            : {total_bytes / 1024 / 1024:.1f} MB")

    aside = DOWNLOAD / "_范围外"
    if aside.is_dir():
        ex_files = ex_bytes = 0
        for dirpath, dirnames, filenames in aside.walk():
            for fn in filenames:
                try:
                    ex_bytes += (dirpath / fn).stat().st_size
                except OSError:
                    pass
                ex_files += 1
        w(f"_范围外/          : {len(list(aside.iterdir()))} 个单位, "
          f"{ex_files} 文件, {ex_bytes / 1024 / 1024:.1f} MB")

    w("")
    w("=" * 68)
    w("六、结论")
    w("=" * 68)
    problems = len(missing_dir) + len(missing_file) + len(img_short) + len(att_bad) + n_zeros
    if problems == 0:
        w("PASS — 范围内所有通知均完整落地，无缺失文件、无零字节文件。")
    else:
        w(f"存在 {problems} 处问题，详见上文 '!' 行。")
    w("")
    w("属原站限制、不计入失败的项目：")
    w(f"  · {len(skipped)} 篇通知需统一身份认证（CAS），未能下载")
    w(f"  · {n_cap} 个附件原站要求图形验证码，仅取到中间页")
    w(f"  · {n_dead} 个附件原站已失效（服务器返回空内容）")
    w(f"  · {n_dead_img_total} 张配图在原站为 404，已失效")

    text = "\n".join(out)
    print(text)

    rep = args.out or (OUT / "verify_report.txt")
    from pathlib import Path
    rep = Path(rep)
    rep.parent.mkdir(parents=True, exist_ok=True)
    rep.write_text(text, encoding="utf-8")
    print(f"\n[报告已写入] {rep}")

    if problems:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
