"""mail_digest 模块测试：摘要渲染、收件人校验、两阶段发送编排。

对外部 ``agently-cli`` 一律用假实现替换，测试不触网、不真正发信。
"""

from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server import mail_digest as MD  # noqa: E402


# ------------------------------------------------------------------ 夹具

def make_item(
    title: str,
    priority: str = "P1",
    status: str = "near",
    normalized: str | None = "2026-09-27T23:00:00",
    category: str = "course",
    evidence: str = "请大家在9月27日23:00前完成重修报名，逾期不再受理。",
    state: str = "active",
    needs_review: bool = False,
) -> dict:
    return {
        "item_key": f"k-{title}",
        "preview": title,
        "title": title,
        "decision": {
            "priority": priority,
            "deadline_status": status,
            "queue_state": state,
            "needs_review": needs_review,
        },
        "judgments": {
            "deadline": {"value": {"normalized": normalized, "status": status}},
            "category": {"value": category},
        },
        "evidence": [{"content": evidence, "sender": "辅导员", "timestamp": "2026-09-25 12:00"}],
    }


def make_result(items: list[dict], conversation: str = "2023级计科1班", as_of: str = "2026-09-25") -> dict:
    counts: dict[str, int] = {}
    for item in items:
        key = item.get("decision", {}).get("priority", "P3")
        counts[key] = counts.get(key, 0) + 1
    return {
        "source": {"conversation": conversation, "as_of": as_of, "archive_start": "2026-09-20", "archive_end": "2026-09-25"},
        "summary": {"priority_counts": counts},
        "items": items,
    }


# ------------------------------------------------------------------ 收件人校验

class TestRecipients:
    def test_accepts_comma_semicolon_and_space(self):
        assert MD.validate_recipients("a@x.com, b@y.com; c@z.com d@w.com") == [
            "a@x.com", "b@y.com", "c@z.com", "d@w.com",
        ]

    def test_dedupes_and_keeps_order(self):
        assert MD.validate_recipients(["a@x.com", "a@x.com", "b@y.com"]) == ["a@x.com", "b@y.com"]

    def test_rejects_malformed(self):
        with pytest.raises(MD.MailError):
            MD.validate_recipients("not-an-email")

    def test_rejects_empty(self):
        with pytest.raises(MD.MailError):
            MD.validate_recipients("   ")

    def test_rejects_too_many(self):
        with pytest.raises(MD.MailError):
            MD.validate_recipients([f"u{i}@x.com" for i in range(21)])


# ------------------------------------------------------------------ 摘要渲染

class TestBuildDigest:
    def test_subject_carries_conversation_and_count(self):
        result = make_result([make_item("重修报名"), make_item("医保声明")])
        digest = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))
        assert "2023级计科1班" in digest["subject"]
        assert "2 条进行中" in digest["subject"]
        assert "逾期" not in digest["subject"]

    def test_subject_flags_overdue(self):
        result = make_result([
            make_item("选课", priority="P0", status="overdue", normalized="2026-09-24T13:00:00"),
            make_item("重修报名"),
        ])
        digest = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))
        assert "含逾期 1 条" in digest["subject"]

    def test_overdue_section_precedes_pending(self):
        result = make_result([
            make_item("选课", priority="P0", status="overdue", normalized="2026-09-24T13:00:00"),
            make_item("重修报名", priority="P1"),
        ])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert body.index("已逾期 · 请尽快确认") < body.index("按优先级排序的待办")
        assert "已逾期 1 天" in body

    def test_pending_grouped_by_priority(self):
        result = make_result([
            make_item("三号", priority="P3"),
            make_item("零号", priority="P0"),
            make_item("一号", priority="P1"),
        ])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert body.index("P0 · 立即处理") < body.index("P1 · 尽快处理") < body.index("P3 · 参考")

    def test_evidence_quote_included(self):
        item = make_item("重修报名", evidence="请大家在9月27日23:00前完成重修报名，逾期不再受理。")
        body = MD.build_digest(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "> " in body
        assert "9月27日23:00前完成重修报名" in body

    def test_long_evidence_truncated(self):
        item = make_item("长文本", evidence="很长的内容" * 40)
        body = MD.build_digest(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        quote_lines = [line for line in body.splitlines() if line.strip().startswith(">")]
        # 摘要先截断到 limit（90 字），再按 60 字换行；省略号只出现在最后一行
        assert quote_lines[-1].rstrip().endswith("…")
        # 每行带上 ">" 引用标记，且总长远小于原始 200 字
        assert all(line.strip().startswith("> ") for line in quote_lines)
        joined = "".join(line.strip().lstrip("> ").rstrip("…") for line in quote_lines)
        assert len(joined) <= 90

    def test_relative_due_text(self):
        result = make_result([make_item("明天到期", normalized="2026-09-26T09:00:00")])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "（明天）" in body

    def test_archived_items_excluded(self):
        result = make_result([
            make_item("进行中", state="active"),
            make_item("已归档", state="archived"),
        ])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "进行中" in body
        assert "已归档" not in body
        assert "1 条进行中" in MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["subject"]

    def test_counts_only_active_items(self):
        """概览里的优先级数字只统计进行中，避免和归档数量混淆。"""
        result = make_result([
            make_item("活跃 P0", priority="P0", state="active"),
            make_item("归档 P0", priority="P0", state="archived"),
            make_item("归档 P1", priority="P1", state="archived"),
        ])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "进行中事项 1 条（P0 1 条）" in body
        # 归档项的 P1 不应出现在进行中统计里
        assert "P1 1 条" not in body

    def test_subject_when_nothing_active(self):
        result = make_result([make_item("已归档", state="archived")])
        digest = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))
        assert "暂无进行中事项" in digest["subject"]
        assert "0 条进行中" not in digest["subject"]

    def test_no_active_items(self):
        result = make_result([make_item("已归档", state="archived")])
        body = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "当前没有进行中的待办事项" in body

    def test_needs_review_marker(self):
        item = make_item("待复核", needs_review=True)
        body = MD.build_digest(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "待人工复核" in body

    def test_no_agent_signature(self):
        """技能规范：不添加 Agent 自己的署名。"""
        body = MD.build_digest(make_result([make_item("事项")]), ["a@x.com"], date(2026, 9, 25))["body"]
        for banned in ("CodeBuddy", "Agent 发送", "AI 助手", "由 Agent"):
            assert banned not in body

    def test_as_of_falls_back_to_source(self):
        result = make_result([make_item("明天到期", normalized="2026-09-26T09:00:00")], as_of="2026-09-25")
        body = MD.build_digest(result, ["a@x.com"])["body"]
        assert "（明天）" in body

    def test_unscheduled_item_renders_label(self):
        item = make_item("无日期", status="unscheduled", normalized=None)
        body = MD.build_digest(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "未排期" in body

    def test_clean_title_strips_newlines(self):
        item = make_item("第一行\n第二行")
        body = MD.build_digest(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "第一行 第二行" in body


# ------------------------------------------------------------------ HTML 渲染

class TestBuildDigestHtml:
    def test_is_full_html_document(self):
        html = MD.build_digest_html(make_result([make_item("事项")]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert html.startswith("<!DOCTYPE html>")
        assert html.rstrip().endswith("</html>")
        assert 'charset="UTF-8"' in html

    def test_subject_matches_text_version(self):
        result = make_result([make_item("重修报名"), make_item("医保声明")])
        text = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))
        assert text["subject"] == html["subject"]

    def test_same_counts_as_text_version(self):
        """两种格式口径必须一致：只统计进行中。"""
        result = make_result([
            make_item("活跃 P0", priority="P0", state="active"),
            make_item("归档 P1", priority="P1", state="archived"),
        ])
        text = MD.build_digest(result, ["a@x.com"], date(2026, 9, 25))["body"]
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "进行中事项 1 条（P0 1 条）" in text
        # HTML 里用独立 badge 呈现
        assert "P0 1" in html
        assert "P1 1" not in html

    def test_escapes_user_content(self):
        """聊天原文里的尖括号必须转义，防止注入破坏邮件结构。"""
        item = make_item("标题", evidence="<script>alert(1)</script> 以及 <img onerror=x>")
        html = MD.build_digest_html(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "<script>" not in html
        assert "&lt;script&gt;" in html
        assert "<img onerror" not in html

    def test_escapes_title(self):
        item = make_item("危险<b>标题</b>")
        html = MD.build_digest_html(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "<b>标题</b>" not in html
        assert "&lt;b&gt;" in html

    def test_escapes_conversation_name(self):
        result = make_result([make_item("事项")], conversation="<script>群</script>")
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "<script>群</script>" not in html

    def test_overdue_block_present_and_before_pending(self):
        result = make_result([
            make_item("已逾期项", priority="P0", status="overdue", normalized="2026-09-24T13:00:00"),
            make_item("待办项", priority="P1"),
        ])
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "已逾期 · 请尽快确认" in html
        # 逾期警示块必须排在按优先级分组之前
        assert html.index("已逾期 · 请尽快确认") < html.index("P1 · 尽快处理")

    def test_priority_group_labels(self):
        result = make_result([make_item("三号", priority="P3"), make_item("零号", priority="P0")])
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert html.index("P0 · 立即处理") < html.index("P3 · 参考")

    def test_countdown_colored(self):
        result = make_result([make_item("明天到期", normalized="2026-09-26T09:00:00")])
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "（明天）" in html

    def test_evidence_snippet_included(self):
        item = make_item("重修报名", evidence="请于9月27日23:00前完成重修报名，逾期不再受理。")
        html = MD.build_digest_html(make_result([item]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "9月27日23:00前完成重修报名" in html

    def test_empty_state(self):
        result = make_result([make_item("已归档", state="archived")])
        html = MD.build_digest_html(result, ["a@x.com"], date(2026, 9, 25))["body"]
        assert "当前没有进行中的待办事项" in html

    def test_no_agent_signature(self):
        html = MD.build_digest_html(make_result([make_item("事项")]), ["a@x.com"], date(2026, 9, 25))["body"]
        for banned in ("CodeBuddy", "由 Agent", "AI 助手"):
            assert banned not in html

    def test_uses_inline_styles_only(self):
        """邮件客户端会剥掉 <style> 与外部 CSS，样式必须内联。"""
        html = MD.build_digest_html(make_result([make_item("事项")]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert "<style" not in html
        assert "class=" not in html
        assert "<link" not in html

    def test_table_based_layout(self):
        """用 table 布局保证 Outlook 等客户端正确渲染。"""
        html = MD.build_digest_html(make_result([make_item("事项")]), ["a@x.com"], date(2026, 9, 25))["body"]
        assert 'role="presentation"' in html
        assert "border-collapse" in html


# ------------------------------------------------------------------ 两阶段发送

class FakeCLI:
    """记录每次调用的参数，按序返回预设 envelope。"""

    def __init__(self, responses: list[dict]):
        self.responses = list(responses)
        self.calls: list[list[str]] = []
        self.cwds: list = []

    def __call__(self, args, timeout=MD.CLI_TIMEOUT, cwd=None):
        self.calls.append(list(args))
        self.cwds.append(cwd)
        if not self.responses:
            raise AssertionError(f"没有预设响应，却收到调用：{args}")
        return self.responses.pop(0)


@pytest.fixture()
def ready_status(monkeypatch):
    monkeypatch.setattr(MD, "mail_status", lambda: {"ready": True, "sender": "sender@example.com", "cli": "agently-cli"})


class TestTwoPhase:
    def test_prepare_returns_token_without_sending(self, monkeypatch, ready_status, tmp_path):
        fake = FakeCLI([{
            "ok": True,
            "data": {
                "confirmation_token": "ctk_abc",
                "summary": {"action": "send", "to": ["a@x.com"], "subject": "S"},
            },
        }])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        prepared = MD.prepare_send(make_result([make_item("重修报名")]), "a@x.com", date(2026, 9, 25))

        assert prepared["confirmation_token"] == "ctk_abc"
        assert prepared["recipients"] == ["a@x.com"]
        assert prepared["sender"] == "sender@example.com"
        assert prepared["summary"]["action"] == "send"
        # 第一阶段绝不能带确认令牌
        assert "--confirmation-token" not in fake.calls[0]
        assert fake.calls[0][:2] == ["message", "+send"]
        # 正文已落盘，且包含 DDL 摘要
        body_file = Path(prepared["body_file"])
        assert body_file.exists()
        assert "重修报名" in body_file.read_text(encoding="utf-8")

    def test_body_file_must_be_relative(self, monkeypatch, ready_status, tmp_path):
        """CLI 明确拒绝绝对路径的 --body-file，必须传相对名 + cwd。"""
        fake = FakeCLI([{"ok": True, "data": {"confirmation_token": "ctk_abc"}}])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        MD.prepare_send(make_result([make_item("事项")]), "a@x.com", date(2026, 9, 25))

        argv = fake.calls[0]
        body_arg = argv[argv.index("--body-file") + 1]
        assert not Path(body_arg).is_absolute(), f"--body-file 必须是相对路径，收到 {body_arg}"
        # 默认走 HTML 富文本
        assert body_arg.endswith(".html")
        # cwd 必须指向正文所在目录，否则相对路径解析不到
        assert Path(fake.cwds[0]) == tmp_path

    def test_body_file_suffix_follows_format(self, monkeypatch, ready_status, tmp_path):
        """后缀决定 CLI 的渲染方式：html → .html，text → .txt。"""
        fake = FakeCLI([
            {"ok": True, "data": {"confirmation_token": "ctk_a"}},
            {"ok": True, "data": {"confirmation_token": "ctk_b"}},
        ])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        html_prep = MD.prepare_send(make_result([make_item("事项")]), "a@x.com", date(2026, 9, 25), "html")
        text_prep = MD.prepare_send(make_result([make_item("事项")]), "a@x.com", date(2026, 9, 25), "text")

        assert Path(html_prep["body_file"]).suffix == ".html"
        assert html_prep["body_format"] == "html"
        assert html_prep["body"].startswith("<!DOCTYPE html>")

        assert Path(text_prep["body_file"]).suffix == ".txt"
        assert text_prep["body_format"] == "text"
        assert not text_prep["body"].startswith("<!DOCTYPE html>")

    def test_send_body_file_suffix_follows_format(self, monkeypatch, tmp_path):
        fake = FakeCLI([
            {"ok": True, "data": {"queued": True}},
            {"ok": True, "data": {"queued": True}},
        ])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        MD.send_confirmed("ctk_a", "a@x.com", "S", "<p>B</p>", "html")
        MD.send_confirmed("ctk_b", "a@x.com", "S", "B", "text")

        assert fake.calls[0][fake.calls[0].index("--body-file") + 1].endswith(".html")
        assert fake.calls[1][fake.calls[1].index("--body-file") + 1].endswith(".txt")

    def test_send_body_file_must_be_relative(self, monkeypatch, tmp_path):
        fake = FakeCLI([{"ok": True, "data": {"message_id": "msg_1"}}])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        MD.send_confirmed("ctk_abc", "a@x.com", "主题", "正文")

        argv = fake.calls[0]
        body_arg = argv[argv.index("--body-file") + 1]
        assert not Path(body_arg).is_absolute()
        assert Path(fake.cwds[0]) == tmp_path

    def test_send_confirmed_passes_token(self, monkeypatch, tmp_path):
        fake = FakeCLI([{"ok": True, "data": {"message_id": "msg_1"}}])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)

        sent = MD.send_confirmed("ctk_abc", "a@x.com", "主题", "正文")

        assert sent["sent"] is True
        assert sent["message_id"] == "msg_1"
        assert "--confirmation-token" in fake.calls[0]
        assert "ctk_abc" in fake.calls[0]

    def test_send_rejects_non_ctk_token(self, tmp_path, monkeypatch):
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)
        with pytest.raises(MD.MailError):
            MD.send_confirmed("bogus", "a@x.com", "主题", "正文")

    def test_send_rejects_empty_subject(self, tmp_path, monkeypatch):
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)
        with pytest.raises(MD.MailError):
            MD.send_confirmed("ctk_abc", "a@x.com", "  ", "正文")

    def test_send_rejects_empty_body(self, tmp_path, monkeypatch):
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)
        with pytest.raises(MD.MailError):
            MD.send_confirmed("ctk_abc", "a@x.com", "主题", "  ")

    def test_prepare_blocks_when_not_ready(self, monkeypatch):
        monkeypatch.setattr(MD, "mail_status", lambda: {"ready": False, "reason": "未安装 agently-cli"})
        with pytest.raises(MD.MailError) as exc:
            MD.prepare_send(make_result([make_item("事项")]), "a@x.com", date(2026, 9, 25))
        assert "未安装" in str(exc.value)

    def test_prepare_blocks_on_bad_recipient(self, monkeypatch, ready_status):
        with pytest.raises(MD.MailError):
            MD.prepare_send(make_result([make_item("事项")]), "bad-addr", date(2026, 9, 25))

    def test_missing_token_in_response_raises(self, monkeypatch, ready_status, tmp_path):
        fake = FakeCLI([{"ok": True, "data": {"summary": {}}}])
        monkeypatch.setattr(MD, "run_cli", fake)
        monkeypatch.setattr(MD, "OUTBOX", tmp_path)
        with pytest.raises(MD.MailError):
            MD.prepare_send(make_result([make_item("事项")]), "a@x.com", date(2026, 9, 25))


# ------------------------------------------------------------------ CLI 输出解析

class TestCliParsing:
    def test_parses_clean_stdout(self, monkeypatch):
        """真实情形：stdout 是干净 JSON，tip 走 stderr。"""

        class Completed:
            returncode = 0
            stdout = '{\n  "ok": true,\n  "data": {"a": 1}\n}\n'
            stderr = "tip: agently-cli message +list\n"

        monkeypatch.setattr(MD, "cli_path", lambda: "agently-cli")
        monkeypatch.setattr(MD.subprocess, "run", lambda *a, **k: Completed())
        assert MD.run_cli(["+me"])["data"] == {"a": 1}

    def test_tolerates_tip_on_stdout(self, monkeypatch):
        """防御性：即便上游把 tip 混进 stdout，也要能取出第一个 JSON 对象。"""

        class Completed:
            returncode = 0
            stdout = '{\n  "ok": true,\n  "data": {"a": 1}\n}\ntip: something\n'
            stderr = ""

        monkeypatch.setattr(MD, "cli_path", lambda: "agently-cli")
        monkeypatch.setattr(MD.subprocess, "run", lambda *a, **k: Completed())
        assert MD.run_cli(["+me"])["data"] == {"a": 1}

    def test_missing_cli_raises(self, monkeypatch):
        monkeypatch.setattr(MD, "cli_path", lambda: None)
        with pytest.raises(MD.MailError) as exc:
            MD.run_cli(["+me"])
        assert "未找到 agently-cli" in str(exc.value)

    def test_exit_code_3_maps_to_auth_hint(self, monkeypatch):
        class Completed:
            returncode = 3
            stdout = '{"ok": false, "error": {"message": "token expired"}}'
            stderr = ""

        monkeypatch.setattr(MD, "cli_path", lambda: "agently-cli")
        monkeypatch.setattr(MD.subprocess, "run", lambda *a, **k: Completed())
        with pytest.raises(MD.MailError) as exc:
            MD.run_cli(["+me"])
        assert "auth login" in str(exc.value)

    def test_unparseable_output_raises(self, monkeypatch):
        class Completed:
            returncode = 0
            stdout = "not json at all"
            stderr = ""

        monkeypatch.setattr(MD, "cli_path", lambda: "agently-cli")
        monkeypatch.setattr(MD.subprocess, "run", lambda *a, **k: Completed())
        with pytest.raises(MD.MailError) as exc:
            MD.run_cli(["+me"])
        assert "没有返回可解析" in str(exc.value)

    def test_status_not_ready_when_cli_absent(self, monkeypatch):
        monkeypatch.setattr(MD, "cli_path", lambda: None)
        st = MD.mail_status()
        assert st["ready"] is False
        assert "未安装" in st["reason"]
