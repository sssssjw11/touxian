"""邮件端点的集成测试：/api/mail/status、/api/mail/prepare、/api/mail/send。

用假 CLI 替换真实发送，测试走完整 HTTP 链路但不真正发信。
"""

from __future__ import annotations

import unittest
from unittest import mock

from fastapi.testclient import TestClient

from server import main
from server import mail_digest as MD


def sample_result() -> dict:
    return {
        "source": {"conversation": "2023级计科1班", "as_of": "2026-09-25", "archive_start": "2026-09-20", "archive_end": "2026-09-25"},
        "summary": {"priority_counts": {"P0": 1, "P1": 1}},
        "items": [
            {
                "item_key": "k1",
                "preview": "选课确认",
                "decision": {"priority": "P0", "deadline_status": "overdue", "queue_state": "active"},
                "judgments": {"deadline": {"value": {"normalized": "2026-09-24T13:00:00", "status": "overdue"}}, "category": {"value": "course"}},
                "evidence": [{"content": "选课系统将于9月24日13:00关闭。"}],
            },
            {
                "item_key": "k2",
                "preview": "重修报名",
                "decision": {"priority": "P1", "deadline_status": "near", "queue_state": "active"},
                "judgments": {"deadline": {"value": {"normalized": "2026-09-27T23:00:00", "status": "near"}}, "category": {"value": "admin"}},
                "evidence": [{"content": "请在9月27日23:00前完成重修报名。"}],
            },
        ],
    }


class MailEndpointTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    # ------------------------------------------------------------ status

    def test_status_reports_cli_readiness(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}):
            response = self.client.get("/api/mail/status")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["ready"])
        self.assertEqual(body["sender"], "sender@example.com")

    def test_status_when_not_installed(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": False, "reason": "未安装 agently-cli"}):
            response = self.client.get("/api/mail/status")
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()["ready"])

    # ------------------------------------------------------------ prepare

    def test_prepare_returns_token_and_preview(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}), \
             mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"confirmation_token": "ctk_x", "summary": {"action": "send"}}}) as fake_run:
            response = self.client.post("/api/mail/prepare", json={
                "result": sample_result(),
                "recipients": "a@x.com, b@y.com",
                "as_of": "2026-09-25",
            })
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["confirmation_token"], "ctk_x")
        self.assertEqual(body["recipients"], ["a@x.com", "b@y.com"])
        self.assertIn("重修报名", body["body"])
        self.assertIn("已逾期", body["body"])
        # 阶段一不得出现确认令牌
        argv = " ".join(fake_run.call_args[0][0])
        self.assertNotIn("--confirmation-token", argv)
        self.assertEqual(fake_run.call_args[0][0][:2], ["message", "+send"])

    def test_prepare_defaults_to_html(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}), \
             mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"confirmation_token": "ctk_x"}}):
            response = self.client.post("/api/mail/prepare", json={
                "result": sample_result(),
                "recipients": "a@x.com",
            })
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["body_format"], "html")
        self.assertTrue(body["body"].startswith("<!DOCTYPE html>"))
        self.assertIn("已逾期 · 请尽快确认", body["body"])

    def test_prepare_honours_text_format(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}), \
             mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"confirmation_token": "ctk_x"}}):
            response = self.client.post("/api/mail/prepare", json={
                "result": sample_result(),
                "recipients": "a@x.com",
                "body_format": "text",
            })
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["body_format"], "text")
        self.assertFalse(body["body"].startswith("<!DOCTYPE html>"))
        self.assertIn("进行中事项", body["body"])

    def test_send_passes_body_format(self):
        with mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"queued": True}}) as fake:
            response = self.client.post("/api/mail/send", json={
                "confirmation_token": "ctk_abc",
                "recipients": "a@x.com",
                "subject": "S",
                "body": "<p>B</p>",
                "body_format": "text",
            })
        self.assertEqual(response.status_code, 200)
        argv = fake.call_args[0][0]
        assert argv[argv.index("--body-file") + 1].endswith(".txt")

    def test_prepare_requires_result(self):
        response = self.client.post("/api/mail/prepare", json={"recipients": "a@x.com"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("分拣结果", response.json()["detail"])

    def test_prepare_rejects_bad_recipient(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}):
            response = self.client.post("/api/mail/prepare", json={"result": sample_result(), "recipients": "bad"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("格式不正确", response.json()["detail"])

    def test_prepare_surfaces_unready_mail(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": False, "reason": "邮箱授权已失效"}):
            response = self.client.post("/api/mail/prepare", json={"result": sample_result(), "recipients": "a@x.com"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("授权已失效", response.json()["detail"])

    def test_prepare_rejects_bad_as_of(self):
        response = self.client.post("/api/mail/prepare", json={
            "result": sample_result(), "recipients": "a@x.com", "as_of": "2026/09/25",
        })
        self.assertEqual(response.status_code, 400)
        self.assertIn("YYYY-MM-DD", response.json()["detail"])

    # ------------------------------------------------------------ send

    def test_send_requires_token(self):
        response = self.client.post("/api/mail/send", json={"recipients": "a@x.com", "subject": "S", "body": "B"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("确认令牌", response.json()["detail"])

    def test_send_uses_token(self):
        with mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"queued": True}}) as fake:
            response = self.client.post("/api/mail/send", json={
                "confirmation_token": "ctk_abc",
                "recipients": "a@x.com",
                "subject": "【待办提醒】测试",
                "body": "正文",
            })
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["sent"])
        self.assertTrue(response.json()["queued"])
        argv = " ".join(fake.call_args[0][0])
        self.assertIn("--confirmation-token", argv)
        self.assertIn("ctk_abc", argv)

    def test_send_reports_message_id_when_present(self):
        with mock.patch.object(MD, "run_cli", return_value={"ok": True, "data": {"queued": True, "message_id": "msg_9"}}):
            response = self.client.post("/api/mail/send", json={
                "confirmation_token": "ctk_abc", "recipients": "a@x.com", "subject": "S", "body": "B",
            })
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["message_id"], "msg_9")

    def test_send_rejects_bogus_token(self):
        response = self.client.post("/api/mail/send", json={
            "confirmation_token": "not-ctk", "recipients": "a@x.com", "subject": "S", "body": "B",
        })
        self.assertEqual(response.status_code, 400)
        self.assertIn("确认令牌无效", response.json()["detail"])

    # ------------------------------------------------------------ health

    def test_health_exposes_mail_fields(self):
        with mock.patch.object(MD, "mail_status", return_value={"ready": True, "sender": "sender@example.com"}):
            response = self.client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["mail_ready"])
        self.assertEqual(body["mail"]["sender"], "sender@example.com")
        self.assertIn("deepseek", body["providers"])


if __name__ == "__main__":
    unittest.main()
