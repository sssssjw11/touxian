import io
import struct
import unittest
import zipfile
import zlib
from datetime import datetime

from server import chat_archive


CHAT_TEXT = """测试班级群
·班委
2026年9月23日 07:52
【关于课程补选的通知】

各位同学：
请于9月24日18:00前登录教务系统完成申请。

·逐梦者
2026年9月23日 22:01
关于挑战杯竞赛的通知已挂网https://ai.wzu.edu.cn/info/1339/30110.htm，请于10月10日16:00前填写在线表格。

·逐梦者
2026年9月23日 22:01
[文件] 挑战杯附件.zip

·owowow
2026年9月24日 12:05
[图片] 微信图片_202609241205_1.jpg

·owowow
2026年9月24日 13:05
@所有人 转专业转到我们班的同学请联系我
"""


def make_zip(entries, text_name="聊天记录.txt"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(text_name, CHAT_TEXT.encode("utf-8"))
        for name, payload in entries:
            archive.writestr(name, payload)
    return buffer.getvalue()


def make_gbk_zip(entry_name, payload, text_name="聊天记录.txt", text=CHAT_TEXT):
    """构造一个文件名按 GBK 原始字节存放、且未设 UTF-8 标志的 zip。

    这正是 Windows 资源管理器「压缩到 zip」产出的形态，也是本项目
    必须兼容的真实场景。标准库 ``zipfile.writestr`` 写不出这种包，
    所以这里手写本地头 + 中央目录。
    """
    out = io.BytesIO()
    central = []

    def add(name_bytes, body):
        crc = zlib.crc32(body) & 0xFFFFFFFF
        offset = out.tell()
        out.write(struct.pack(
            "<IHHHHHIIIHH", 0x04034B50, 20, 0, 0, 0, 0, crc, len(body), len(body), len(name_bytes), 0,
        ))
        out.write(name_bytes)
        out.write(body)
        central.append((name_bytes, crc, len(body), offset))

    add(text_name.encode("utf-8"), text.encode("utf-8"))
    add(entry_name.encode("gbk"), payload)

    central_start = out.tell()
    for name_bytes, crc, size, offset in central:
        out.write(struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, 0, 0, 0, 0, crc, size, size,
            len(name_bytes), 0, 0, 0, 0, 0, offset,
        ))
        out.write(name_bytes)
    central_size = out.tell() - central_start
    out.write(struct.pack(
        "<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central), central_size, central_start, 0,
    ))
    return out.getvalue()


class ParseChatTextTests(unittest.TestCase):
    def test_splits_sender_datetime_and_multiline_content(self):
        messages, contact = chat_archive.parse_chat_text(CHAT_TEXT)
        self.assertEqual(contact, "测试班级群")
        self.assertEqual(len(messages), 5)
        first = messages[0]
        self.assertEqual(first["sender"], "班委")
        self.assertEqual(first["type"], "text")
        self.assertIn("关于课程补选的通知", first["content"])
        self.assertIn("9月24日18:00前", first["content"])
        expected = int(datetime(2026, 9, 23, 7, 52).timestamp())
        self.assertEqual(first["timestamp"], expected)
        # 逐条递增且 local_id 连续
        self.assertEqual([m["local_id"] for m in messages], [1, 2, 3, 4, 5])

    def test_inline_url_in_plain_text_stays_text(self):
        """正文里带链接但不是「[链接] 卡片」的消息仍算 text，由分拣层提取 URL。"""
        messages, _ = chat_archive.parse_chat_text(CHAT_TEXT)
        notice = messages[1]
        self.assertEqual(notice["type"], "text")
        self.assertIn("https://ai.wzu.edu.cn/info/1339/30110.htm", notice["content"])

    def test_link_card_marker_becomes_link_type(self):
        text = "·班长\n2026年9月24日 09:00\n[链接] 第十一届创新创业大赛 https://mp.weixin.qq.com/s/abc123\n"
        messages, _ = chat_archive.parse_chat_text(text)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["type"], "link")
        self.assertIn("https://mp.weixin.qq.com/s/abc123", messages[0]["content"])

    def test_file_and_image_markers_are_typed(self):
        messages, _ = chat_archive.parse_chat_text(CHAT_TEXT)
        self.assertEqual(messages[2]["type"], "file")
        self.assertEqual(messages[3]["type"], "image")
        self.assertEqual(messages[3]["sender"], "owowow")

    def test_bullet_like_line_inside_body_is_not_a_sender(self):
        text = "·辅导员\n2026年9月25日 09:00\n请准备材料：\n·身份证\n·学生证\n以上缺一不可，务必带齐。\n"
        messages, _ = chat_archive.parse_chat_text(text)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["sender"], "辅导员")
        self.assertIn("·身份证", messages[0]["content"])

    def test_all_mention_is_preserved_for_priority_lift(self):
        messages, _ = chat_archive.parse_chat_text(CHAT_TEXT)
        self.assertIn("@所有人", messages[4]["content"])

    def test_handles_dash_date_format(self):
        text = "·班长\n2026-09-24 10:05\n重修报名截止9月27日23:00\n"
        messages, _ = chat_archive.parse_chat_text(text)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0]["timestamp"], int(datetime(2026, 9, 24, 10, 5).timestamp()))


class ParseArchiveTests(unittest.TestCase):
    def test_reads_text_and_links_attachments(self):
        payload = make_zip([
            ("聊天记录内的图片、视频和文件/挑战杯附件.zip", b"PK\x03\x04fake"),
            ("聊天记录内的图片、视频和文件/微信图片_202609241205_1.jpg", b"\xff\xd8\xff\xe0jpg"),
        ])
        result = chat_archive.parse_archive(payload)
        source = result["source"]
        self.assertEqual(source["source_kind"], "archive-upload")
        self.assertEqual(source["contact_display"], "测试班级群")
        self.assertEqual(len(source["messages"]), 5)

        messages = source["messages"]
        file_message = messages[2]
        self.assertEqual(file_message["type"], "file")
        self.assertEqual(file_message["file"]["name"], "挑战杯附件.zip")
        self.assertEqual(file_message["file"]["extension"], "zip")
        self.assertTrue(file_message["file"]["md5"])

        image_message = messages[3]
        self.assertEqual(image_message["type"], "image")
        self.assertEqual(image_message["file"]["extension"], "jpg")

        self.assertEqual(result["summary"]["attachment_count"], 2)
        self.assertEqual(result["summary"]["resolved_attachment_count"], 2)
        self.assertEqual(result["summary"]["archive_start"], "2026-09-23")
        self.assertEqual(result["summary"]["archive_end"], "2026-09-24")

    def test_gbk_encoded_entry_names_are_restored(self):
        """Windows 压缩工具常把中文名按 cp437 存放实为 GBK 的字节。"""
        payload = make_gbk_zip(
            "聊天记录内的图片、视频和文件/挑战杯附件.zip", b"PK\x03\x04fake",
        )
        result = chat_archive.parse_archive(payload)
        self.assertEqual(result["summary"]["attachment_count"], 1)
        self.assertEqual(result["attachments"][0]["name"], "挑战杯附件.zip")
        # 还原后的文件名要和 [文件] 标记对得上，不能因乱码而失配
        file_message = result["source"]["messages"][2]
        self.assertEqual(file_message["type"], "file")
        self.assertEqual(file_message["file"]["name"], "挑战杯附件.zip")

    def test_missing_text_entry_raises(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("readme.md", b"no chat here")
        with self.assertRaises(chat_archive.ChatArchiveError):
            chat_archive.parse_archive(buffer.getvalue())

    def test_non_zip_bytes_raise(self):
        with self.assertRaises(chat_archive.ChatArchiveError):
            chat_archive.parse_archive(b"this is not a zip")

    def test_path_traversal_entry_is_contained(self):
        payload = make_zip([
            ("聊天记录内的图片、视频和文件/../../evil.txt", b"x"),
        ])
        result = chat_archive.parse_archive(payload)
        # 归一化后仍被视为附件目录下的条目，但落盘名不含 ..
        for item in result["attachments"]:
            self.assertNotIn("..", item["path"])

    def test_attachment_without_marker_is_still_listed(self):
        payload = make_zip([
            ("聊天记录内的图片、视频和文件/未被引用.pdf", b"%PDF-1.4"),
        ])
        result = chat_archive.parse_archive(payload)
        self.assertEqual(result["summary"]["attachment_count"], 1)
        # 没有对应 [文件] 标记，不强行挂到某条消息上
        self.assertIsNone(result["attachments"][0]["message_index"])


class SafeFilenameTests(unittest.TestCase):
    def test_strips_path_separators_and_keeps_extension(self):
        self.assertEqual(chat_archive.safe_filename("a/b:c*d?.zip", "fallback.zip"), "a_b_c_d_.zip")

    def test_blank_name_falls_back(self):
        self.assertEqual(chat_archive.safe_filename("   ", "fallback.bin"), "fallback.bin")


if __name__ == "__main__":
    unittest.main()
