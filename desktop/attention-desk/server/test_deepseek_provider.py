"""DeepSeek 适配层测试。

DeepSeek 走 OpenAI 兼容的 /chat/completions，不吃 Jev 的 state+questions 协议。
这里只测适配器本身（prompt 渲染 + 响应解析 + provider 归一），
不发起真实网络请求；门控行为由 test_analyze_archive 里的既有用例覆盖。
"""

import json
import unittest

from server import main


class BuildMessagesTests(unittest.TestCase):
    def payload(self):
        return {
            "protocol": "jev-typed-judgments-v1",
            "model": "deepseek-flash",
            "state": {
                "source": {"conversation": "测试班级群", "as_of": "2026-09-25"},
                "candidate": {"candidate_id": "ann-0001", "text": "挑战杯报名通知"},
                "messages": [
                    {"id": "m-3", "sender": "逐梦者", "content": "10月10日16:00前填写在线表格", "type": "text"},
                ],
            },
            "questions": {
                "is_announcement": {"type": "noul"},
                "record_kind": {"type": "choice", "options": ["announcement", "resource", "noise"]},
                "category": {"type": "choice", "options": ["activity", "course"]},
                "importance": {"type": "score", "criteria": ["0-20: noise", "81-100: critical"]},
            },
        }

    def test_renders_system_and_user_messages(self):
        messages = main.build_deepseek_messages(self.payload())
        self.assertEqual([m["role"] for m in messages], ["system", "user"])
        self.assertTrue(all(isinstance(m["content"], str) and m["content"] for m in messages))

    def test_prompt_mentions_json_and_gives_example(self):
        """JSON Output 的两条硬要求：prompt 里要有 json 字样 + 输出样例。"""
        messages = main.build_deepseek_messages(self.payload())
        combined = "\n".join(m["content"] for m in messages)
        self.assertIn("json", combined.lower())
        self.assertIn('"record_kind"', combined)

    def test_prompt_includes_evidence_and_questions(self):
        user = main.build_deepseek_messages(self.payload())[1]["content"]
        self.assertIn("测试班级群", user)
        self.assertIn("逐梦者", user)
        self.assertIn("10月10日16:00前填写在线表格", user)
        self.assertIn("record_kind", user)
        self.assertIn("importance", user)
        # 选项要原样透传给模型，否则它无从选择
        self.assertIn("activity", user)


class ParseDeepSeekContentTests(unittest.TestCase):
    def test_plain_json(self):
        content = '{"category": {"value": "activity", "confidence": 0.9}}'
        answers = main.parse_deepseek_content(content)
        self.assertEqual(answers["category"]["value"], "activity")

    def test_fenced_json(self):
        content = '```json\n{"category": {"value": "admin", "confidence": 0.8}}\n```'
        answers = main.parse_deepseek_content(content)
        self.assertEqual(answers["category"]["value"], "admin")

    def test_json_with_surrounding_prose(self):
        content = '好的，判断如下：\n{"importance": {"score": 70}}\n以上。'
        answers = main.parse_deepseek_content(content)
        self.assertEqual(answers["importance"]["score"], 70)

    def test_unwraps_answers_envelope(self):
        content = '{"answers": {"category": {"value": "exam", "confidence": 0.9}}}'
        answers = main.parse_deepseek_content(content)
        self.assertIn("category", answers)
        self.assertNotIn("answers", answers)

    def test_empty_content_raises(self):
        """官方已知：JSON Output 有概率返回空 content。"""
        for value in ("", "   ", None):
            with self.assertRaises(Exception):
                main.parse_deepseek_content(value)

    def test_non_json_raises(self):
        with self.assertRaises(Exception):
            main.parse_deepseek_content("我无法判断")

    def test_accepts_already_parsed_dict(self):
        answers = main.parse_deepseek_content({"category": {"value": "admin"}})
        self.assertEqual(answers["category"]["value"], "admin")


class ResolveProviderTests(unittest.TestCase):
    def test_local_is_not_remote(self):
        kind, endpoint, model = main.resolve_remote_provider("local", "", "")
        self.assertEqual(kind, "jev")

    def test_deepseek_fills_defaults(self):
        kind, endpoint, model = main.resolve_remote_provider("deepseek", "", "")
        self.assertEqual(kind, "deepseek")
        self.assertEqual(endpoint, main.DEEPSEEK_DEFAULT_ENDPOINT)
        self.assertEqual(model, main.DEEPSEEK_DEFAULT_MODEL)

    def test_deepseek_accepts_base_url(self):
        kind, endpoint, _ = main.resolve_remote_provider("deepseek", "https://api.deepseek.com", "")
        self.assertEqual(endpoint, "https://api.deepseek.com/chat/completions")

    def test_deepseek_keeps_full_endpoint(self):
        given = "https://api.deepseek.com/chat/completions"
        kind, endpoint, _ = main.resolve_remote_provider("deepseek", given, "")
        self.assertEqual(endpoint, given)

    def test_deepseek_keeps_custom_model(self):
        _, _, model = main.resolve_remote_provider("deepseek", "", "deepseek-v4-pro")
        self.assertEqual(model, "deepseek-v4-pro")

    def test_deepseek_overrides_jev_defaults_from_form(self):
        """表单默认值是 Jev 的，选 DeepSeek 时不能沿用到请求里。"""
        kind, endpoint, model = main.resolve_remote_provider(
            "deepseek", "https://api.typesafe.ai/v1/systemone", "jev-system-one"
        )
        self.assertEqual(kind, "deepseek")
        self.assertEqual(endpoint, main.DEEPSEEK_DEFAULT_ENDPOINT)
        self.assertEqual(model, main.DEEPSEEK_DEFAULT_MODEL)

    def test_jev_provider_untouched(self):
        kind, endpoint, model = main.resolve_remote_provider(
            "custom", "https://api.typesafe.ai/v1/systemone", "jev-system-one"
        )
        self.assertEqual(kind, "jev")
        self.assertEqual(endpoint, "https://api.typesafe.ai/v1/systemone")
        self.assertEqual(model, "jev-system-one")

    def test_jev_provider_gets_default_model_when_blank(self):
        _, _, model = main.resolve_remote_provider("custom", "https://x.example/api", "")
        self.assertEqual(model, "jev-system-one")


class DeepSeekAnswersFlowThroughGateTests(unittest.TestCase):
    """DeepSeek 的回答必须走与 Jev 相同的门控：DDL 与 urgency 不可被覆盖。"""

    def base_item(self):
        return {
            "candidate_id": "ann-0001",
            "judgments": {
                "is_announcement": {"value": True, "confidence": 0.9, "reason": "本地"},
                "record_kind": {"value": "announcement", "confidence": 0.9},
                "category": {"value": "activity", "confidence": 0.6},
                "audience": {"value": "all", "confidence": 0.6},
                "deadline": {"value": {"normalized": "2026-10-10T16:00"}, "confidence": 0.78},
                "action_required": {"value": True, "confidence": 0.7},
                "importance": {"value": 50, "confidence": 0.5},
                "urgency": {"value": 40, "confidence": 0.5},
                "risk": {"value": 10, "confidence": 0.5},
            },
        }

    def test_deadline_and_urgency_cannot_be_overwritten(self):
        item = self.base_item()
        payload = {"answers": {
            "deadline": {"value": "2099-01-01", "confidence": 0.99},
            "urgency": {"score": 100, "confidence": 0.99},
        }}
        main.apply_jev_answers(item, payload)
        trace = item["provider_trace"]
        self.assertEqual(sorted(trace["ignored_application_owned"]), ["deadline", "urgency"])
        # 本地 DDL 原样保留
        self.assertEqual(item["judgments"]["deadline"]["value"]["normalized"], "2026-10-10T16:00")

    def test_high_confidence_choice_is_accepted(self):
        item = self.base_item()
        payload = {"answers": {"category": {"value": "exam", "confidence": 0.9}}}
        main.apply_jev_answers(item, payload)
        self.assertEqual(item["judgments"]["category"]["value"], "exam")
        self.assertIn("category", item["provider_trace"]["accepted"])

    def test_low_confidence_is_rejected(self):
        item = self.base_item()
        payload = {"answers": {"category": {"value": "exam", "confidence": 0.3}}}
        main.apply_jev_answers(item, payload)
        self.assertEqual(item["judgments"]["category"]["value"], "activity")
        self.assertTrue(any("low_confidence" in r for r in item["provider_trace"]["rejected"]))

    def test_invalid_choice_is_rejected(self):
        item = self.base_item()
        payload = {"answers": {"category": {"value": "not_a_category", "confidence": 0.95}}}
        main.apply_jev_answers(item, payload)
        self.assertEqual(item["judgments"]["category"]["value"], "activity")
        self.assertTrue(any("invalid_choice" in r for r in item["provider_trace"]["rejected"]))

    def test_model_cannot_promote_non_announcement(self):
        """本地判为非公告时，模型不能翻案成 announcement。"""
        item = self.base_item()
        item["judgments"]["record_kind"] = {"value": "resource", "confidence": 0.9}
        payload = {"answers": {"record_kind": {"value": "announcement", "confidence": 0.99}}}
        main.apply_jev_answers(item, payload)
        self.assertEqual(item["judgments"]["record_kind"]["value"], "resource")
        self.assertTrue(any("local_gate" in r for r in item["provider_trace"]["rejected"]))


if __name__ == "__main__":
    unittest.main()
