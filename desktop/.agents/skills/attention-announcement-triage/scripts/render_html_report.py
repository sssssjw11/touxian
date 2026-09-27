"""Render a standalone HTML report from a JEV announcement triage JSON file."""

from __future__ import annotations

import argparse
import html
import json
import re
from pathlib import Path
from typing import Any


CATEGORY_LABELS = {
    "course": "课程教学",
    "assignment": "作业提交",
    "exam": "考试测验",
    "activity": "活动竞赛",
    "admin": "行政事务",
    "safety": "安全提醒",
    "employment": "实习就业",
    "resource": "资料资源",
    "noise": "低价值噪声",
    "other": "其他事项",
}
PRIORITY_LABELS = {
    "P0": "立即处理",
    "P1": "近期开工",
    "P2": "重要排期",
    "P3": "参考归档",
}
PRIORITY_ORDER = ["P0", "P1", "P2", "P3"]


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def esc(value: Any) -> str:
    return html.escape(str(value if value is not None else ""), quote=True)


def compact(value: Any, limit: int = 220) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


def category_label(value: str) -> str:
    return CATEGORY_LABELS.get(value, value or "未知")


def action_label(value: str) -> str:
    return {
        "required": "需要行动",
        "optional": "自愿/可选",
        "informational": "信息阅读",
    }.get(value, value or "未判断")


def deadline(item: dict[str, Any]) -> dict[str, Any]:
    value = item.get("judgments", {}).get("deadline", {}).get("value")
    return value if isinstance(value, dict) else {}


def meter(label: str, value: Any, tone: str) -> str:
    number = max(0, min(100, int(value or 0)))
    return (
        '<div class="meter"><span>%s</span><i><b class="%s" style="width:%d%%"></b></i><strong>%d</strong></div>'
        % (esc(label), esc(tone), number, number)
    )


def render_item(item: dict[str, Any]) -> str:
    judgments = item.get("judgments", {})
    decision = item.get("decision", {})
    priority = str(decision.get("priority") or "P3")
    category = str(judgments.get("category", {}).get("value") or "other")
    action_meta = judgments.get("action_required", {}).get("metadata", {})
    action = str(action_meta.get("kind") or "informational")
    dl = deadline(item)
    dl_text = dl.get("normalized") or "未识别明确 DDL"
    dl_status = str(dl.get("status") or "unknown")
    dl_label = {
        "overdue": "已超期",
        "due_today": "今日截止",
        "near": "临近 1-3 天",
        "ample": "宽裕",
        "unscheduled": "未排期",
        "reference": "日期参考",
        "unknown": "需复核",
    }.get(dl_status, "需复核")
    review = '<span class="review">需要复核</span>' if decision.get("needs_review") else ""
    nearby_ids = decision.get("nearby_all_mention_message_ids", [])
    mention_flag = (
        f'<span class="mention-flag">近邻 @所有人 · {esc(decision.get("nearby_all_mention_window_minutes"))} 分钟</span>'
        if decision.get("nearby_all_mention") else ""
    )
    ids = item.get("message_ids", [])
    evidence = ", ".join(str(value) for value in ids)
    preview = str(item.get("preview") or "")
    window = item.get("window", {})
    scores = judgments.get("category", {}).get("metadata", {}).get("scores", {})
    score_detail = "；".join(
        f"{category_label(str(key))} {value}"
        for key, value in sorted(scores.items(), key=lambda pair: -pair[1])
        if value
    )
    search_text = " ".join([preview, category, priority, evidence, str(dl_text), score_detail, " ".join(str(value) for value in nearby_ids)])
    pills = "".join(f'<span class="pill">{esc(value)}</span>' for value in ids)
    return f"""
<article class="item p-{esc(priority)}" data-priority="{esc(priority)}" data-category="{esc(category)}" data-action="{esc(action)}" data-search="{esc(search_text)}">
  <div class="topline"><span class="priority">{esc(priority)} · {esc(PRIORITY_LABELS.get(priority, priority))}</span><span class="category">{esc(category_label(category))}</span>{mention_flag}{review}</div>
  <h3>{esc(compact(preview, 100))}</h3>
  <div class="meta">消息窗口：{esc(window.get("start", ""))} → {esc(window.get("end", ""))}<span>证据：{esc(evidence)}</span></div>
  <div class="item-grid">
    <div class="deadline"><small>DDL / 事件</small><strong>{esc(dl_text)}</strong><em class="status-{esc(dl_status)}">{esc(dl_label)}</em></div>
    <div class="scores">{meter("重要性", decision.get("importance"), "importance")}{meter("紧迫性", decision.get("urgency"), "urgency")}{meter("风险", decision.get("risk"), "risk")}</div>
  </div>
  <div class="action"><span class="dot action-{esc(action)}"></span><b>{esc(action_label(action))}</b><span>受众：{esc(judgments.get("audience", {}).get("value", "unknown"))}</span><span>分类置信度：{float(judgments.get("category", {}).get("confidence", 0) or 0):.0%}</span></div>
  <details><summary>查看内容与判断依据</summary><div class="details-body"><p>{esc(preview)}</p><dl><dt>判断</dt><dd>{esc(decision.get("reason", ""))}</dd><dt>分类得分</dt><dd>{esc(score_detail or "无")}</dd><dt>证据消息</dt><dd>{pills or "无"}</dd><dt>近邻 @所有人</dt><dd>{esc(', '.join(str(value) for value in nearby_ids) if nearby_ids else "未发现")}</dd></dl></div></details>
</article>"""


def render_html(result: dict[str, Any]) -> str:
    source = result.get("source", {})
    summary = result.get("summary", {})
    items = result.get("items", [])
    date_filter = source.get("message_date_filter") or {}
    filter_from = date_filter.get("from") or "不限"
    filter_to = date_filter.get("to") or "不限"
    selected_range = f"{filter_from} 至 {filter_to}" if filter_from != "不限" or filter_to != "不限" else "全部归档"
    priority_counts = summary.get("priority_counts", {})
    category_counts = summary.get("category_counts", {})
    max_category = max(category_counts.values() or [1])
    priority_html = "".join(
        f'<div class="priority-summary p-{priority}"><span>{priority}</span><b>{int(priority_counts.get(priority, 0))}</b><small>{PRIORITY_LABELS[priority]}</small></div>'
        for priority in PRIORITY_ORDER
    )
    category_html = "".join(
        f'<div class="category-row"><span>{esc(category_label(category))}</span><i><b style="width:{int(count) / max_category * 100:.1f}%"></b></i><strong>{int(count)}</strong></div>'
        for category, count in sorted(category_counts.items(), key=lambda pair: (-pair[1], pair[0]))
    )
    priority_options = "".join(
        f'<option value="{priority}">{priority} · {PRIORITY_LABELS[priority]} ({int(priority_counts.get(priority, 0))})</option>'
        for priority in PRIORITY_ORDER
    )
    category_options = "".join(
        f'<option value="{esc(category)}">{esc(category_label(category))} ({int(count)})</option>'
        for category, count in sorted(category_counts.items(), key=lambda pair: (-pair[1], pair[0]))
    )
    cards = "\n".join(render_item(item) for item in items)
    actionable = sum(1 for item in items if item.get("decision", {}).get("action_kind") == "required")
    title = f"Attention 公告报告 · {source.get('conversation', '微信群')}"
    template = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
:root{--ink:#182230;--muted:#6b7685;--line:#e4e9ef;--paper:#f6f8fb;--white:#fff;--navy:#203047;--red:#c95747;--amber:#d18b20;--blue:#426fc8;--green:#36856c}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font-family:Inter,"Microsoft YaHei","Noto Sans SC",sans-serif;line-height:1.55}.shell{max-width:1240px;margin:0 auto;padding:26px 20px 70px}.hero{background:var(--navy);color:#fff;border-radius:12px;padding:30px 32px;box-shadow:0 12px 30px #20304722}.eyebrow{font-size:12px;letter-spacing:.12em;color:#adc0db;font-weight:700}.hero h1{font-size:clamp(28px,4vw,48px);line-height:1.1;margin:10px 0 12px;letter-spacing:0}.hero p{max-width:760px;color:#d7e1ee;margin:0}.hero-meta{display:flex;flex-wrap:wrap;gap:10px 22px;color:#b9c7d8;font-size:13px;margin-top:22px}.notice{margin:18px 0;padding:12px 15px;border-left:3px solid var(--amber);background:#fff9ed;color:#73511c;border-radius:6px;font-size:13px}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}.stat,.panel,.item{background:var(--white);border:1px solid var(--line);border-radius:8px}.stat{padding:16px 18px}.stat small{display:block;color:var(--muted);font-size:12px}.stat b{display:block;font-size:28px;line-height:1.2;margin-top:5px}.dashboard{display:grid;grid-template-columns:1.2fr .8fr;gap:18px}.panel{padding:20px}.panel h2{font-size:17px;margin:0 0 15px}.priority-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:9px}.priority-summary{border-top:3px solid #aab4c0;background:#fafbfd;padding:12px;min-height:90px}.priority-summary span{display:block;font-weight:800}.priority-summary b{display:block;font-size:25px}.priority-summary small{color:var(--muted)}.p-P0{border-color:var(--red)}.p-P1{border-color:var(--amber)}.p-P2{border-color:var(--blue)}.p-P3{border-color:#9aa5b3}.category-row{display:grid;grid-template-columns:86px 1fr 32px;align-items:center;gap:8px;margin:8px 0;font-size:12px}.category-row i{height:8px;background:#edf0f4;border-radius:8px;overflow:hidden}.category-row i b{display:block;height:100%;background:linear-gradient(90deg,#6e8fd2,#e5674f);border-radius:8px}.category-row strong{text-align:right}.toolbar{position:sticky;top:10px;z-index:5;margin:18px 0 12px;padding:10px 0;background:#f6f8fbee;backdrop-filter:blur(8px)}.toolbar-inner{display:grid;grid-template-columns:1.6fr .75fr .9fr .9fr auto;gap:9px}input,select,button{height:40px;border:1px solid #d5dce5;border-radius:6px;background:#fff;color:var(--ink);padding:0 12px;font:inherit}button{cursor:pointer;font-weight:700}button:hover{border-color:var(--blue);color:var(--blue)}.queue-head{display:flex;justify-content:space-between;align-items:end;margin:22px 0 10px}.queue-head h2{font-size:22px;margin:0}.queue-head small{color:var(--muted)}.queue{display:grid;gap:12px}.item{border-left:4px solid #aab4c0;padding:18px;box-shadow:0 3px 12px #1f31490b}.item.p-P0{border-left-color:var(--red)}.item.p-P1{border-left-color:var(--amber)}.item.p-P2{border-left-color:var(--blue)}.item.p-P3{border-left-color:#a4adb9}.item.hidden{display:none}.topline{display:flex;flex-wrap:wrap;gap:7px;align-items:center}.priority,.category,.review,.pill{display:inline-flex;align-items:center;border-radius:999px;padding:4px 9px;font-size:12px;font-weight:750}.priority{background:#f0f2f5}.item.p-P0 .priority{background:#fff0ee;color:#a13a2d}.item.p-P1 .priority{background:#fff5df;color:#925c0f}.item.p-P2 .priority{background:#edf3ff;color:#315cad}.item.p-P3 .priority{color:#687383}.category{background:#eef5f3;color:#31745d}.review{background:#fff0ee;color:#a13a2d}.item h3{font-size:17px;line-height:1.4;margin:13px 0 7px}.meta{display:flex;flex-wrap:wrap;gap:6px 18px;color:var(--muted);font-size:12px}.item-grid{display:grid;grid-template-columns:.9fr 1.1fr;gap:15px;margin:16px 0 12px}.deadline{background:#f8fafc;border:1px solid #edf0f4;border-radius:7px;padding:12px}.deadline small{display:block;color:var(--muted);font-size:11px}.deadline strong{display:block;margin:4px 0;font-size:15px}.deadline em{font-size:12px;font-style:normal}.status-upcoming{color:var(--green)}.status-overdue{color:#a13a2d}.status-unknown{color:#8b6a2c}.meter{display:grid;grid-template-columns:48px 1fr 26px;align-items:center;gap:8px;font-size:11px;margin:6px 0}.meter i{height:7px;background:#eef1f5;border-radius:7px;overflow:hidden}.meter i b{display:block;height:100%;border-radius:7px}.meter .importance{background:var(--blue)}.meter .urgency{background:var(--amber)}.meter .risk{background:var(--red)}.action{display:flex;flex-wrap:wrap;align-items:center;gap:9px;color:var(--muted);font-size:12px;border-top:1px solid #edf0f4;padding-top:12px}.dot{width:8px;height:8px;border-radius:50%;background:#aab4c0}.action-required{background:var(--red)}.action-optional{background:var(--amber)}.action-informational{background:var(--blue)}details{margin-top:13px;border-top:1px solid #edf0f4;padding-top:10px}summary{cursor:pointer;color:#48627f;font-size:13px;font-weight:700}.details-body p{white-space:pre-wrap;color:#374454;font-size:13px;margin:11px 0}dl{display:grid;grid-template-columns:78px 1fr;gap:7px 12px;font-size:12px}dt{color:var(--muted)}dd{margin:0;color:#3f4e60}.pill{background:#f0f2f5;color:#596676;margin:0 4px 4px 0;padding:3px 7px}.empty{text-align:center;padding:40px;color:var(--muted);background:#fff;border:1px dashed #ccd4de;border-radius:8px}.footer{color:var(--muted);font-size:12px;margin-top:30px;text-align:center}@media(max-width:820px){.stats,.dashboard{grid-template-columns:1fr 1fr}.toolbar-inner{grid-template-columns:1fr 1fr}.toolbar-inner input{grid-column:1/-1}.item-grid{grid-template-columns:1fr}}@media(max-width:560px){.shell{padding:16px 12px 50px}.hero{padding:24px 20px}.stats,.priority-grid,.dashboard{grid-template-columns:1fr 1fr}.toolbar-inner{grid-template-columns:1fr}.toolbar-inner input{grid-column:auto}.queue-head{display:block}}
 .mention-flag{display:inline-flex;align-items:center;border-radius:999px;padding:4px 9px;font-size:12px;font-weight:750;background:#eef5ff;color:#315cad}
</style>
</head>
<body>
<main class="shell">
<header class="hero"><div class="eyebrow">ATTENTION / JEV ANNOUNCEMENT TRIAGE</div><h1>__CONVERSATION__</h1><p>把群聊里的信息噪声压缩成可以行动的注意力队列：先看优先级，再看 DDL，最后回到证据。</p><div class="hero-meta"><span>消息筛选：__SELECTED_RANGE__</span><span>分析基准日：__AS_OF__</span><span>完整归档：__ARCHIVE_START__ 至 __ARCHIVE_END__</span></div></header>
<div class="notice">本报告由本地归档生成。归档结束日期早于当前日期时，不代表之后没有新公告；日期筛选只控制消息发送日期，不会把公告正文里的 DDL 当作筛选条件。</div>
<section class="stats"><div class="stat"><small>本次分析消息</small><b>__MESSAGE_COUNT__</b></div><div class="stat"><small>去重后事项</small><b>__ITEM_COUNT__</b></div><div class="stat"><small>需要行动</small><b>__ACTIONABLE__</b></div><div class="stat"><small>需要复核</small><b>__REVIEW_COUNT__</b></div></section>
<section class="dashboard"><div class="panel"><h2>优先级分布</h2><div class="priority-grid">__PRIORITY_HTML__</div></div><div class="panel"><h2>类别分布</h2>__CATEGORY_HTML__</div></section>
<section class="toolbar"><div class="toolbar-inner"><input id="search" type="search" placeholder="搜索内容、消息 ID、DDL、类别…"><select id="priority-filter"><option value="">全部优先级</option>__PRIORITY_OPTIONS__</select><select id="category-filter"><option value="">全部类别</option>__CATEGORY_OPTIONS__</select><select id="action-filter"><option value="">全部行动状态</option><option value="required">需要行动</option><option value="optional">自愿/可选</option><option value="informational">信息阅读</option></select><button id="reset" type="button">重置筛选</button></div></section>
<section class="queue-head"><h2>公告队列</h2><small id="result-count">显示 __ITEM_COUNT__ 条</small></section>
<section id="queue" class="queue">__CARDS__</section>
<footer class="footer">Generated by attention-announcement-triage · JEV-style local reducer · 证据可回溯至原始消息 ID</footer>
</main>
<script>
const cards = Array.from(document.querySelectorAll('.item'));
const search = document.getElementById('search');
const priority = document.getElementById('priority-filter');
const category = document.getElementById('category-filter');
const action = document.getElementById('action-filter');
const count = document.getElementById('result-count');
function applyFilters() {
  const query = search.value.trim().toLowerCase();
  let visible = 0;
  cards.forEach(function(card) {
    const match = (!query || (card.dataset.search || '').toLowerCase().includes(query)) &&
      (!priority.value || card.dataset.priority === priority.value) &&
      (!category.value || card.dataset.category === category.value) &&
      (!action.value || card.dataset.action === action.value);
    card.classList.toggle('hidden', !match);
    if (match) visible += 1;
  });
  count.textContent = '显示 ' + visible + ' 条';
}
[search, priority, category, action].forEach(function(element) {
  element.addEventListener('input', applyFilters);
});
document.getElementById('reset').addEventListener('click', function() {
  search.value = '';
  priority.value = '';
  category.value = '';
  action.value = '';
  applyFilters();
});
</script>
</body>
</html>"""
    replacements = {
        "__TITLE__": esc(title),
        "__CONVERSATION__": esc(source.get("conversation", "微信群")),
        "__SELECTED_RANGE__": esc(selected_range),
        "__AS_OF__": esc(source.get("as_of", "")),
        "__ARCHIVE_START__": esc(source.get("archive_start", "")),
        "__ARCHIVE_END__": esc(source.get("archive_end", "")),
        "__MESSAGE_COUNT__": str(int(source.get("message_count", 0))),
        "__ITEM_COUNT__": str(len(items)),
        "__ACTIONABLE__": str(actionable),
        "__REVIEW_COUNT__": str(int(summary.get("needs_review_count", 0))),
        "__PRIORITY_HTML__": priority_html or '<div class="empty">无</div>',
        "__CATEGORY_HTML__": category_html or '<div class="empty">当前日期范围没有公告候选</div>',
        "__PRIORITY_OPTIONS__": priority_options,
        "__CATEGORY_OPTIONS__": category_options,
        "__CARDS__": cards or '<div class="empty">这个日期范围内没有识别到公告候选。</div>',
    }
    for key, value in replacements.items():
        template = template.replace(key, value)
    return template


def main() -> None:
    parser = argparse.ArgumentParser(description="生成 Attention 公告 HTML 报告")
    parser.add_argument("--input", required=True, help="triage.json 路径")
    parser.add_argument("--output", help="HTML 输出路径；默认与 triage.json 同目录的 report.html")
    args = parser.parse_args()
    input_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve() if args.output else input_path.parent / "report.html"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(render_html(load_json(input_path)), encoding="utf-8")
    print(json.dumps({"status": "ok", "report": str(output_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
