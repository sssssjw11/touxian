"""DeepSeek 链路的端到端测试：用假的上游 server 验证真实 HTTP 流程。

不依赖真实 API key，也不打真实网络：起一个本地 aiohttp/ASGI 假响应，
验证「候选 → prompt → /chat/completions → 解析 → 门控 → 最终优先级」全通。
"""

import io
import json
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from server import main
from server.test_chat_archive import CHAT_TEXT


def make_fake_deepseek(answers_by_content=None, fail_times=0):
    """构造一个假的 DeepSeek 上游，记录收到的请求体。"""
    seen = {"requests": [], "remaining_failures": fail_times}
    upstream = FastAPI()

    @upstream.post("/chat/completions")
    async def chat(request: Request):
        body = await request.json()
        seen["requests"].append(body)
        if seen["remaining_failures"] > 0:
            seen["remaining_failures"] -= 1
            # 模拟官方已知的「JSON Output 偶发空 content」
            return JSONResponse({"choices": [{"message": {"content": ""}}]})
        answers = {
            "record_kind": {"value": "announcement", "confidence": 0.9},
            "category": {"value": "activity", "confidence": 0.88},
            "audience": {"value": "all", "confidence": 0.8},
            "is_announcement": {"probability": 0.93, "confidence": 0.9},
            "action_required": {"probability": 0.86, "confidence": 0.85},
            "importance": {"score": 75, "confidence": 0.8},
            "risk": {"score": 8, "confidence": 0.8},
            "deadline": {"value": "2099-01-01", "confidence": 0.99},  # 必须被忽略
            "urgency": {"score": 99, "confidence": 0.99},             # 必须被忽略
        }
        if answers_by_content:
            answers.update(answers_by_content)
        return JSONResponse({
            "choices": [{"message": {"content": json.dumps(answers, ensure_ascii=False)}}]
        })

    return upstream, seen


class DeepSeekEndToEndTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self._original_root = main.ARCHIVE_ROOT
        main.ARCHIVE_ROOT = Path(self.tmp.name)
        self.client = TestClient(main.app)

    def tearDown(self):
        main.ARCHIVE_ROOT = self._original_root
        self.tmp.cleanup()

    def build_zip(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("聊天记录.txt", CHAT_TEXT.encode("utf-8"))
        return buffer.getvalue()

    def call(self, upstream, **form):
        """把 main 的 httpx 客户端指向假上游（TestClient 内部用 ASGITransport）。"""
        transport = httpx.ASGITransport(app=upstream)
        original = httpx.AsyncClient

        def patched(*args, **kwargs):
            kwargs["transport"] = transport
            return original(*args, **kwargs)

        httpx.AsyncClient = patched
        try:
            return self.client.post(
                "/api/analyze-archive",
                files={"file": ("聊天记录.zip", self.build_zip(), "application/zip")},
                data={"as_of": "2026-09-25", "provider": "deepseek",
                      "api_key": "sk-fake-for-test", "endpoint": "https://api.deepseek.com/chat/completions",
                      **form},
            )
        finally:
            httpx.AsyncClient = original

    def test_deepseek_provider_runs_and_annotates_result(self):
        upstream, seen = make_fake_deepseek()
        response = self.call(upstream)
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["provider"]["kind"], "deepseek")
        self.assertIn("DeepSeek", body["provider"]["label"])
        self.assertGreater(body["provider"]["calls"], 0)
        self.assertTrue(seen["requests"], "假上游应当收到请求")

    def test_request_uses_openai_shape(self):
        upstream, seen = make_fake_deepseek()
        self.call(upstream)
        sent = seen["requests"][0]
        self.assertEqual(sent["model"], "deepseek-flash")
        self.assertIn("messages", sent)
        self.assertEqual(sent["response_format"], {"type": "json_object"})
        self.assertFalse(sent["stream"])
        self.assertIn("max_tokens", sent)
        roles = [m["role"] for m in sent["messages"]]
        self.assertEqual(roles, ["system", "user"])

    def test_answers_flow_through_gate_and_keep_local_deadline(self):
        """模型给了 2099 的假 DDL，本地解析的截止日必须原样保留。"""
        upstream, _ = make_fake_deepseek()
        response = self.call(upstream)
        body = response.json()
        for item in body["items"]:
            deadline = item["judgments"].get("deadline", {}).get("value")
            if isinstance(deadline, dict):
                self.assertNotEqual(deadline.get("normalized"), "2099-01-01T00:00")
            trace = item.get("provider_trace") or {}
            if trace.get("kind") == "jev":
                self.assertIn("deadline", trace.get("ignored_application_owned", []))
                self.assertIn("urgency", trace.get("ignored_application_owned", []))

    def test_empty_content_is_retried(self):
        """官方已知的空 content 应触发重试，而不是直接失败。"""
        upstream, seen = make_fake_deepseek(fail_times=1)
        response = self.call(upstream)
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertGreater(body["provider"]["calls"], 0, "重试后应当成功")
        self.assertGreater(len(seen["requests"]), 1, "应当发出多于一次请求")

    def test_missing_api_key_is_rejected(self):
        upstream, _ = make_fake_deepseek()
        transport = httpx.ASGITransport(app=upstream)
        original = httpx.AsyncClient
        httpx.AsyncClient = lambda *a, **k: original(*a, **{**k, "transport": transport})
        try:
            response = self.client.post(
                "/api/analyze-archive",
                files={"file": ("聊天记录.zip", self.build_zip(), "application/zip")},
                data={"as_of": "2026-09-25", "provider": "deepseek", "api_key": ""},
            )
        finally:
            httpx.AsyncClient = original
        self.assertEqual(response.status_code, 400)
        self.assertIn("API key", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()
