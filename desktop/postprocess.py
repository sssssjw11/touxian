# -*- coding: utf-8 -*-
"""第 5 步：下载后的后处理

1. 修正「正文.html」里的图片 src —— 把原页面的相对/绝对外链改成指向本地
   「图片/」目录，让离线 HTML 双击就能看到图。
2. 生成「索引.html」—— 浅色主题的本地浏览页：按单位分组、支持关键词/单位/日期
   筛选、标题直达离线正文、附件直达本地文件，文末附两张异常清单。

附件标签按甄别结果分三种渲染（甄别工作由 finalize.py 完成，本脚本在它之后运行）：
   有效        可点「附件 ⤓」
   需图形验证码 不可点「附件 · 需验证码」（琥珀色）
   原站已失效   不可点「附件 · 已失效」（红色）

用法：
  python postprocess.py
  python postprocess.py -h       # 查看用法（任何前置检查之前生效）
"""
from __future__ import annotations

import argparse
import html
import re
from collections import defaultdict
from urllib.parse import urljoin

from _common import DOWNLOAD, OUT, dump_json, human_size, load_json, load_jsonl, setup_console

MANIFEST = DOWNLOAD / "_manifest.jsonl"


def fix_body_html(rec):
    """把 正文.html 里的外链图片替换成本地路径，返回替换处数。"""
    folder = DOWNLOAD / rec["dir"]
    hp, mp = folder / "正文.html", folder / "meta.json"
    if not (hp.is_file() and mp.is_file()):
        return 0
    meta = load_json(mp) or {}
    img_map = meta.get("图片清单", {})
    if not img_map:
        return 0

    page_url = rec["url"]
    txt = hp.read_text(encoding="utf-8")
    n = 0

    def repl(m):
        nonlocal n
        quote, src = m.group(1), m.group(2)
        if src.startswith("data:"):
            return m.group(0)
        local = img_map.get(urljoin(page_url, src))
        if local:
            n += 1
            return f"src={quote}{local}{quote}"
        return m.group(0)

    new = re.sub(r'src=(["\'])([^"\']+)\1', repl, txt)
    if n:
        new = new.replace(
            "<body>",
            '<body style="max-width:900px;margin:0 auto;padding:24px;'
            "font:16px/1.8 'Microsoft YaHei',sans-serif\">", 1)
        hp.write_text(new, encoding="utf-8")
    return n


def build_index(recs):
    ok = [r for r in recs if r.get("status") == "OK"]
    skipped = [r for r in recs if r.get("status") != "OK"]

    total_bytes = sum(r.get("bytes", 0) for r in ok)
    total_imgs = sum(r.get("images", 0) for r in ok)

    # 附件按甄别状态分三类统计
    n_valid = n_cap = n_dead = 0
    cap_rows = []
    for r in ok:
        for a in r.get("assets", {}).get("attachments", []):
            if a.get("need_captcha"):
                n_cap += 1
                cap_rows.append((r["unit"], r["title"], r["url"], "需图形验证码"))
            elif a.get("dead"):
                n_dead += 1
                cap_rows.append((r["unit"], r["title"], r["url"], "原站已失效"))
            else:
                n_valid += 1

    units = defaultdict(list)
    for r in ok:
        units[r["unit"]].append(r)
    for k in units:
        units[k].sort(key=lambda x: (x.get("date") or "", x.get("title") or ""), reverse=True)

    esc = lambda s: html.escape(s or "")   # noqa: E731

    cards = []
    for unit in sorted(units, key=lambda u: -len(units[u])):
        items = units[unit]
        li = []
        for r in items:
            folder = r["dir"]
            extra = ""
            if r.get("images"):
                extra += f'<span class="tg">图 {r["images"]}</span>'
            for a in r.get("assets", {}).get("attachments", []):
                if not a.get("file"):
                    continue
                if a.get("need_captcha"):
                    extra += ('<span class="tg cap" title="原站要求输入图形验证码，'
                              '机器无法下载；请点「原站 ↗」到原页面手动获取">附件 · 需验证码</span>')
                elif a.get("dead"):
                    extra += ('<span class="tg dead" title="原站该附件已失效'
                              '（服务器返回空内容）">附件 · 已失效</span>')
                else:
                    extra += (f'<a class="tg att" href="{esc(folder)}/{esc(a["file"])}" '
                              f'target="_blank" title="{esc(a["file"].split("/")[-1])}">附件 ⤓</a>')
            li.append(
                f'<li class="nt" data-t="{esc(r["title"]).lower()}" data-u="{esc(unit)}">'
                f'<span class="d">{esc(r.get("date") or "—")}</span>'
                f'<a class="t" href="{esc(folder)}/正文.html" target="_blank">{esc(r["title"])}</a>'
                f'{extra}'
                f'<a class="src" href="{esc(r["url"])}" target="_blank" title="原站链接">原站 ↗</a>'
                f'<a class="src" href="{esc(folder)}/内容.md" target="_blank" title="Markdown 正文">md</a>'
                f'</li>')
        cards.append(f'''<section class="unit" data-unit="{esc(unit)}">
  <header class="uh"><h3>{esc(unit)}</h3><span class="cnt">{len(items)} 篇</span></header>
  <ul class="nl">{"".join(li)}</ul>
</section>''')

    opt = "".join(f'<option value="{esc(u)}">{esc(u)}（{len(units[u])}）</option>'
                  for u in sorted(units, key=lambda u: -len(units[u])))

    dates = [r.get("date") for r in recs if r.get("date")]
    maxdate = max(dates) if dates else ""

    skip_rows = "".join(
        f'<tr><td>{esc(r.get("unit"))}</td><td>{esc(r.get("title"))}</td>'
        f'<td><a href="{esc(r.get("url"))}" target="_blank">{esc(r.get("url"))}</a></td>'
        f'<td>{esc(r.get("status"))}</td></tr>' for r in skipped) \
        or '<tr><td colspan="4">无</td></tr>'

    cap_table = "".join(
        f'<tr><td>{esc(u)}</td><td>{esc(t)}</td>'
        f'<td><a href="{esc(p)}" target="_blank">原页面 ↗</a></td>'
        f'<td>{esc(st)}</td></tr>' for u, t, p, st in cap_rows) \
        or '<tr><td colspan="4">无</td></tr>'

    doc = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>温州大学通知公告 · 图文离线库</title>
<style>
 :root{{--bg:#f5f7fa;--card:#fff;--line:#e4e9f0;--ink:#1f2937;--ink2:#5b6b82;--ink3:#8b99ab;--blue:#1e5eb8;--blue-l:#eaf1fb;}}
 *{{box-sizing:border-box}}
 body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.65 "Microsoft YaHei","PingFang SC",system-ui,sans-serif}}
 .wrap{{max-width:1180px;margin:0 auto;padding:26px 20px 60px}}
 .hero{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:24px 26px;box-shadow:0 1px 3px rgba(16,40,80,.05)}}
 .hero h1{{margin:0 0 6px;font-size:24px}}
 .hero .sub{{color:var(--ink2);font-size:13.5px}}
 .kpis{{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-top:18px}}
 .kpi{{background:var(--blue-l);border:1px solid #d7e4f7;border-radius:10px;padding:12px 14px}}
 .kpi b{{display:block;font-size:22px;color:var(--blue);line-height:1.3}}
 .kpi span{{font-size:12.5px;color:var(--ink2)}}
 .toolbar{{position:sticky;top:0;z-index:9;margin:20px 0 14px;padding:12px 14px;background:rgba(245,247,250,.94);backdrop-filter:blur(6px);border:1px solid var(--line);border-radius:12px;display:flex;gap:10px;flex-wrap:wrap;align-items:center}}
 input,select{{font:inherit;font-size:14px;padding:8px 11px;border:1px solid #cfd9e6;border-radius:8px;background:#fff;color:var(--ink);outline:none}}
 input:focus,select:focus{{border-color:var(--blue);box-shadow:0 0 0 3px rgba(30,94,184,.12)}}
 #q{{flex:1;min-width:220px}}
 .hint{{font-size:12.5px;color:var(--ink3)}}
 .unit{{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-bottom:12px;overflow:hidden}}
 .uh{{display:flex;gap:10px;align-items:center;padding:12px 18px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#fbfcfe,#f7f9fc)}}
 .uh h3{{margin:0;font-size:16px}}
 .cnt{{font-size:12.5px;color:var(--ink2)}}
 ul.nl{{list-style:none;margin:0;padding:6px 0}}
 li.nt{{display:flex;gap:10px;align-items:baseline;padding:7px 18px;border-bottom:1px dashed #eef2f7;flex-wrap:wrap}}
 li.nt:last-child{{border-bottom:0}}
 li.nt:hover{{background:#f8fafd}}
 li.nt .d{{font:12px/1.5 ui-monospace,Consolas,monospace;color:var(--ink3);white-space:nowrap;min-width:82px}}
 li.nt .t{{color:#16324f;text-decoration:none;flex:1;min-width:240px}}
 li.nt .t:hover{{color:var(--blue);text-decoration:underline}}
 .tg{{font-size:11.5px;color:var(--ink3);border:1px solid var(--line);border-radius:5px;padding:1px 7px;background:#fbfcfe;white-space:nowrap}}
 .tg.att{{color:#0f766e;border-color:#c8e8e4;background:#e6f5f3;text-decoration:none}}
 .tg.cap{{color:#854f0b;border-color:#fac775;background:#faeeda;cursor:help}}
 .tg.dead{{color:#a32d2d;border-color:#f7c1c1;background:#fcebeb;cursor:help}}
 .src{{font-size:11.5px;color:var(--blue);text-decoration:none;white-space:nowrap}}
 .src:hover{{text-decoration:underline}}
 .panel{{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-top:18px;overflow:hidden}}
 .panel h2{{margin:0;padding:13px 18px;font-size:16px;border-bottom:1px solid var(--line);background:#f7f9fc}}
 table{{width:100%;border-collapse:collapse;font-size:13.5px}}
 th,td{{padding:9px 14px;border-bottom:1px solid #eef2f7;text-align:left;vertical-align:top}}
 th{{background:#fafcfe;color:var(--ink2);font-weight:600;font-size:12.5px}}
 td a{{color:var(--blue);text-decoration:none;word-break:break-all}}
 footer{{margin-top:24px;font-size:12.5px;color:var(--ink3);text-align:center;line-height:1.9}}
 .empty{{padding:40px;text-align:center;color:var(--ink3)}}
</style></head><body><div class="wrap">
<div class="hero">
  <h1>温州大学 · 通知公告图文离线库</h1>
  <div class="sub">串行下载（单线程 · 限速 · 真实请求头）· 正文与图片已本地化 · 需统一身份认证的页面已跳过<br>
  另有 {n_cap} 个附件因原站图形验证码、{n_dead} 个因原站文件失效未能下载，详见文末列表</div>
  <div class="kpis">
    <div class="kpi"><b>{len(ok)}</b><span>已下载通知</span></div>
    <div class="kpi"><b>{len(units)}</b><span>覆盖单位</span></div>
    <div class="kpi"><b>{total_imgs}</b><span>正文图片</span></div>
    <div class="kpi"><b>{n_valid}</b><span>有效附件</span></div>
    <div class="kpi"><b>{human_size(total_bytes)}</b><span>资源总大小</span></div>
  </div>
</div>
<div class="toolbar">
  <input id="q" placeholder="搜索标题关键词，例如：申报、公示、招标、奖学金…">
  <select id="u"><option value="">全部单位</option>{opt}</select>
  <select id="s"><option value="">全部日期</option><option value="30">近 30 天</option>
    <option value="90">近 90 天</option><option value="365">近 1 年</option></select>
  <button id="clr" style="font:inherit;font-size:14px;padding:8px 14px;border:1px solid #cfd9e6;border-radius:8px;background:#fff;color:var(--ink2);cursor:pointer">重置</button>
  <span class="hint" id="stat"></span>
</div>
<div id="list">{"".join(cards)}</div>
<div class="empty" id="none" style="display:none">没有匹配的通知。</div>
<div class="panel">
  <h2>未下载的条目（需统一身份认证 / 其他异常，共 {len(skipped)} 条）</h2>
  <table><thead><tr><th>单位</th><th>标题</th><th>链接</th><th>状态</th></tr></thead>
  <tbody>{skip_rows}</tbody></table>
</div>
<div class="panel">
  <h2>附件未能下载的条目（共 {len(cap_rows)} 个：需图形验证码 {n_cap} 个 · 原站已失效 {n_dead} 个）</h2>
  <table><thead><tr><th>单位</th><th>标题</th><th>原页面</th><th>原因</th></tr></thead>
  <tbody>{cap_table}</tbody></table>
</div>
<footer>所有内容版权归温州大学各二级单位所有，本库仅作离线归档与检索之用。<br>
点击标题打开离线正文；「附件 ⤓」直接下载本地附件；「原站 ↗」跳转原始网页。<br>
标注「附件 · 需验证码」或「附件 · 已失效」的未能取得，请通过「原站 ↗」到原页面处理。</footer>
</div>
<script>
const NOW=new Date("{(maxdate or "2026-01-01")}T00:00:00");
const q=document.getElementById('q'),us=document.getElementById('u'),ss=document.getElementById('s'),
      stat=document.getElementById('stat'),noneBox=document.getElementById('none');
function apply(){{
  const kw=q.value.trim().toLowerCase(),unit=us.value,days=parseInt(ss.value||'0',10);
  let shown=0,total=0;
  document.querySelectorAll('section.unit').forEach(sec=>{{
    const uok=!unit||sec.dataset.unit===unit;let vis=0;
    sec.querySelectorAll('li.nt').forEach(li=>{{
      total++;let ok=uok;
      if(ok&&kw)ok=li.dataset.t.includes(kw);
      if(ok&&days){{const d=li.querySelector('.d').textContent.trim();
        if(d==='—')ok=false;else ok=((NOW-new Date(d+'T00:00:00'))/86400000)<=days;}}
      li.style.display=ok?'':'none';if(ok){{vis++;shown++;}}
    }});
    sec.style.display=vis?'':'none';
  }});
  stat.textContent=`显示 ${{shown}} / ${{total}} 篇`;
  noneBox.style.display=shown?'none':'';
}}
q.addEventListener('input',apply);us.addEventListener('change',apply);ss.addEventListener('change',apply);
document.getElementById('clr').onclick=()=>{{q.value='';us.value='';ss.value='';apply();}};
apply();
</script></body></html>'''

    p = DOWNLOAD / "索引.html"
    p.write_text(doc, encoding="utf-8")
    return p, len(ok), len(units), n_valid, n_cap, n_dead, total_imgs


def build_parser():
    return argparse.ArgumentParser(
        description="第 5 步：把「正文.html」的图片外链改成本地路径，并生成离线索引页",
        epilog="前置：finalize.py 已跑过（附件甄别结果会被索引页按三种状态渲染）\n"
               "下一步：python verify.py   # 完整性核对",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )


def main():
    setup_console()
    # 先解析参数：--help 必须在任何前置检查之前生效，否则新 clone 里看不到用法。
    build_parser().parse_args()

    recs, bad = load_jsonl(MANIFEST)
    if not recs:
        print("download/_manifest.jsonl 为空，先跑 python download.py")
        return
    if bad:
        print(f"[警告] manifest 有 {len(bad)} 行解析失败")

    # 同一 URL 可能被多次记录，取最后一条
    byurl = {}
    for r in recs:
        byurl[r["url"]] = r
    recs = list(byurl.values())

    fixed = sum(fix_body_html(r) for r in recs if r.get("status") == "OK")
    print(f"已修正 {fixed} 处正文图片外链 -> 本地路径")

    p, n_ok, n_units, n_valid, n_cap, n_dead, n_imgs = build_index(recs)
    print(f"索引已生成：{p}")
    print(f"已下载 {n_ok} 篇 / {n_units} 个单位 / 图片 {n_imgs} 张")
    print(f"附件：有效 {n_valid} · 需验证码 {n_cap} · 已失效 {n_dead}")
    print(f"另有 {len(recs) - n_ok} 条未下载（需认证等），已在索引页列出")


if __name__ == "__main__":
    main()
