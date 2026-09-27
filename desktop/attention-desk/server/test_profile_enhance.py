"""画像增强的集成测试：灰度保证、门控防翻案、以及端点行为。

最关键的一条是不变量：**关闭画像时，输出必须与没有画像模块时逐字节一致**。
这条保证了画像是一个纯增量的旁路，不会影响既有用户。
"""

from __future__ import annotations

import json
import sys
from datetime import date, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent))

import profile_store as PROFILE
import main as M


def _ts(text):
    """把 'YYYY-MM-DD HH:MM' 转成 Unix 时间戳（triage 脚本期望整数）。"""
    return int(datetime.strptime(text, "%Y-%m-%d %H:%M").timestamp())


ACTIVE_PROFILE = {
    "enabled": True,
    "college": "计算机与人工智能学院",
    "major": "计算机科学与技术",
    "grade": "2023",
    "notes": "关注竞赛和实习",
    "interests": ["竞赛", "实习"],
}

INACTIVE_PROFILE = {**PROFILE.default_profile()}


@pytest.fixture(autouse=True)
def _isolate_profile(tmp_path, monkeypatch):
    """把画像文件指向临时目录，避免污染真实 data/。"""
    monkeypatch.setattr(PROFILE, "PROFILE_PATH", tmp_path / "profile.json")
    yield


def _source():
    return {
        "contact_display": "测试群",
        "source_kind": "file",
        "messages": [
            {
                "local_id": 1,
                "message_uid": "u1",
                "timestamp": _ts("2025-03-01 10:00"),
                "sender": "辅导员",
                "content": "计算机与人工智能学院关于举办程序设计竞赛的通知，2023级学生均可报名，3月14日截止。",
                "type": "text",
            },
            {
                "local_id": 2,
                "message_uid": "u2",
                "timestamp": _ts("2025-03-02 10:00"),
                "sender": "外院老师",
                "content": "外国语学院英语演讲比赛报名通知，请各班班长于3月14日前把参赛名单汇总上报。",
                "type": "text",
            },
        ],
    }


def _candidate(text, uid="u1"):
    return {
        "candidate_id": "cand-1",
        "text": text,
        "messages": [{"message_uid": uid, "sender": "s", "timestamp": _ts("2025-03-01 10:00"), "content": text, "type": "text"}],
    }


# ------------------------------------------------------------------ 灰度：关闭画像 = 原样

class TestGrayRelease:
    def test_no_profile_leaves_no_trace_in_result(self):
        source = _source()
        items, raw, lookup = M.local_items(source, date(2025, 3, 10))
        result = M.make_result(source, items, raw, date(2025, 3, 10), {"kind": "local"}, lookup, profile=None)
        assert result["profile"]["applied"] is False
        assert result["profile"]["changed"] == []
        assert "profile" not in result["summary"]

    def test_inactive_profile_leaves_no_trace(self):
        source = _source()
        items, raw, lookup = M.local_items(source, date(2025, 3, 10))
        result = M.make_result(source, items, raw, date(2025, 3, 10), {"kind": "local"}, lookup, profile=INACTIVE_PROFILE)
        assert result["profile"]["applied"] is False
        assert result["profile"]["changed"] == []

    def test_disabled_and_absent_produce_identical_items(self):
        """核心不变量：关闭画像与传 None，判断结果完全一致。"""
        as_of = date(2025, 3, 10)

        src_a = _source()
        items_a, raw_a, lookup_a = M.local_items(src_a, as_of)
        res_a = M.make_result(src_a, items_a, raw_a, as_of, {"kind": "local"}, lookup_a, profile=None)

        src_b = _source()
        items_b, raw_b, lookup_b = M.local_items(src_b, as_of)
        res_b = M.make_result(src_b, items_b, raw_b, as_of, {"kind": "local"}, lookup_b, profile=INACTIVE_PROFILE)

        assert json.dumps(res_a, ensure_ascii=False, sort_keys=True) == json.dumps(res_b, ensure_ascii=False, sort_keys=True)


# ------------------------------------------------------------------ 注入

class TestQuestionPayloadInjection:
    def test_viewer_and_relevance_present_when_active(self):
        payload = M.question_payload(_candidate("通知"), {"contact_display": "群"}, date(2025, 3, 10), "m", ACTIVE_PROFILE)
        assert payload["state"]["viewer"]["college"] == "计算机与人工智能学院"
        assert payload["state"]["viewer_note"]
        assert "relevance" in payload["questions"]

    def test_no_injection_when_disabled(self):
        payload = M.question_payload(_candidate("通知"), {"contact_display": "群"}, date(2025, 3, 10), "m", INACTIVE_PROFILE)
        assert "viewer" not in payload["state"]
        assert "relevance" not in payload["questions"]

    def test_deepseek_prompt_renders_viewer_block(self):
        payload = M.question_payload(_candidate("通知"), {"contact_display": "群"}, date(2025, 3, 10), "m", ACTIVE_PROFILE)
        user = M.build_deepseek_messages(payload)[1]["content"]
        assert PROFILE.VIEWER_OPEN in user and PROFILE.VIEWER_CLOSE in user
        assert '"relevance"' in user

    def test_deepseek_prompt_clean_without_profile(self):
        payload = M.question_payload(_candidate("通知"), {"contact_display": "群"}, date(2025, 3, 10), "m", None)
        user = M.build_deepseek_messages(payload)[1]["content"]
        assert PROFILE.VIEWER_OPEN not in user
        assert '"relevance"' not in user


# ------------------------------------------------------------------ 门控

def _reduce_with_relevance(candidate_text, relevance, confidence, as_of=date(2025, 3, 10)):
    """跑一遍本地 reducer，再套用一次 relevance 答案，返回 item。"""
    candidate = _candidate(candidate_text)
    item = M.TRIAGE.reduce_priority(M.TRIAGE.judge_candidate(candidate, as_of), as_of)
    M.apply_jev_answers(item, {"relevance": {"score": relevance, "confidence": confidence}}, ACTIVE_PROFILE)
    M.TRIAGE.reduce_priority(item, as_of)
    return item


class TestRelevanceGate:
    def test_hard_signal_floor_raises_relevance(self):
        item = _reduce_with_relevance("计算机与人工智能学院2023级程序设计竞赛报名", 8, 0.9)
        judgment = item["judgments"]["relevance"]
        assert judgment["value"] >= 70
        assert "本地命中画像关键词" in judgment["reason"]

    def test_weak_signal_uses_lower_floor(self):
        item = _reduce_with_relevance("计算机与人工智能学院通知", 8, 0.9)
        assert item["judgments"]["relevance"]["value"] >= 70

    def test_no_signal_keeps_model_answer(self):
        item = _reduce_with_relevance("外国语学院英语角活动", 15, 0.9)
        assert item["judgments"]["relevance"]["value"] == 15

    def test_low_score_not_inflated_to_full(self):
        """回归：relevance=5 必须读作「几乎无关」，不能被当成 5 星制放大成 100。"""
        item = _reduce_with_relevance("外国语学院英语角活动", 5, 0.9)
        assert item["judgments"]["relevance"]["value"] == 5

    def test_relevance_never_changes_record_kind(self):
        """relevance 完全不碰 record_kind / is_announcement。"""
        candidate = _candidate("今天天气不错，聊聊吧")
        before = M.TRIAGE.judge_candidate(candidate, date(2025, 3, 10))
        kind_before = before["judgments"]["record_kind"]["value"]
        ann_before = before["judgments"]["is_announcement"]["value"]

        item = M.TRIAGE.reduce_priority(M.TRIAGE.judge_candidate(candidate, date(2025, 3, 10)), date(2025, 3, 10))
        M.apply_jev_answers(item, {"relevance": {"score": 100, "confidence": 0.99}}, ACTIVE_PROFILE)
        assert item["judgments"]["record_kind"]["value"] == kind_before
        assert item["judgments"]["is_announcement"]["value"] == ann_before

    def test_relevance_does_not_touch_deadline(self):
        item = _reduce_with_relevance("3月14日截止，计算机与人工智能学院竞赛报名", 8, 0.9)
        # DDL 由本地解析器掌控，relevance 分支不得改写
        assert item["judgments"]["deadline"]["value"] is not None
        assert "relevance" not in item["judgments"]["deadline"].get("reason", "")

    def test_low_confidence_relevance_rejected(self):
        item = _reduce_with_relevance("计算机与人工智能学院通知", 90, 0.3)
        assert "relevance" not in item["judgments"]
        assert any(entry.startswith("relevance:") for entry in item["provider_trace"]["rejected"])

    def test_invalid_relevance_rejected(self):
        candidate = _candidate("计算机与人工智能学院通知")
        item = M.TRIAGE.reduce_priority(M.TRIAGE.judge_candidate(candidate, date(2025, 3, 10)), date(2025, 3, 10))
        M.apply_jev_answers(item, {"relevance": {"score": "not-a-number", "confidence": 0.9}}, ACTIVE_PROFILE)
        assert "relevance" not in item["judgments"]
        assert "relevance:invalid_score" in item["provider_trace"]["rejected"]

    def test_relevance_ignored_when_profile_off(self):
        candidate = _candidate("计算机与人工智能学院通知")
        item = M.TRIAGE.reduce_priority(M.TRIAGE.judge_candidate(candidate, date(2025, 3, 10)), date(2025, 3, 10))
        # 画像关闭时，relevance 问题根本不会下发；即便模型多答也不接受
        M.apply_jev_answers(item, {"relevance": {"score": 90, "confidence": 0.9}}, INACTIVE_PROFILE)
        assert "relevance" not in item["judgments"]
        assert item["provider_trace"]["profile_applied"] is False

    def test_profile_applied_flag_set(self):
        item = _reduce_with_relevance("通知", 50, 0.9)
        assert item["provider_trace"]["profile_applied"] is True


# ------------------------------------------------------------------ 加权生效

class TestWeightingEndToEnd:
    def test_weighting_sinks_unrelated_announcement(self):
        """构造一条本地判为 P2、但不命中画像的公告，验证被沉到 P3。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        target = None
        for item in items:
            text = item.get("preview", "")
            if "计算机" in text:
                item["judgments"]["relevance"] = {"value": 92, "confidence": 0.9}
            else:
                item["judgments"]["relevance"] = {"value": 6, "confidence": 0.9}
                target = item
        # 前提校验：本地把第二条判成了 P2（否则无所谓「沉」）
        assert target is not None
        assert target["decision"]["priority"] == "P2", target["decision"]["priority"]

        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        assert result["profile"]["applied"] is True
        assert result["summary"]["profile"]["sunk"] >= 1
        sunk_entry = [entry for entry in result["profile"]["changed"] if entry["sunk"]]
        assert sunk_entry and sunk_entry[0]["priority_after"] == "P3"

    def test_summary_counts_weighted_items(self):
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            item["judgments"]["relevance"] = {"value": 40, "confidence": 0.9}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        assert result["summary"]["profile"]["weighted"] >= 1


# ------------------------------------------------------------------ 强介入：升级

class TestPromotion:
    def test_hard_signal_promotes_regardless_of_model_score(self):
        """本地命中学院/年级即可升级，即便模型把相关度压得很低。

        构造：一条本地判为 P3 的公告，原文点名「计算机与人工智能学院 2023级」
        （命中 2 类硬信号），但模型只给 relevance=30。硬信号通道应把它升到 P2。
        """
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        assert items, "样例应至少产出一条 item"
        target = items[0]
        target["judgments"]["relevance"] = {"value": 30, "confidence": 0.9}
        before = target["decision"]["priority"]

        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        promoted = [e for e in result["profile"]["changed"] if e["promoted"]]
        assert promoted, "命中硬信号应触发升级"
        entry = promoted[0]
        assert entry["local_hits"], "升级条目应带本地命中记录"
        assert entry["priority_after"] == PROFILE.PROFILE_PROMOTE_TARGETS[before]

    def test_model_relevance_promotes_when_high_and_confident(self):
        """无本地命中时，模型高相关 + 高置信也能升级。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            # 既让本地不命中（内容里不含画像词），又给模型高相关。
            item["preview"] = "关于组织参观校史馆活动的通知，请有兴趣的同学报名。"
            item["messages"] = []
            item["judgments"]["relevance"] = {"value": 88, "confidence": 0.85}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        promoted = [e for e in result["profile"]["changed"] if e["promoted"]]
        assert promoted, "高相关高置信应触发升级"
        assert promoted[0]["local_hits"] == []

    def test_low_confidence_does_not_promote(self):
        """相关度高但置信度不足 0.6、且无本地命中时，不升级。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            item["preview"] = "关于组织参观校史馆活动的通知，请有兴趣的同学报名。"
            item["messages"] = []
            item["judgments"]["relevance"] = {"value": 95, "confidence": 0.3}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        assert not [e for e in result["profile"]["changed"] if e["promoted"]]

    def test_never_promotes_beyond_p1(self):
        """升级上限：P1 不再升，永不进入 P0。"""
        assert PROFILE.promote_priority("P1", 95, 0.95, ["学院:计算机"], False) == ("P1", None)
        assert PROFILE.promote_priority("P0", 95, 0.95, ["学院:计算机"], False) == ("P0", None)

    def test_promotion_caps_at_one_level(self):
        """P3 只能升到 P2，不能一步到 P1。"""
        new_priority, reason = PROFILE.promote_priority("P3", 95, 0.95, ["学院:计算机"], False)
        assert new_priority == "P2"
        assert reason

    def test_missing_relevance_never_promotes(self):
        """模型没答相关度（None）时不干预，避免与「模型说不相关」混淆。"""
        assert PROFILE.promote_priority("P3", None, 0.0, [], False) == ("P3", None)

    def test_archived_item_not_promoted(self):
        """已归档（逾期）条目不升级：升级只对队列内条目有意义。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        promoted_any = False
        for item in items:
            if item["decision"].get("queue_state") != "active":
                item["judgments"]["relevance"] = {"value": 95, "confidence": 0.95}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        for entry in result["profile"]["changed"]:
            if entry["promoted"]:
                promoted_any = True
        # 只要没有归档条目被升级即可（active 条目允许升级）
        archived_promoted = [
            e for e in result["profile"]["changed"]
            if e["promoted"] and e["priority_after"] == "P0"
        ]
        assert not archived_promoted, "任何情况下都不得升入 P0"

    def test_summary_exposes_promoted_count(self):
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            item["judgments"]["relevance"] = {"value": 95, "confidence": 0.95}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        assert "promoted" in result["summary"]["profile"]
        assert result["summary"]["profile"]["promoted"] >= 1

    def test_promoted_flag_recorded_on_decision(self):
        """升级后 decision 上应留下可追溯标记与原始优先级。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            item["judgments"]["relevance"] = {"value": 95, "confidence": 0.95}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=ACTIVE_PROFILE)
        marked = [i for i in items if i["decision"].get("profile_promoted")]
        assert marked, "应有条目被标记为升级"
        for item in marked:
            assert item["decision"]["profile_priority_before"] in {"P2", "P3"}
            assert item["decision"]["priority"] != "P0"

    def test_disabled_profile_produces_no_promotion(self):
        """关闭画像时升级通道完全静默，保持灰度不变量。"""
        as_of = date(2025, 3, 10)
        source = _source()
        items, raw, lookup = M.local_items(source, as_of)
        for item in items:
            item["judgments"]["relevance"] = {"value": 99, "confidence": 0.99}
        result = M.make_result(source, items, raw, as_of, {"kind": "local"}, lookup, profile=INACTIVE_PROFILE)
        assert result["profile"]["applied"] is False
        assert not any(i["decision"].get("profile_promoted") for i in items)


# ------------------------------------------------------------------ 端点

client = TestClient(M.app)


class TestProfileEndpoints:
    def test_get_default_profile(self):
        response = client.get("/api/profile")
        assert response.status_code == 200
        body = response.json()
        assert body["active"] is False
        assert len(body["colleges"]) == len(PROFILE.COLLEGES)
        assert body["grade_range"] == [PROFILE.GRADE_MIN, PROFILE.GRADE_MAX]

    def test_post_then_get_roundtrip(self):
        response = client.post("/api/profile", json=ACTIVE_PROFILE)
        assert response.status_code == 200
        assert response.json()["active"] is True

        fetched = client.get("/api/profile").json()
        assert fetched["profile"]["college"] == "计算机与人工智能学院"
        assert fetched["profile"]["interests"] == ["竞赛", "实习"]

    def test_post_unknown_college_rejected(self):
        response = client.post("/api/profile", json={**ACTIVE_PROFILE, "college": "霍格沃茨"})
        assert response.status_code == 400
        assert "未知学院" in response.json()["detail"]

    def test_post_enable_without_fields_rejected(self):
        response = client.post("/api/profile", json={"enabled": True})
        assert response.status_code == 400

    def test_post_disabled_clears_active(self):
        client.post("/api/profile", json=ACTIVE_PROFILE)
        response = client.post("/api/profile", json={**ACTIVE_PROFILE, "enabled": False})
        assert response.json()["active"] is False

    def test_corrupt_profile_file_falls_back_to_default(self):
        PROFILE.PROFILE_PATH.parent.mkdir(parents=True, exist_ok=True)
        PROFILE.PROFILE_PATH.write_text("{ not json", encoding="utf-8")
        response = client.get("/api/profile")
        assert response.status_code == 200
        assert response.json()["profile"] == PROFILE.default_profile()
