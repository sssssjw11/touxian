# 1.9 iteration: persistent in-WeChat intent mode

## Goal

Keep the two WeChat workflows explicit. Event monitoring records eligible
visible messages and may create reminders. Intent analysis reads the entire
currently visible text conversation in memory and updates its interpretation
until the user switches back.

## Behavior

1. The WeChat overlay has two manually selected modes: `事件监测` (default) and
   `意图分析`. The choice survives service and app restarts. Entering intent mode
   expands the overlay once; later updates respect a manually collapsed card.
2. In intent mode, each foreground change or 1.5-second poll reads all visible
   chat text bubbles. The latest readable counterpart turn is the focus; every
   visible text turn can contribute to context signals, follow-up resolution and
   the displayed context count. Earlier requests remain available beyond the
   previous four-turn limit; a later closing turn stops a stale request from
   driving a follow-up interpretation.
3. Intent mode bypasses message archiving, event generation, cloud refinement,
   title OCR, and history paging. Switching into it cancels an active history
   task and pending event analysis. Queued writes check the current mode before
   committing. The Android foreground-service status notification remains,
   labeled for the selected mode; it is not a message alert.
4. The overlay shows the likely intent, importance, heuristic context
   confidence, visible-text count, selected relevant excerpts, next step and
   provenance. Confidence is not a calibrated probability or a clinical
   inference. Unreadable, media-only and OCR-body text are not inferred.
5. Returning to event monitoring restarts its ordinary capture flow on the
   current visible chat. It does not retroactively archive pages seen only in
   intent mode.

## Focused verification

- Persisted mode choice, full-screen context beyond four preceding turns,
  closing-boundary behavior, collapsed overlay stability, no archive or event
  writes during intent mode, history rejection and event-mode resumption.
- On the connected Android phone: preserve-data install, in-WeChat mode switch,
  auto-update on visible-context change, no new archive writes, return switch,
  and a visual layout check. Do not send messages.
