# -*- coding: utf-8 -*-
"""第 8 步：打包成 zip 交付

排除项
  _范围外/                时间范围外的历史数据（只移不删，留在原地可回滚）
  _stdout.log / _download.log   抓取日志（体积大且无交付价值）

附带 README.txt（UTF-8 BOM，Windows 记事本双击能正常显示中文），
内容从实际 manifest 统计生成 —— 数字不会与包内数据对不上。

用法：
  python pack.py
  python pack.py --name 温州大学通知库_2026秋
  python pack.py --no-verify      # 跳过打包后的完整性校验（不推荐）
"""
from __future__ import annotations

import argparse
import time
import zipfile
from collections import Counter

from _common import DOWNLOAD, HERE, human_size, load_jsonl, setup_console

EXCLUDE_TOP = {"_范围外"}
EXCLUDE_FILES = {"_stdout.log", "_download.log"}

README_TMPL = """温州大学 · 各学院/部门通知公告 图文离线库
================================================================

时间范围   {since} ~ {until}{span_note}
覆盖单位   {n_units} 个
通知篇数   {n_ok} 篇
正文图片   {n_imgs} 张
有效附件   {n_valid} 个

----------------------------------------------------------------
【目录结构】

  索引.html / index.html      图文离线浏览页（可搜索、按单位/日期筛选、附件直达）
  单位名/日期__编号__标题/     每篇通知一个目录
      |- 内容.md      正文（Markdown，图片指向本地文件）
      |- 正文.html    正文网页快照（图片已本地化，可直接双击用浏览器打开）
      |- meta.json    元数据（原站 URL、日期、栏目、图片与附件清单）
      |- 图片/
      |- 附件/
  _manifest.jsonl             全部下载记录（断点续跑依据）

----------------------------------------------------------------
【使用方法】

  解压后直接双击 索引.html 用浏览器打开即可浏览。
  点标题看离线正文；「附件 ⤓」打开本地附件；「原站 ↗」跳转原始网页。
  请勿拆分或移动各单位目录，索引页里的链接依赖当前相对层级。

----------------------------------------------------------------
【未收录与异常说明】（均属原站限制，非抓取失败）

  1) {n_skip} 篇通知需「温州大学统一身份认证」（CAS 登录）才能访问，未能下载。
     明细见索引页底部「未下载的条目」表。
  2) {n_cap} 个附件原站要求输入图形验证码，机器无法自动通过，
     仅取到「请输入验证码下载附件」中间页。
     明细见索引页底部「附件未能下载的条目」表。
  3) {n_dead} 个附件原站已失效，服务器返回空内容。
  4) 个别配图在原站为 404（已失效），其余图片均完整。

----------------------------------------------------------------
【抓取方式】

  单线程串行、随机限速、完整浏览器请求头与 Cookie 会话，
  模拟真实用户访问，全程未使用并发。需认证的页面一律跳过，不做任何绕过。

----------------------------------------------------------------
【版权】

  所有内容版权归温州大学各二级单位所有，本库仅作离线归档与检索之用。

生成时间：{today}
"""


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="打包图文离线库")
    ap.add_argument("--name", default="", help="压缩包与根目录名（默认自动按日期区间命名）")
    ap.add_argument("--out", default="", help="输出 zip 路径")
    ap.add_argument("--no-verify", action="store_true", help="跳过压缩包完整性校验")
    args = ap.parse_args()

    records, _ = load_jsonl(DOWNLOAD / "_manifest.jsonl")
    if not records:
        print("download/_manifest.jsonl 为空，先跑 python download.py")
        return

    ok = [r for r in records if r.get("status") == "OK"]
    skipped = [r for r in records if r.get("status") != "OK"]
    dates = sorted(r["date"] for r in ok if r.get("date"))
    since, until = (dates[0], dates[-1]) if dates else ("????-??-??", "????-??-??")

    atts = [a for r in ok for a in r.get("assets", {}).get("attachments", [])]
    n_valid = sum(1 for a in atts if not a.get("need_captcha") and not a.get("dead"))
    n_cap = sum(1 for a in atts if a.get("need_captcha"))
    n_dead = sum(1 for a in atts if a.get("dead"))

    rootname = args.name or f"温州大学通知图文库_{since.replace('-', '')}-{until.replace('-', '')}"
    out = args.out or str(HERE / f"{rootname}.zip")

    readme = README_TMPL.format(
        since=since, until=until, span_note="",
        n_units=len({r["unit"] for r in ok}), n_ok=len(ok),
        n_imgs=sum(r.get("images", 0) for r in ok), n_valid=n_valid,
        n_skip=len(skipped), n_cap=n_cap, n_dead=n_dead,
        today=time.strftime("%Y-%m-%d"),
    )
    (DOWNLOAD / "README.txt").write_text(readme, encoding="utf-8-sig")
    print(f"已写入说明文件: {DOWNLOAD / 'README.txt'}")

    t0 = time.time()
    n = skipped_files = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(DOWNLOAD.rglob("*")):
            rel = path.relative_to(DOWNLOAD)
            parts = rel.parts
            if parts and parts[0] in EXCLUDE_TOP:
                skipped_files += 1
                continue
            if len(parts) == 1 and path.name in EXCLUDE_FILES:
                skipped_files += 1
                continue
            if path.is_dir():
                continue
            z.write(path, f"{rootname}/{rel.as_posix()}")
            n += 1
            if n % 400 == 0:
                print(f"  ... 已写入 {n} 个文件")

    size = __import__("pathlib").Path(out).stat().st_size
    print(f"\n文件数        : {n}")
    print(f"压缩包        : {out}")
    print(f"压缩后大小    : {human_size(size)}")

    if not args.no_verify:
        print("\n校验压缩包完整性 ...")
        with zipfile.ZipFile(out) as z:
            bad = z.testzip()
            cnt = len(z.namelist())
        print(f"  {'! 损坏项: ' + str(bad) if bad else f'OK — {cnt} 个条目全部可读'}")

    print(f"已排除（不打包）: {skipped_files} 个条目（_范围外/ 与抓取日志）")
    print(f"耗时          : {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
