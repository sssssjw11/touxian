# 1.11 iteration: fixes from real-device acceptance

Baseline: [1.10 real-device acceptance](device-acceptance-1.10.md).

- WeChat membership, recall and group-name system lines are classified as system notices, not action requests. The bare Chinese `请` cue no longer fires inside `邀请` or `申请`.
- Action confidence now distinguishes an instruction, a named recipient/deliverable and a deadline. It remains an explainable heuristic, not a probability.
- WeChat intent results include a separate emotion/relationship cue with its own strength and matched phrase. It can carry a cue from the whole visible context when the focused message is terse; it does not assert a person's actual feelings.
- Overlay panel and button backgrounds share the same opacity setting. Text/icons remain solid; dark system mode uses a high-contrast dark overlay palette. Mode-button typography uses the same label scale as the surrounding controls.
- Expanded intent detail is capped at 185dp rather than 240dp, leaving more chat visible. Compact and ball modes remain available.
- Expanded panels reserve space above WeChat's composer when restored from a low compact-toolbar position.

Still open: WeChat can draw a group title without exposing it to accessibility. Such chats require the manual title OCR and user confirmation before event monitoring can bind a title. Do not guess a title from message content.

## Verification on 2026-09-24

- Focused JVM tests passed: 15 intent tests and 12 overlay tests, with no failures.
- VersionCode 12 was installed on the connected Xiaomi phone without clearing app data.
- A membership system line changed from an incorrect action request to a low-importance system notice. A separate ordinary chat displayed 30% intent confidence; the system notice displayed 89%, confirming that the shown values are no longer identical across these cases.
- In an actual WeChat group message, intent mode showed the new emotion line as "no clear emotion" with 45% strength and a visible evidence note. Positive, negative, care and whole-screen carry-over cases were checked in focused JVM tests, not in private live chats.
- At the user's 16% background setting, panel and mode-button surfaces now fade together while foreground text/icons stay solid. Expanded content stopped above the composer; compact and ball controls remained clickable.
- Intent mode diagnostics updated from a titleless but readable group. Archive database and event JSON sizes remained unchanged at 520192 and 96098 bytes respectively. No app fatal exception appeared in the recent device log.

Remaining UX limitation: 16% surface opacity over dense WeChat text can still make the result visually busy. The ball remains the lowest-footprint state. Group-title OCR confirmation was not completed during this acceptance run.
