# 1.12 iteration: evidence-weighted intent and affect

## Why

The real-device 1.10 acceptance found that common chats could cluster at the same confidence value. The previous score mixed a category baseline with a small number of fixed additions, so the number looked more stable than the evidence deserved.

## Changes

- Replaced category-heavy confidence with five visible factors: prior signal strength, direct evidence, contextual support, ambiguity penalty and visible-data quality.
- Context support is distance-aware. A matching request or emotion cue one turn away carries more weight than one several turns away; the current counterpart message is always weighted most.
- Added `ConfidenceTrace` to the local result. The WeChat panel now shows the factor summary instead of presenting a bare percentage.
- Borrowed the local `crush-skill` model's useful separation of prior confidence, time decay and emotional intensity, while keeping this app local, conservative and message-scoped. It does not diagnose attachment style or claim a person's true feelings.
- Expanded affect analysis to five cue families: relationship confirmation, care, positive warmth, negative friction and avoidance/cooling. It reports valence, intensity, evidence, source basis and a separate affect confidence.
- Mixed positive/negative cues, hedge words, and context-only carry-over reduce the affect confidence. No matching cue now reports `情绪不明显` with a low confidence value rather than a separate `证据不足` state.
- System messages are excluded from affect scoring. User-side replies are context only and are never treated as the counterpart's emotion.

## Verification

- Core intent and overlay tests pass after a clean rebuild.
- Regression checks cover strong versus weak requests, descriptive neutral text versus filler, near versus distant context, mixed emotion, avoidance, care and system notices.
- The 1.12 APK was rebuilt successfully and verified on the connected Xiaomi 25098PN5AC phone (physical display 1220x2656) on September 25, 2026. No WeChat messages were sent.
- On a real WeChat group screen, the overlay reported whole-screen context, importance, confidence, affect evidence and source. Re-rendering the same visible chat changed the displayed values from `语境置信度 71% / 把握度 37%` to `35% / 把握度 32%`, confirming that the score is not stuck at one value.
- Expanded -> compact -> 52dp ball -> compact -> expanded was completed by touch. Switching `意图分析 -> 事件监测 -> 意图分析` persisted correctly; event mode reported an out-of-scope conversation and intent mode returned to `整屏语境已更新`.
- Intent-mode updates did not change `message_archive.db` (520192 bytes) or `attention_guard_events_v1.json` (96098 bytes). A recent logcat scan found no `FATAL EXCEPTION` for `com.attentionguard.app`.
- Remaining observation for the next layout iteration: at the user's 16% opacity, the expanded card still covers a substantial part of the chat. The compact bar and ball reduce the footprint, but expanded mode should use a smaller, more consistent surface and typography.
