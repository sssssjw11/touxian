import unittest
from datetime import date, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

from server.completion_store import apply_completions, item_key, load_completions, set_completion
from server.main import TRIAGE, build_summary


AS_OF = date(2026, 9, 23)


def make_item(days_from_as_of):
    deadline_date = AS_OF + timedelta(days=days_from_as_of)
    return {
        "candidate_id": f"test-{days_from_as_of}",
        "window": {"end": "2026-09-22 12:00"},
        "preview": "请按时提交材料",
        "judgments": {
            "importance": {"value": 80},
            "urgency": {"value": 80},
            "risk": {"value": 0},
            "action_required": {"value": True, "metadata": {"kind": "required"}},
            "record_kind": {"value": "announcement"},
            "is_announcement": {"value": True},
            "audience": {"value": "all"},
            "deadline": {"value": {
                "kind": "deadline",
                "normalized": deadline_date.isoformat(),
                "days_from_as_of": days_from_as_of,
            }},
            "category": {"value": "admin", "confidence": 0.9},
        },
    }


class ArchiveDecisionTests(unittest.TestCase):
    def test_deadline_boundary_moves_item_to_archive(self):
        today = TRIAGE.reduce_priority(make_item(0), AS_OF)
        overdue = TRIAGE.reduce_priority(make_item(-1), AS_OF)

        self.assertEqual(today["decision"]["queue_state"], "active")
        self.assertIsNone(today["decision"]["archive_reason"])
        self.assertEqual(overdue["decision"]["queue_state"], "archived")
        self.assertEqual(overdue["decision"]["archive_reason"], "deadline_passed")

    def test_archive_never_precedes_active_and_is_sorted_by_recency(self):
        active = TRIAGE.reduce_priority(make_item(1), AS_OF)
        recent = TRIAGE.reduce_priority(make_item(-1), AS_OF)
        old = TRIAGE.reduce_priority(make_item(-10), AS_OF)
        active["decision"]["priority"] = "P3"
        old["decision"]["priority"] = "P0"

        self.assertEqual(
            [item["candidate_id"] for item in sorted([old, recent, active], key=TRIAGE.sort_key)],
            ["test-1", "test--1", "test--10"],
        )

    def test_summary_counts_only_active_review_items(self):
        active = TRIAGE.reduce_priority(make_item(0), AS_OF)
        archived = TRIAGE.reduce_priority(make_item(-1), AS_OF)
        active["decision"]["needs_review"] = True
        archived["decision"]["needs_review"] = True

        summary = build_summary([active, archived], 2, None, None, AS_OF, 2)
        self.assertEqual(summary["active_count"], 1)
        self.assertEqual(summary["archived_count"], 1)
        self.assertEqual(summary["needs_review_count"], 1)

    def test_completion_persists_with_evidence_id_across_reanalysis(self):
        source = {"contact_username": "test@chatroom", "archive_start": "2026-09-01"}
        item = TRIAGE.reduce_priority(make_item(1), AS_OF)
        item["message_ids"] = ["m-stable-42"]
        key = item_key(source, item)
        reanalyzed = {**item, "candidate_id": "ann-9999"}
        self.assertEqual(item_key(source, reanalyzed), key)

        with TemporaryDirectory() as directory:
            path = Path(directory) / "completions.json"
            set_completion(key, True, path)
            classified = apply_completions(source, [reanalyzed], load_completions(path))
            self.assertEqual(classified[0]["decision"]["queue_state"], "archived")
            self.assertEqual(classified[0]["decision"]["archive_reason"], "completed")
            self.assertEqual(item["decision"]["queue_state"], "active")
            summary = build_summary(classified, 1, None, None, AS_OF, 1)
            self.assertEqual(summary["completed_count"], 1)
            self.assertEqual(summary["overdue_count"], 0)

            set_completion(key, False, path)
            restored = apply_completions(source, [reanalyzed], load_completions(path))
            self.assertEqual(restored[0]["decision"]["queue_state"], "active")


if __name__ == "__main__":
    unittest.main()
