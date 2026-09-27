import io
import json
import os
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from fastapi.testclient import TestClient

from server import main
from server.inbox import InboxStore, default_watch_dir
from server.test_chat_archive import CHAT_TEXT


def build_chat_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("聊天记录.txt", CHAT_TEXT.encode("utf-8"))
    return buffer.getvalue()


def backdate(path: Path, seconds: float = 60) -> None:
    """把 mtime 拨回过去，绕过收件箱的写入稳定窗口。"""
    stamp = time.time() - seconds
    os.utime(path, (stamp, stamp))


class InboxStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.watch_dir = self.root / "chat-history"
        self.watch_dir.mkdir()
        self.store = InboxStore(
            self.root / "inbox", watch_dir=self.watch_dir, poll_seconds=0
        )

    def tearDown(self):
        self.store.stop_watcher()
        self.tmp.cleanup()

    def test_inbox_dir_is_created_by_store(self):
        self.assertTrue((self.root / "inbox").is_dir())

    def test_scan_copies_new_zip_and_registers_manifest(self):
        source = self.watch_dir / "20260927-161216-a6ae8c-聊天记录.zip"
        source.write_bytes(build_chat_zip())
        backdate(source)

        added = self.store.scan()

        self.assertEqual(len(added), 1)
        record = added[0]
        self.assertEqual(record["status"], "new")
        self.assertEqual(record["size"], source.stat().st_size)
        # zip 已拷入收件箱，原文件保持原样
        self.assertTrue((self.root / "inbox" / source.name).is_file())
        self.assertTrue(source.exists())
        # manifest 落盘且内容一致
        manifest = json.loads(
            (self.root / "inbox" / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(manifest["items"][0]["id"], record["id"])

    def test_scan_is_idempotent_for_same_content(self):
        first = self.watch_dir / "a.zip"
        first.write_bytes(build_chat_zip())
        backdate(first)
        self.assertEqual(len(self.store.scan()), 1)
        # 同内容不同文件名：按内容哈希去重，不重复登记
        second = self.watch_dir / "b.zip"
        second.write_bytes(build_chat_zip())
        backdate(second)
        self.assertEqual(self.store.scan(), [])
        self.assertEqual(len(self.store.list_items()), 1)

    def test_scan_ignores_non_zip_entries(self):
        (self.watch_dir / "notes.txt").write_text("hello", encoding="utf-8")
        (self.watch_dir / "wechat-chat-history-123").mkdir()
        corrupt = self.watch_dir / "broken.zip"
        corrupt.write_bytes(b"not a zip at all")
        backdate(corrupt)

        self.assertEqual(self.store.scan(), [])
        self.assertEqual(self.store.list_items(), [])

    def test_scan_skips_files_still_being_written(self):
        source = self.watch_dir / "fresh.zip"
        source.write_bytes(build_chat_zip())  # mtime 就是现在，未过稳定窗口

        self.assertEqual(self.store.scan(), [])
        backdate(source)
        self.assertEqual(len(self.store.scan()), 1)

    def test_mark_imported_and_error(self):
        source = self.watch_dir / "chat.zip"
        source.write_bytes(build_chat_zip())
        backdate(source)
        self.store.scan()

        self.store.mark_imported(source.name)
        record = self.store.list_items()[0]
        self.assertEqual(record["status"], "imported")
        self.assertTrue(record["imported_at"])

        self.store.mark_imported(source.name, error="解析失败")
        record = self.store.list_items()[0]
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["error"], "解析失败")

    def test_read_zip_validates(self):
        with self.assertRaises(ValueError):
            self.store.read_zip("missing.zip")
        fake = self.root / "inbox" / "fake.zip"
        fake.write_bytes(b"nope")
        with self.assertRaises(ValueError):
            self.store.read_zip("fake.zip")

    def test_watcher_thread_autoscan(self):
        self.store.poll_seconds = 0.2
        self.store.start_watcher()
        source = self.watch_dir / "auto.zip"
        source.write_bytes(build_chat_zip())
        backdate(source)

        for _ in range(30):
            if self.store.list_items():
                break
            time.sleep(0.2)
        self.assertEqual(len(self.store.list_items()), 1)
        self.assertTrue(self.store.status()["watcher_running"])
        self.store.stop_watcher()
        self.assertFalse(self.store.status()["watcher_running"])


class WatchDirResolutionTests(unittest.TestCase):
    def test_env_override_wins(self):
        with mock.patch.dict(
            os.environ, {"ATTENTION_INBOX_WATCH_DIR": "~/custom-chat-dir"}
        ):
            self.assertEqual(
                default_watch_dir(), Path("~/custom-chat-dir").expanduser()
            )
            store = InboxStore(Path(tempfile.mkdtemp()) / "inbox")
            self.assertEqual(store.watch_dir, Path("~/custom-chat-dir").expanduser())

    def test_default_is_workbuddy_chat_history(self):
        # 只移除目标变量：清空整个 environ 会让 Windows 上的 Path.home() 失效
        with mock.patch.dict(os.environ):
            os.environ.pop("ATTENTION_INBOX_WATCH_DIR", None)
            self.assertEqual(
                default_watch_dir(),
                Path.home() / ".workbuddy" / "app" / "tmp" / "chat-history",
            )
        # 空字符串等同未设置
        with mock.patch.dict(os.environ, {"ATTENTION_INBOX_WATCH_DIR": "   "}):
            self.assertEqual(
                default_watch_dir(),
                Path.home() / ".workbuddy" / "app" / "tmp" / "chat-history",
            )

    def test_missing_watch_dir_degrades_gracefully(self):
        # 换到没装 WorkBuddy 的机器：目录不存在时不应报错
        with TemporaryDirectory() as tmp:
            store = InboxStore(
                Path(tmp) / "inbox",
                watch_dir=Path(tmp) / "absent" / "chat-history",
                poll_seconds=0,
            )
            self.assertEqual(store.scan(), [])
            self.assertFalse(store.status()["watch_dir_exists"])


class InboxEndpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.watch_dir = self.root / "chat-history"
        self.watch_dir.mkdir()
        self.store = InboxStore(
            self.root / "inbox", watch_dir=self.watch_dir, poll_seconds=0
        )
        self._original_inbox = main.INBOX
        main.INBOX = self.store
        self.client = TestClient(main.app)

    def tearDown(self):
        main.INBOX = self._original_inbox
        self.store.stop_watcher()
        self.tmp.cleanup()

    def seed_watch_dir(self, name="聊天记录_20260927.zip"):
        source = self.watch_dir / name
        source.write_bytes(build_chat_zip())
        backdate(source)
        return source

    def test_overview_lists_scanned_items(self):
        source = self.seed_watch_dir()
        response = self.client.get("/api/inbox")
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertTrue(body["status"]["watch_dir_exists"])
        self.assertEqual(len(body["items"]), 1)
        self.assertEqual(body["items"][0]["filename"], source.name)

    def test_import_runs_triage_and_marks_manifest(self):
        source = self.seed_watch_dir()
        response = self.client.post(
            "/api/inbox/import",
            json={"filename": source.name, "as_of": "2026-09-27"},
        )
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["source"]["source_kind"], "inbox-import")
        self.assertEqual(body["source"]["source_strategy"], "workbuddy-share-inbox")
        self.assertEqual(body["source"]["conversation"], "测试班级群")
        self.assertTrue(body["items"], "应至少分拣出一个候选事项")
        # manifest 已标记 imported
        record = self.store.list_items()[0]
        self.assertEqual(record["status"], "imported")

    def test_import_without_filename_is_rejected(self):
        response = self.client.post("/api/inbox/import", json={})
        self.assertEqual(response.status_code, 400)

    def test_import_missing_file_is_rejected(self):
        response = self.client.post(
            "/api/inbox/import", json={"filename": "ghost.zip"}
        )
        self.assertEqual(response.status_code, 400)

    def test_import_invalid_zip_marks_error(self):
        source = self.seed_watch_dir()
        # 先走正常概览链路，拿到 manifest 登记的条目文件名
        overview = self.client.get("/api/inbox").json()
        entry_name = overview["items"][0]["filename"]
        # 用一个能通过 zip 校验、但缺少聊天记录文本的包替换收件箱内容
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("readme.md", b"nothing")
        (self.root / "inbox" / entry_name).write_bytes(buffer.getvalue())

        response = self.client.post(
            "/api/inbox/import", json={"filename": entry_name}
        )
        self.assertEqual(response.status_code, 400)
        record = next(
            item for item in self.store.list_items() if item["filename"] == entry_name
        )
        self.assertEqual(record["status"], "error")
        self.assertIn("聊天记录", record["error"])

    def test_import_unregistered_filename_is_rejected(self):
        self.seed_watch_dir()
        # 收件箱目录里有文件、但不在 manifest 登记内：必须拒绝
        rogue = self.root / "inbox" / "rogue.zip"
        rogue.write_bytes(build_chat_zip())
        response = self.client.post("/api/inbox/import", json={"filename": "rogue.zip"})
        self.assertEqual(response.status_code, 400)
        self.assertIn("收件箱", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()
