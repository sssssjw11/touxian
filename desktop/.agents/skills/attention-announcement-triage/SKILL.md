---
name: attention-announcement-triage
description: >-
  Read class, department, and college WeChat group logs; separate actionable
  announcements from noise, extract deadlines and required actions, and rank
  items by urgency and importance using a JEV-style typed decision graph.
---

# Attention 公告分拣

Use this skill when the user asks to process a WeChat group log, class notice
stream, college announcement stream, or a similar high-volume information
channel. The goal is an executable attention queue, not a prose summary.

## Core Model

This skill uses a JEV-style architecture:

- Keep one immutable shared state containing the source messages and evidence.
- Run independent typed judges over each candidate: `Boolean`, `Choice`, and
  `Score`. Keep DDL extraction as a deterministic application parser rather
  than asking a bounded classifier to invent dates.
- Allow `unknown` and low-confidence results. Do not invent a deadline,
  sender, audience, or required action.
- Combine judge outputs with a deterministic reducer. The reducer owns the
  final priority and ordering; free-form text does not override it.
- Treat probabilities as routing evidence, not as truth. The application must
  define review thresholds and retain an insufficient-evidence path.
- Keep every result traceable to message IDs and timestamps.

Read [references/jev-architecture.md](references/jev-architecture.md) before
changing the decision graph. Read
[references/announcement-rubric.md](references/announcement-rubric.md) before
changing category, priority, or deadline rules.

## Workflow

1. Locate or export the requested group. For this repository, use
   `scripts/extract_messages.py --contact "<群名>" --output-dir data/contacts`.
   The Attention Desk web app also exposes a read-only local WeChat bridge:
   `GET /api/wechat/groups` lists groups from the already decrypted SQLite,
   and `POST /api/wechat/analyze` exports the selected group before running
   this same workflow. It never sends messages or joins groups. The web API
   accepts optional `from_date` and `to_date` fields; the inclusive filter is
   applied before candidate clustering, while `as_of` remains the DDL baseline.
2. Treat the generated `messages.json` as the source of truth. Do not infer
   missing sender names from `me`/`them` when the source is a group export.
3. Run:

   ```powershell
   .\.venv\Scripts\python.exe .agents\skills\attention-announcement-triage\scripts\triage_announcements.py `
     --input "<bundle_dir>\messages.json" `
     --output-dir "<bundle_dir>\announcement_triage" `
     --as-of YYYY-MM-DD
   ```

   To inspect only messages sent on a selected date, add `--date YYYY-MM-DD`.
   For an inclusive range, use `--from-date YYYY-MM-DD` and/or
   `--to-date YYYY-MM-DD`. `--date` is shorthand for setting both bounds to
   the same day. Date filtering happens before candidate clustering, so
   messages outside the range cannot be merged into a selected announcement.
   By default, an announcement candidate with an `@所有人` message within
   five minutes before or after its message window is promoted to at least P2.
   Adjust the window with `--all-mention-window-minutes N`; use `0` to disable
   the promotion. The decision stores the neighboring message IDs as evidence.

4. Read `triage.json`, `attention.md`, and `evidence.jsonl`. Report the top
   actions first, then the full category counts and unresolved items.
   To generate a standalone browser report:

   ```powershell
   .\\.venv\\Scripts\\python.exe .agents\\skills\\attention-announcement-triage\\scripts\\render_html_report.py `
     --input "<bundle_dir>\\announcement_triage\\triage.json" `
     --output "<bundle_dir>\\announcement_triage\\report.html"
   ```
5. If the user asks for a refreshed result, rerun extraction before triage;
   never merge a new group export with an older triage file by hand.

## Output Contract

The reusable script emits:

- `triage.json`: machine-readable candidates, typed judge outputs, priority,
  deadline status, and evidence references.
- `attention.md`: human-readable queue ordered as P0/P1/P2/P3.
- `evidence.jsonl`: one compact evidence record per candidate.
- `report.html`: standalone local report with search and priority/category/action filters.

When a date filter is used, `triage.json` records both the complete archive
range and the selected message-date range under `source.message_date_filter`.
The report also distinguishes total archive messages from messages included in
the current run.

For group logs, call out the archive's newest message date. If it predates
the current date, say that the result is based on the available archive and
does not prove that no newer announcement exists.

## Safety And Scope

- This skill reads local chat exports. It does not send messages, join groups,
  or mutate WeChat.
- Personal information in the raw log stays under `data/`; reports should
  quote only the minimum text needed to justify an action.
- Recruitment, payment, identity, safety, exam, and deadline messages should
  default toward review when evidence is incomplete, but must retain the
  uncertainty label.
- Technical how-to posts, configuration commands, version numbers, and ordinary
  resource shares are classified as `resource` rather than announcements unless
  the text has a clear group-wide action or notice structure. DDL and urgency
  remain application-owned; a remote Jev provider cannot override them.
