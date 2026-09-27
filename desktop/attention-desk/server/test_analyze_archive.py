import io
import json
import shutil
import unittest
import zipfile
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory

from fastapi.testclient import TestClient

from server import main
from server.test_chat_archive import CHAT_TEXT


class AnalyzeArchiveEndpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self._original_root = main.ARCHIVE_ROOT
        main.ARCHIVE_ROOT = self.root
        self.client = TestClient(main.app)

    def tearDown(self):
        main.ARCHIVE_ROOT = self._original_root
        self.tmp.cleanup()

    def build_zip(self, attachments=True):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("聊天记录.txt", CHAT_TEXT.encode("utf-8"))
            if attachments:
                archive.writestr("聊天记录内的图片、视频和文件/挑战杯附件.zip", b"PK\x03\x04fake")
                archive.writestr("聊天记录内的图片、视频和文件/微信图片_202609241205_1.jpg", b"\xff\xd8\xff\xe0jpg")
        return buffer.getvalue()

    def post(self, payload, name="聊天记录_20260925.zip", **form):
        return self.client.post(
            "/api/analyze-archive",
            files={"file": (name, payload, "application/zip")},
            data={"as_of": "2026-09-25", **form},
        )

    def test_parses_archive_and_returns_queue(self):
        response = self.post(self.build_zip())
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["source"]["source_kind"], "archive-upload")
        self.assertEqual(body["source"]["source_strategy"], "chat-archive-zip")
        self.assertEqual(body["source"]["conversation"], "测试班级群")
        self.assertEqual(body["source"]["message_count"], 5)
        self.assertEqual(body["summary"]["candidate_count"], len(body["items"]))
        self.assertTrue(body["items"], "应至少分拣出一个候选事项")
        priority = {item["decision"]["priority"] for item in body["items"]}
        self.assertTrue(priority & {"P0", "P1", "P2", "P3"})

    def test_attachments_are_persisted_and_summarised(self):
        response = self.post(self.build_zip())
        body = response.json()
        export = body["source_export"]
        self.assertEqual(export["file_count"], 2)
        self.assertEqual(export["resolved_file_count"], 2)
        for item in export["files"]:
            self.assertTrue(item["local_available"])
            # 不向客户端暴露绝对缓存路径
            self.assertNotIn("local_path", item)
        saved = Path(export["bundle_dir"])
        self.assertTrue((saved / "messages.json").exists())
        self.assertTrue((saved / "files.json").exists())
        self.assertEqual(len(list((saved / "attachments").iterdir())), 2)

    def test_archive_without_attachments_still_works(self):
        response = self.post(self.build_zip(attachments=False))
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["source_export"]["file_count"], 0)

    def test_date_range_filter_is_applied(self):
        response = self.post(self.build_zip(), from_date="2026-09-24", to_date="2026-09-24")
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        # 归档含两天，筛选后只剩 9/24 的两条
        self.assertEqual(body["source"]["message_count"], 2)
        self.assertEqual(body["source"]["archive_message_count"], 5)
        self.assertEqual(body["source"]["message_date_filter"], {"from": "2026-09-24", "to": "2026-09-24"})

    def test_non_zip_upload_is_rejected(self):
        response = self.post(b"this is definitely not a zip", name="notes.txt")
        self.assertEqual(response.status_code, 400)
        self.assertIn("zip", response.json()["detail"])

    def test_zip_without_chat_text_is_rejected(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("readme.md", b"nothing here")
        response = self.post(buffer.getvalue())
        self.assertEqual(response.status_code, 400)
        self.assertIn("聊天记录", response.json()["detail"])

    def test_empty_upload_is_rejected(self):
        response = self.post(b"")
        self.assertEqual(response.status_code, 400)

    def test_sample_and_wechat_routes_are_untouched(self):
        """新链路是纯增量：既有端点必须仍在。"""
        paths = {route.path for route in main.app.routes if getattr(route, "path", "")}
        for path in ("/api/sample", "/api/analyze", "/api/wechat/analyze", "/api/wechat/status", "/api/official/overview"):
            self.assertIn(path, paths)
        health = self.client.get("/api/health")
        self.assertEqual(health.status_code, 200)


if __name__ == "__main__":
    unittest.main()
