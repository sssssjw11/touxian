# -*- coding: utf-8 -*-
"""第 2.5 步（可选）：把抓取结果渲染成单文件 HTML 报告 -> out/温大各部门通知汇总.html

这是一个总览页：只索引标题与链接，正文请点标题跳转原站。
与 download/索引.html 的区别 —— 那个是「已下载的图文离线库」的入口，本页是
「抓了多少、覆盖哪些单位、哪些站点没抓到」的统计视图。

浅色主题，支持关键词 / 单位 / 日期区间筛选，文末附需认证与未取到的站点清单。

用法：
  python report.py
  python report.py --title "温州大学通知公告汇总"
"""
from __future__ import annotations

import argparse
import csv
import html
from collections import Counter, defaultdict

from _common import OUT, load_json, setup_console


def main():
    setup_console()
    ap = argparse.ArgumentParser(description="生成抓取结果总览报告")
    ap.add_argument("--title", default="温州大学 · 各学院 / 部门 通知公告汇总")
    args = ap.parse_args()

    csv_path = OUT / "notices.csv"
    if not csv_path.is_file():
        print("缺少 out/notices.csv，先跑 python scrape.py")
        return

    raw_rows = list(csv.DictReader(open(csv_path, encoding="utf-8-sig")))
    # CSV 用中文表头，这里统一成英文键，后续逻辑只认英文键
    rows = [{"cat": r["类别"], "unit": r["单位"], "column": r["栏目"],
             "date": r["发布日期"], "title": r["标题"], "url": r["链接"],
             "home": r["站点"], "status": r["站点状态"]} for r in raw_rows]

    data = load_json(OUT / "sites_result.json") or {}
    sites = data.get("sites", [])
    no_site = data.get("no_site", [])

    total_notices = len(rows)
    dates = sorted(r["date"] for r in rows if r["date"])
    dmin, dmax = (dates[0], dates[-1]) if dates else ("-", "-")

    ok_sites = [s for s in sites if s.get("notices")]
    auth_sites = [s for s in sites if s.get("status") == "需认证-跳过"]
    fail_sites = [s for s in sites if s.get("status") == "访问失败"
                  or str(s.get("status", "")).startswith("首页HTTP")]
    none_sites = [s for s in sites if s.get("status") == "未取到通知"]

    by_unit = defaultdict(list)
    for r in rows:
        by_unit[(r["cat"], r["unit"])].append(r)
    for k in by_unit:
        by_unit[k].sort(key=lambda x: (x["date"] or "", x["title"]), reverse=True)

    order = {"学院": 0, "部门": 1, "其他": 2}
    unit_keys = sorted(by_unit.keys(), key=lambda k: (order.get(k[0], 9), -len(by_unit[k])))
    unit_counts = Counter(r["unit"] for r in rows)

    esc = lambda s: html.escape(str(s or ""))   # noqa: E731

    cards = []
    for cat, unit in unit_keys:
        items = by_unit[(cat, unit)]
        rec = next((s for s in sites if s["name"] == unit), {})
        cols = sorted({i["column"] for i in items if i["column"]})
        li = "".join(
            f'<li class="nt" data-t="{esc(i["title"]).lower()}" data-u="{esc(unit)}">'
            f'<span class="d">{esc(i["date"] or "—")}</span>'
            f'<a class="t" href="{esc(i["url"])}" target="_blank" rel="noopener">{esc(i["title"])}</a>'
            f'<span class="c">{esc(i["column"])}</span></li>'
            for i in items)
        cards.append(f'''<section class="unit" data-unit="{esc(unit)}" data-cat="{esc(cat)}">
  <header class="uh">
    <h3>{esc(unit)}</h3>
    <span class="badge cat-{esc(cat)}">{esc(cat)}</span>
    <span class="cnt">{len(items)} 条</span>
    <span class="cols">{esc(" · ".join(cols))}</span>
    <a class="home" href="{esc(rec.get("home", ""))}" target="_blank" rel="noopener">官网 ↗</a>
  </header>
  <ul class="nl">{"".join(li)}</ul>
</section>''')

    def site_rows(lst, note):
        return "".join(
            f'<tr><td>{esc(s["group"])}</td><td>{esc(s["name"])}</td>'
            f'<td><a href="{esc(s["home"])}" target="_blank" rel="noopener">{esc(s["home"])}</a></td>'
            f'<td>{esc(note)}</td></tr>' for s in lst)

    skip_rows = (site_rows(auth_sites, "需登录 / 统一身份认证，已跳过")
                 + site_rows(fail_sites, "访问失败或被拒绝")
                 + site_rows(none_sites, "未取到通知栏目"))
    no_site_rows = "".join(
        f'<tr><td>{esc(s["group"])}</td><td>{esc(s["name"])}</td>'
        f'<td colspan="2">页脚未提供独立站点入口</td></tr>' for s in no_site)

    unit_options = "".join(f'<option value="{esc(u)}">{esc(u)}（{unit_counts[u]}）</option>'
                           for cat, u in unit_keys)
    total_units = len({s["name"] for s in sites})

    html_doc = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(args.title)}</title>
<style>
  :root{{--bg:#f5f7fa;--card:#fff;--line:#e4e9f0;--ink:#1f2937;--ink2:#5b6b82;--ink3:#8b99ab;
        --blue:#1e5eb8;--blue-l:#eaf1fb;--red:#d03a3a;--amber:#b7791f;}}
  *{{box-sizing:border-box}}
  body{{margin:0;background:var(--bg);color:var(--ink);
        font:15px/1.65 "Microsoft YaHei","PingFang SC",system-ui,-apple-system,sans-serif;}}
  .wrap{{max-width:1180px;margin:0 auto;padding:28px 20px 64px}}
  .hero{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:26px 28px;
         box-shadow:0 1px 3px rgba(16,40,80,.05)}}
  .hero h1{{margin:0 0 6px;font-size:25px;letter-spacing:.4px}}
  .hero .sub{{color:var(--ink2);font-size:13.5px}}
  .kpis{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:20px}}
  .kpi{{background:var(--blue-l);border:1px solid #d7e4f7;border-radius:10px;padding:14px 16px}}
  .kpi b{{display:block;font-size:24px;color:var(--blue);line-height:1.25}}
  .kpi span{{font-size:12.5px;color:var(--ink2)}}
  .toolbar{{position:sticky;top:0;z-index:9;margin:22px 0 14px;padding:12px 14px;background:rgba(245,247,250,.94);
            backdrop-filter:blur(6px);border:1px solid var(--line);border-radius:12px;
            display:flex;gap:10px;flex-wrap:wrap;align-items:center}}
  input,select{{font:inherit;font-size:14px;padding:8px 11px;border:1px solid #cfd9e6;border-radius:8px;
                background:#fff;color:var(--ink);outline:none}}
  input:focus,select:focus{{border-color:var(--blue);box-shadow:0 0 0 3px rgba(30,94,184,.12)}}
  #q{{flex:1;min-width:220px}}
  .hint{{font-size:12.5px;color:var(--ink3)}}
  .unit{{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-bottom:14px;overflow:hidden}}
  .uh{{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:13px 18px;border-bottom:1px solid var(--line);
       background:linear-gradient(180deg,#fbfcfe,#f7f9fc)}}
  .uh h3{{margin:0;font-size:16.5px}}
  .badge{{font-size:11.5px;padding:2px 9px;border-radius:20px;border:1px solid}}
  .cat-学院{{color:#1e5eb8;background:#eaf1fb;border-color:#cfe0f6}}
  .cat-部门{{color:#0f766e;background:#e6f5f3;border-color:#c8e8e4}}
  .cat-其他{{color:#8a5a00;background:#fdf4e3;border-color:#f0e0be}}
  .cnt{{font-size:12.5px;color:var(--ink2)}}
  .cols{{font-size:12px;color:var(--ink3);flex:1;min-width:120px}}
  .home{{font-size:12.5px;color:var(--blue);text-decoration:none;border:1px solid #cfe0f6;background:#fff;
         padding:3px 10px;border-radius:7px}}
  .home:hover{{background:var(--blue-l)}}
  ul.nl{{list-style:none;margin:0;padding:6px 0}}
  li.nt{{display:flex;gap:12px;align-items:baseline;padding:7px 18px;border-bottom:1px dashed #eef2f7}}
  li.nt:last-child{{border-bottom:0}}
  li.nt:hover{{background:#f8fafd}}
  li.nt .d{{font:12px/1.5 ui-monospace,Consolas,monospace;color:var(--ink3);white-space:nowrap;min-width:82px}}
  li.nt .t{{color:#16324f;text-decoration:none;flex:1}}
  li.nt .t:hover{{color:var(--blue);text-decoration:underline}}
  li.nt .c{{font-size:11.5px;color:var(--ink3);border:1px solid var(--line);border-radius:5px;
            padding:1px 7px;white-space:nowrap;background:#fbfcfe}}
  .panel{{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-top:18px;overflow:hidden}}
  .panel h2{{margin:0;padding:14px 18px;font-size:16px;border-bottom:1px solid var(--line);background:#f7f9fc}}
  table{{width:100%;border-collapse:collapse;font-size:13.5px}}
  th,td{{padding:9px 14px;border-bottom:1px solid #eef2f7;text-align:left;vertical-align:top}}
  th{{background:#fafcfe;color:var(--ink2);font-weight:600;font-size:12.5px}}
  td a{{color:var(--blue);text-decoration:none;word-break:break-all}}
  footer{{margin-top:26px;font-size:12.5px;color:var(--ink3);text-align:center;line-height:1.9}}
  .empty{{padding:40px;text-align:center;color:var(--ink3)}}
</style></head><body><div class="wrap">

<div class="hero">
  <h1>{esc(args.title)}</h1>
  <div class="sub">数据来源：<a href="https://www.wzu.edu.cn/" target="_blank" rel="noopener"
       style="color:var(--blue)">温州大学官网</a>页脚「学院 / 部门 / 其他」各二级单位官网 ·
       抓取方式：串行模拟真实用户访问 · 数据截至 {esc(dmax) or "—"}</div>
  <div class="kpis">
    <div class="kpi"><b>{len(ok_sites)}<span style="font-size:15px;color:var(--ink3)">/{total_units}</span></b>
      <span>成功抓取的单位站点</span></div>
    <div class="kpi"><b>{total_notices}</b><span>通知公告条目</span></div>
    <div class="kpi"><b>{len(unit_keys)}</b><span>覆盖单位数</span></div>
    <div class="kpi"><b style="font-size:17px;line-height:1.6">{esc(dmin)}<br>~ {esc(dmax)}</b>
      <span>发布日期区间</span></div>
  </div>
</div>

<div class="toolbar">
  <input id="q" placeholder="搜索通知标题关键词，例如：奖学金、申报、公示、招标…">
  <select id="u"><option value="">全部单位</option>{unit_options}</select>
  <select id="s">
    <option value="">全部日期</option>
    <option value="30">近 30 天</option>
    <option value="90">近 90 天</option>
    <option value="365">近 1 年</option>
  </select>
  <button id="clr" style="font:inherit;font-size:14px;padding:8px 14px;border:1px solid #cfd9e6;
     border-radius:8px;background:#fff;color:var(--ink2);cursor:pointer">重置</button>
  <span class="hint" id="stat"></span>
</div>

<div id="list">{"".join(cards)}</div>
<div class="empty" id="none" style="display:none">没有匹配的通知，换个关键词试试。</div>

<div class="panel">
  <h2>需登录 / 未取到的站点（{len(auth_sites) + len(fail_sites) + len(none_sites)} 个）</h2>
  <table><thead><tr><th>类别</th><th>单位</th><th>站点</th><th>说明</th></tr></thead>
  <tbody>{skip_rows or '<tr><td colspan="4">无</td></tr>'}</tbody></table>
</div>

<div class="panel">
  <h2>页脚中未提供独立站点的单位（{len(no_site)} 个）</h2>
  <table><thead><tr><th>类别</th><th>单位</th><th colspan="2">说明</th></tr></thead>
  <tbody>{no_site_rows or '<tr><td colspan="4">无</td></tr>'}</tbody></table>
</div>

<footer>
  本页由脚本串行抓取生成，仅索引各站点公开的通知公告标题与链接，正文请点击标题跳转原站查看。<br>
  抓取遵守「不并发、限速、真实请求头」原则；对需身份认证的页面一律跳过，不做任何绕过。
</footer>
</div>

<script>
const NOW = new Date("{dmax or "2026-01-01"}T00:00:00");
const q = document.getElementById('q'), us = document.getElementById('u'),
      ss = document.getElementById('s'), stat = document.getElementById('stat'),
      noneBox = document.getElementById('none');

function apply(){{
  const kw = q.value.trim().toLowerCase();
  const unit = us.value, days = parseInt(ss.value || '0', 10);
  let shown = 0, total = 0;
  document.querySelectorAll('section.unit').forEach(sec => {{
    const uok = !unit || sec.dataset.unit === unit;
    let vis = 0;
    sec.querySelectorAll('li.nt').forEach(li => {{
      total++;
      let ok = uok;
      if (ok && kw) ok = li.dataset.t.includes(kw);
      if (ok && days) {{
        const d = li.querySelector('.d').textContent.trim();
        if (d === '—') ok = false;
        else ok = ((NOW - new Date(d + 'T00:00:00')) / 86400000) <= days;
      }}
      li.style.display = ok ? '' : 'none';
      if (ok) {{ vis++; shown++; }}
    }});
    sec.style.display = vis ? '' : 'none';
  }});
  stat.textContent = `显示 ${{shown}} / ${{total}} 条`;
  noneBox.style.display = shown ? 'none' : '';
}}
q.addEventListener('input', apply);
us.addEventListener('change', apply);
ss.addEventListener('change', apply);
document.getElementById('clr').onclick = () => {{ q.value=''; us.value=''; ss.value=''; apply(); }};
apply();
</script></body></html>'''

    p = OUT / "温大各部门通知汇总.html"
    p.write_text(html_doc, encoding="utf-8")
    print(f"报告已生成：{p}")
    print(f"条目 {total_notices} / 单位 {len(unit_keys)} / 成功站点 {len(ok_sites)}")
    print(f"跳过或异常 {len(auth_sites) + len(fail_sites) + len(none_sites)} 个；"
          f"页脚无独立站点 {len(no_site)} 个")


if __name__ == "__main__":
    main()
