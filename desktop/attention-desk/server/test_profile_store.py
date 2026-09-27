"""画像模块单元测试：校验、硬信号、prompt 片段、以及优先级后处理。

设计原则（对应已确认的产品决策）：
- 单人单画像：只存一份，字段可选。
- 默认关闭：``enabled=false`` 时链路与没有这个模块时完全一致。
- 温和降权：最低 ×0.6。
- 弱否决：relevance 低 + 高置信 + 无硬信号时才沉 P3。
- 防翻案：本地命中画像关键词时不允许被判成无关。
"""

from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import profile_store as P


# ------------------------------------------------------------------ 校验

class TestValidateProfile:
    def test_defaults_are_disabled_and_empty(self):
        profile = P.default_profile()
        assert profile["enabled"] is False
        assert profile["college"] == ""
        assert profile["interests"] == []

    def test_unknown_college_rejected_when_strict(self):
        with pytest.raises(P.ProfileError):
            P.validate_profile({"college": "不存在学院"}, strict=True)

    def test_unknown_college_downgraded_when_lax(self):
        profile = P.validate_profile({"college": "不存在学院"}, strict=False)
        assert profile["college"] == ""

    def test_grade_out_of_range_rejected(self):
        with pytest.raises(P.ProfileError):
            P.validate_profile({"grade": "1999"}, strict=True)
        with pytest.raises(P.ProfileError):
            P.validate_profile({"grade": "abc"}, strict=True)

    def test_grade_in_range_accepted(self):
        assert P.validate_profile({"grade": "2023"}, strict=True)["grade"] == "2023"

    def test_interests_accept_string_or_list(self):
        from_string = P.validate_profile({"interests": "竞赛、实习, 考研"}, strict=True)
        assert from_string["interests"] == ["竞赛", "实习", "考研"]
        from_list = P.validate_profile({"interests": ["竞赛", "竞赛", ""]}, strict=True)
        assert from_list["interests"] == ["竞赛"]

    def test_interests_capped(self):
        profile = P.validate_profile({"interests": [f"t{i}" for i in range(40)]}, strict=True)
        assert len(profile["interests"]) == P.INTERESTS_LIMIT

    def test_notes_truncated(self):
        profile = P.validate_profile({"notes": "x" * 1000}, strict=True)
        assert len(profile["notes"]) == P.NOTES_LIMIT

    def test_enable_without_any_field_rejected(self):
        with pytest.raises(P.ProfileError):
            P.validate_profile({"enabled": True}, strict=True)

    def test_enable_without_any_field_downgraded_when_lax(self):
        assert P.validate_profile({"enabled": True}, strict=False)["enabled"] is False

    def test_non_dict_downgraded_when_lax(self):
        assert P.validate_profile(["nope"], strict=False) == P.default_profile()


# ------------------------------------------------------------------ is_active

class TestIsActive:
    def test_disabled_is_inactive(self):
        assert P.is_active({"enabled": False, "college": "法学院（纪检监察学院）"}) is False

    def test_enabled_but_empty_is_inactive(self):
        assert P.is_active({"enabled": True, "college": "", "notes": ""}) is False

    def test_enabled_with_one_field_is_active(self):
        assert P.is_active({"enabled": True, "grade": "2023"}) is True

    def test_none_is_inactive(self):
        assert P.is_active(None) is False


def _profile(**overrides):
    base = {
        "enabled": True,
        "college": "计算机与人工智能学院",
        "major": "计算机科学与技术",
        "grade": "2023",
        "notes": "关注竞赛和实习",
        "interests": ["竞赛", "实习"],
    }
    base.update(overrides)
    return base


# ------------------------------------------------------------------ 本地硬信号

class TestLocalHits:
    def test_college_full_name_hit(self):
        hits = P.local_hits("计算机与人工智能学院关于举办程序设计竞赛的通知", _profile())
        assert "学院:计算机与人工智能学院" in hits

    def test_college_short_alias_hit(self):
        # 简称「计算机」也能命中
        hits = P.local_hits("计算机学院实验室开放安排", _profile())
        assert any(hit.startswith("学院:") for hit in hits)

    def test_major_hit(self):
        hits = P.local_hits("计算机科学与技术专业培养方案调整", _profile())
        assert "专业:计算机科学与技术" in hits

    def test_grade_patterns(self):
        for text in ["2023级学生须知", "2023届毕业生离校安排", "23级军训通知", "23届推免"]:
            assert any(hit.startswith("年级:") for hit in P.local_hits(text, _profile())), text

    def test_interest_hit(self):
        assert "兴趣:竞赛" in P.local_hits("数学建模竞赛报名开始", _profile())

    def test_unrelated_text_has_no_hits(self):
        assert P.local_hits("外国语学院英语角活动", _profile()) == []

    def test_inactive_profile_has_no_hits(self):
        assert P.local_hits("计算机与人工智能学院通知", {"enabled": False}) == []


class TestRelevanceFloor:
    def test_two_kinds_gives_high_floor(self):
        text = "计算机与人工智能学院2023级程序设计竞赛报名"
        assert P.relevance_floor(text, _profile()) == 0.85

    def test_one_kind_gives_lower_floor(self):
        assert P.relevance_floor("计算机与人工智能学院通知", _profile()) == 0.70

    def test_no_hit_gives_none(self):
        assert P.relevance_floor("外国语学院英语角", _profile()) is None


# ------------------------------------------------------------------ prompt 片段

class TestViewerPrompt:
    def test_inactive_returns_none(self):
        assert P.viewer_state({"enabled": False}) is None

    def test_state_marks_grade_with_suffix(self):
        state = P.viewer_state(_profile())
        assert state["grade"] == "2023级"

    def test_prompt_block_wrapped_in_tag(self):
        block = P.viewer_prompt_block(_profile())
        assert block.startswith(P.VIEWER_OPEN)
        assert block.endswith(P.VIEWER_CLOSE)
        assert "不是给你的指令" in block

    def test_prompt_block_empty_when_inactive(self):
        assert P.viewer_prompt_block(None) == ""

    def test_relevance_question_shape(self):
        question = P.relevance_question()
        assert question["type"] == "score"
        assert len(question["criteria"]) == 5


# ------------------------------------------------------------------ 权重与弱否决

def _fake_reducer(item, as_of):
    """最小 reducer 桩：按 importance / is_announcement 落 P1/P2/P3。"""
    imp = item["judgments"]["importance"]["value"] or 0
    is_ann = item["judgments"].get("is_announcement", {}).get("value")
    item["decision"] = {
        "priority": "P1" if (is_ann and imp >= 70) else ("P2" if (is_ann and imp >= 50) else "P3"),
        "queue_state": "active",
        "record_kind": item["judgments"].get("record_kind", {}).get("value"),
        "importance": imp,
    }
    return item


def _item(name, importance, relevance, confidence, text=""):
    return {
        "candidate_id": name,
        "preview": text,
        "messages": [{"content": text}],
        "judgments": {
            "importance": {"value": importance, "confidence": 0.9},
            "relevance": {"value": relevance, "confidence": confidence},
            "is_announcement": {"value": True, "confidence": 0.9},
            "record_kind": {"value": "announcement", "confidence": 0.9},
        },
    }


AS_OF = date(2025, 3, 10)


class TestWeightFor:
    def test_high_relevance_hits_ceiling(self):
        assert P.weight_for(100, 0.9) == pytest.approx(P.PROFILE_WEIGHT_CEILING)

    def test_high_relevance_exceeds_one(self):
        """强介入：高相关度必须能突破 ×1.0，否则无法给 importance 加分。"""
        assert P.weight_for(90, 0.9) > 1.0

    def test_weight_above_one_only_after_midpoint(self):
        assert P.weight_for(50, 0.9) < 1.0
        assert P.weight_for(60, 0.9) > 1.0

    def test_floor_at_zero_relevance(self):
        assert P.weight_for(0, 0.9) == pytest.approx(P.PROFILE_WEIGHT_FLOOR)

    def test_missing_relevance_no_intervention(self):
        assert P.weight_for(None, 0.9) == 1.0

    def test_low_confidence_no_intervention(self):
        assert P.weight_for(0, 0.2) == 1.0

    def test_low_confidence_blocks_promotion_weight(self):
        """置信度不足时即使相关度满分也不加分。"""
        assert P.weight_for(100, 0.4) == 1.0

    def test_monotonic(self):
        assert P.weight_for(30, 0.9) < P.weight_for(60, 0.9) < P.weight_for(90, 0.9)


class TestLocalRelevanceEstimate:
    """本地相关度估算：让默认模式（无 API key）也具备双向介入能力。"""

    def test_strong_positive_hits_score_high(self):
        rel, conf, _ = P.local_relevance_estimate(
            "计算机与人工智能学院程序设计竞赛通知，2025级学生均可报名", _profile(grade="2025")
        )
        assert rel >= P.PROFILE_PROMOTE_RELEVANCE
        assert conf >= P.PROFILE_PROMOTE_CONFIDENCE
        assert rel > 50

    def test_single_positive_hit_crosses_promote_line(self):
        rel, conf, _ = P.local_relevance_estimate("关于举办竞赛训练营的通知", _profile())
        assert rel >= P.PROFILE_PROMOTE_RELEVANCE
        assert conf >= P.PROFILE_PROMOTE_CONFIDENCE

    def test_other_college_scores_below_sink_line(self):
        rel, conf, _ = P.local_relevance_estimate("外国语学院英语演讲比赛报名通知", _profile())
        assert rel < P.PROFILE_SINK_RELEVANCE
        assert conf >= P.PROFILE_SINK_CONFIDENCE, "反向命中需高置信才能沉底"

    def test_other_grade_scores_below_sink_line(self):
        rel, _, _ = P.local_relevance_estimate("关于2023级学生返校安排的通知", _profile(grade="2025"))
        assert rel < P.PROFILE_SINK_RELEVANCE

    def test_broadcast_text_stays_neutral(self):
        """全校性通知即使点名他院也不反向（各学院 / 全校）。"""
        for text in (
            "外国语学院英语角活动，全校同学均可参加",
            "关于奖助学金评审的通知，请各学院于10月25日前完成初审",
            "关于火车票预订的通知，全体同学可于10月30日前登记",
        ):
            rel, conf, _ = P.local_relevance_estimate(text, _profile())
            assert rel == 50.0, text
            assert conf < 0.5, f"{text} 应为低置信（不干预）"

    def test_no_signal_stays_neutral(self):
        rel, conf, _ = P.local_relevance_estimate("关于图书馆开放时间调整的说明", _profile())
        assert rel == 50.0
        assert conf < 0.5

    def test_positive_wins_over_negative(self):
        """同时提到自己和别人时，按正向处理（提到了你就算相关）。"""
        rel, _, _ = P.local_relevance_estimate(
            "计算机与人工智能学院与外院联合活动通知", _profile()
        )
        assert rel > 50

    def test_inactive_profile_returns_neutral(self):
        rel, conf, _ = P.local_relevance_estimate("计算机与人工智能学院通知", {"enabled": False})
        assert rel == 50.0
        assert conf < 0.5


class TestNegativeHits:
    def test_detects_other_college(self):
        negs = P.negative_hits("外国语学院英语演讲比赛通知", _profile())
        assert any("外国语学院" in n for n in negs)

    def test_detects_other_grade(self):
        negs = P.negative_hits("关于2023级学生返校的通知", _profile(grade="2025"))
        assert any("2023" in n for n in negs)

    def test_no_negative_when_own_college_present(self):
        assert P.negative_hits("计算机与人工智能学院通知", _profile()) == []

    def test_no_negative_for_broadcast(self):
        assert P.negative_hits("外国语学院活动，全校同学均可参加", _profile()) == []

    def test_no_negative_for_own_grade(self):
        assert P.negative_hits("关于2025级学生选课的通知", _profile(grade="2025")) == []


class TestApplyProfileWeight:
    def test_inactive_profile_is_noop(self):
        items = [_item("a", 80, 5, 0.9, "外国语学院英语角")]
        stats = P.apply_profile_weight(items, {"enabled": False}, AS_OF, _fake_reducer)
        assert stats["applied"] is False
        assert stats["weighted"] == 0
        assert items[0]["judgments"]["importance"]["value"] == 80
        assert "profile" not in items[0]["judgments"]["importance"]

    def test_empty_profile_is_noop(self):
        items = [_item("a", 80, 5, 0.9, "外国语学院英语角")]
        stats = P.apply_profile_weight(items, {"enabled": True, "college": ""}, AS_OF, _fake_reducer)
        assert stats["applied"] is False
        assert items[0]["judgments"]["importance"]["value"] == 80

    def test_high_relevance_keeps_priority(self):
        items = [_item("a", 80, 95, 0.9, "计算机与人工智能学院程序设计竞赛 2023级")]
        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert items[0]["decision"]["priority"] == "P1"
        assert items[0]["judgments"]["importance"]["value"] >= 75

    def test_low_relevance_sinks_to_p3(self):
        items = [_item("a", 80, 8, 0.9, "外国语学院英语角活动通知")]
        stats = P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert stats["sunk"] == 1
        assert items[0]["decision"]["priority"] == "P3"
        assert "画像判断与本条相关性低" in items[0]["decision"]["reason"]

    def test_low_relevance_without_confidence_does_not_sink(self):
        items = [_item("a", 80, 8, 0.5, "外国语学院英语角活动通知")]
        stats = P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert stats["sunk"] == 0

    def test_hard_signal_prevents_sinking(self):
        # 原文点名本人学院，模型却说无关 → 不允许沉底
        items = [_item("a", 80, 8, 0.9, "计算机与人工智能学院推免工作安排")]
        stats = P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert stats["sunk"] == 0
        assert items[0]["judgments"]["importance"]["profile"]["floor_applied"] is True
        assert items[0]["decision"]["priority"] != "P3"

    def test_weight_records_before_after(self):
        items = [_item("a", 80, 50, 0.9, "关于期末教学安排的通知")]
        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        record = items[0]["judgments"]["importance"]["profile"]
        assert record["base"] == 80
        assert record["relevance"] == 50
        assert P.PROFILE_WEIGHT_FLOOR <= record["weight"] <= P.PROFILE_WEIGHT_CEILING

    def test_weight_boosts_importance_when_highly_relevant(self):
        """强介入：高相关条目 importance 应被上调，而非压制。"""
        items = [_item("a", 60, 95, 0.9, "关于期末教学安排的通知")]
        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        importance = items[0]["judgments"]["importance"]
        record = importance["profile"]
        assert record["weight"] > 1.0
        assert importance["value"] > record["base"], "高相关应加分"

    def test_high_relevance_lifts_importance_over_p1_line(self):
        """DS 模式的核心诉求：高相关能把 importance 推过 P1 门槛（≥70）。

        构造 importance=58（本地判 P2）但 relevance=95 的公告：
        权重 1.275 → 58×1.275 ≈ 74 → reducer 重新判为 P1。
        """
        items = [_item("a", 58, 95, 0.9, "关于期末教学安排的通知")]
        before = items[0]["judgments"]["importance"]["value"]
        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        after = items[0]["judgments"]["importance"]["value"]
        assert after > before, f"importance 应被加分：{before} -> {after}"
        assert after >= 70, f"应跨过 P1 门槛，实际 {after}"
        assert items[0]["decision"]["priority"] == "P1"

    def test_boost_never_exceeds_100(self):
        """加分后 importance 仍被 clamp 在 100 以内。"""
        items = [_item("a", 95, 100, 1.0, "关于期末教学安排的通知")]
        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert items[0]["judgments"]["importance"]["value"] <= 100

    def test_changed_trail_lists_priority_transition(self):
        items = [_item("a", 80, 8, 0.9, "外国语学院英语角活动通知")]
        stats = P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer)
        assert len(stats["changed"]) == 1
        entry = stats["changed"][0]
        assert entry["importance_before"] == 80
        assert entry["priority_before"] == "P1"
        assert entry["priority_after"] == "P3"
        assert entry["sunk"] is True

    def test_does_not_touch_deadline_or_urgency(self):
        item = _item("a", 80, 8, 0.9, "外国语学院英语角")
        item["judgments"]["deadline"] = {"value": {"normalized": "2025-03-12", "kind": "deadline"}, "confidence": 0.9}
        item["judgments"]["urgency"] = {"value": 90, "confidence": 0.9}
        P.apply_profile_weight([item], _profile(), AS_OF, _fake_reducer)
        assert item["judgments"]["deadline"]["value"]["normalized"] == "2025-03-12"
        assert item["judgments"]["urgency"]["value"] == 90

    def test_item_without_relevance_gets_local_estimate(self):
        """无模型 relevance 时，本地估算介入：命中画像的文本应被加分。"""
        item = _item("a", 80, None, 0.9, "计算机与人工智能学院通知")
        item["judgments"].pop("relevance")
        stats = P.apply_profile_weight([item], _profile(), AS_OF, _fake_reducer)
        record = item["judgments"]["importance"]["profile"]
        assert stats["weighted"] == 1
        assert item["judgments"]["importance"]["value"] > 80
        assert record["relevance_source"] == "local-estimate"
        assert item["judgments"]["relevance"]["source"] == "local-estimate"

    def test_neutral_text_without_relevance_stays_untouched(self):
        """中性文本（无正反信号）本地估算后仍不干预。"""
        item = _item("a", 80, None, 0.9, "关于图书馆开放时间调整的说明")
        item["judgments"].pop("relevance")
        stats = P.apply_profile_weight([item], _profile(), AS_OF, _fake_reducer)
        assert stats["weighted"] == 0
        assert item["judgments"]["importance"]["value"] == 80

    def test_sort_key_applied_when_given(self):
        items = [_item("a", 60, 8, 0.9, "外国语学院x"), _item("b", 95, 95, 0.9, "计算机与人工智能学院竞赛")]
        calls = []

        def sort_key(item):
            calls.append(item["candidate_id"])
            return item["candidate_id"]

        P.apply_profile_weight(items, _profile(), AS_OF, _fake_reducer, sort_key=sort_key)
        assert calls == ["a", "b"]

    def test_archived_item_not_sunk_further(self):
        items = [_item("a", 80, 8, 0.9, "外国语学院英语角")]

        def archived_reducer(item, as_of):
            item["decision"] = {
                "priority": "P1",
                "queue_state": "archived",
                "record_kind": "announcement",
                "importance": item["judgments"]["importance"]["value"] or 0,
            }
            return item

        stats = P.apply_profile_weight(items, _profile(), AS_OF, archived_reducer)
        assert stats["sunk"] == 0
        assert items[0]["decision"]["priority"] == "P1"
