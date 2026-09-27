# Attention Guard UX Contract

This contract describes observable Android behavior. Visual tokens live in
`DESIGN.md`; runtime owners live in `GuardUi`, `GuardMotion`, `EventStore`, and the
activities, `MessageArchive`, and `HistorySession`.

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Table Selection | Not applicable: no table widget in the native app | Android navigation and event-list contract | Event row tap only | Unit filtering + manual device check |
| Select/Listbox | Not applicable: no select/listbox is exposed | Settings contract | Toggle, text field, seek bar | Unit validation + manual device check |
| Date | Native Android `DatePickerDialog` in `CaptureActivity` and `CalendarActivity`; `ChatDateParser` for separators | Inclusive date-only `HistoryRange`, device timezone; calendar draft is user-confirmed only | Platform calendar for start/end and explicit event preview; event labels remain display-only until confirmation | ChatDateParser/HistorySession/CalendarDraft tests; device calendar preview check |
| Form | `GuardUi.field` + settings explicit-save + capture explicit-prepare | `DESIGN.md` and settings section below | DeepSeek save; history prepare then in-chat confirmation; OCR consent | ActivityFlow/CaptureActivity tests + lint |
| Scrollbar | Android `ScrollView` platform owner | `GuardUi.scroll` and system theme | One vertical scroll surface per page | Build/lint + manual device check |
| Toast | `GuardUi.feedback` Snackbar and overlay Toast | Shared feedback wording below | Snackbar in app, Toast over other apps | Unit/message review + manual device check |
| CRUD | `EventStore` and `MessageArchive` | Separate event and raw-message lifecycles | Events complete/restore and archive/unarchive; raw messages paginate/clear with confirmation | EventStore/MessageArchive/Activity tests |

## Surface ownership

- `MainActivity` owns the four-tab shell, attention summary, ledger, sources, and
  profile/permission entry points.
- `SettingsActivity` owns DeepSeek-only configuration and explicit save/cancel.
- `CustomIntentActivity` owns transient, user-entered chat analysis. It does not
  read a foreground app or write the event/message stores.
- `CaptureActivity` owns diagnosis, raw-message paging, local OCR opt-in, and history preparation.
- `AttentionOverlayController` owns the read-only cross-app card. It may open the
  app, expand a stored event, manually recognize the current WeChat chat, reset
  its position, or hide itself. It also confirms,
  pauses and cancels an explicitly prepared history session; dismiss pauses it.
- `EventStore` is the only owner of persisted event JSON. `MainActivity` never
  writes event JSON directly.
- `ChatCaptureService` is the only owner of capture/debounce/cancellation. It reads
  the current visible accessibility window and never edits or sends a message.

## Event lifecycle

1. A visible chat snapshot is separated into candidate notices and passes the
   local Attention Gate. Useful information updates may become monitoring
   records without being turned into mandatory tasks.
2. If it is actionable, due, or high priority, the app may call DeepSeek when the
   user has enabled cloud enhancement and a usable encrypted key exists.
3. All accepted local events from the screen are merged by stable ID in one
   `EventStore.upsertAll` write, with a bounded timeline and original evidence.
   The overlay displays the highest-priority event, breaking ties by attention
   score; the other events remain available in the ledger.
4. The user can open detail, expand evidence, mark complete, restore the prior
   status, archive, or unarchive. Archive is a reversible flag independent of
   completion; archived items leave active filters and counters.
5. A failed DeepSeek request keeps the local event and shows a degraded-mode notice.
   A failed local read or save never overwrites the previous record.
- Each event records its latest capture origin (WeChat automatic/manual), capture
  time, conversation, sender and original evidence. Old records with no origin
  field display an unverified-source label rather than a fabricated origin.

## WeChat capture modes and intent analysis

- The WeChat overlay selects `事件监测` (default) or `意图分析`; the selected mode
  persists until changed manually. The collapsed mode icon and expanded
  segmented control both allow switching. Entering intent mode opens the result
  once, while later automatic updates respect a collapsed card.
- Intent mode reads every currently visible, accessible text bubble, not only
  the immediately preceding message. The latest readable counterpart turn is
  the focus, with all visible turns available for context and follow-up cues.
  The result shows a visible-text count, selected relevant excerpts, possible
  intent, importance, context confidence, next step, capture time and source.
  It updates on visible changes or a foreground poll without requiring a
  recognized conversation title or collection keyword.
- Intent mode does not archive messages, create event reminders, invoke the
  cloud model or run history paging. An active history task and pending event
  analysis are canceled on entry. Event monitoring resumes only when the user
  switches back, without retroactive capture of intent-only screens. The
  Android foreground-service status remains visible and reflects the mode.
- The Jev-inspired intent reading is local heuristic guidance, not a call to an
  online Jev model or a verified psychological conclusion. Its confidence is
  context-cue strength, not a calibrated probability. It does not infer intent
  from unreadable bubbles or OCR body text and does not invoke DeepSeek.
- The global custom page accepts free text, optionally using `我：`/`对方：`
  (also `Me:`/`Them:`) turn prefixes. Unlabeled text is treated as counterpart
  text; the latest counterpart turn and all supplied context are classified. If the
  user replied afterward, the next step says to recheck whether action is still
  needed. Neither mode requires a conversation name.
  Empty or self-only input cannot produce an insight. The result is labeled
  `手动输入 · 本地规则`; it never becomes a captured WeChat message or event,
  invokes no cloud request, and is not saved by the app.

## Current conversation marking

- The WeChat overlay can mark the currently confirmed conversation from both
  collapsed and expanded states. A tap performs a new foreground tree read;
  cached titles and non-WeChat windows are not accepted.
- If visible bubbles are confirmed but the title node is unavailable, the
  explicit tap attempts one local header OCR pass, then opens a compact
  editable confirmation page. The user may correct or enter a keyword before
  saving. This does not turn on continuous background title screenshots.
- The confirmed title is added once to the existing recognition keyword set.
  A second tap is idempotent. When the prior set was empty (all conversations),
  the first mark narrows monitoring to the new term and explicitly says so.
  Existing terms are preserved. A blocked conversation is not stored as messages
  before marking; a settings change then triggers a fresh capture.
- The app cannot reliably establish whether a named conversation is a group or
  a one-to-one chat from the visible title alone. Marking therefore applies to
  the current confirmed WeChat conversation, and title keyword matching retains
  its existing limitations for duplicate or substring-matching names. A
  manually confirmed term alone cannot make title-inaccessible chats eligible
  for automatic capture. One-shot in-chat analysis is independent of the title
  and recognition keyword set.

## Navigation and state

- Back from detail returns to the previous list; back from another tab returns to
  注意力; back from 注意力 exits the activity.
- The overlay opens its stored event in the ledger on cold launch or through an
  existing activity. Missing event IDs fall back to the ledger. This entry leaves
  demo mode so real notifications never display an unrelated demo event.
- Tab, filter, query, detail ID, evidence disclosure, list limit, and each tab's
  scroll position survive activity recreation.
- The capture workspace has History, Records and Diagnostics tabs; the selected
  tab and history draft survive recreation. Preparation locks the active draft.
  Returning to WeChat still requires the in-chat Start/Resume action.
- Permission and health are separate: a granted service with no live same-process
  connection or a heartbeat older than 10 seconds is disconnected, not observing.
- The search clear affordance clears immediately and resets the result list.
- Empty states distinguish “no events” from “no matching events” and offer a path
  to settings or demo mode where appropriate.

## Settings and sensitive data

- The only visible provider is DeepSeek Official at `api.deepseek.com`.
- Cloud enhancement is opt-in. It sends the current visible conversation name,
  up to 12 visible messages matching the selected event's evidence (body capped
  at 2,000 characters each), associated
  sender/side/mention metadata, up to 2,000 characters of user-entered context,
  the current time, and a preliminary local event title/priority/score.
- API keys are encrypted with an Android Keystore AES-GCM key. Legacy plaintext is
  read only for one migration and removed after a successful encrypted commit.
- The current Attention Guard request path does not log keys, raw chat text, or
  provider response bodies. The DeepSeek client is packaged and called only by
  the product flow; it is outside this guarantee. The connection test sends a
  built-in sample only. A key is sent to DeepSeek as an HTTPS authorization header.
- Event evidence is private local JSON, not application-encrypted. Android backup
  is disabled. Completing an event does not delete its original evidence.
- Leaving unsaved settings requires an app-owned confirmation dialog.
- Overlay background opacity can be set from 0% to 100%; the control, text and
  icons stay opaque at 0%. The existing 92% default is retained on upgrade.

## Calendar Confirmation

- `MainActivity` opens `CalendarActivity` from an event detail; opening the page
  never writes to the system calendar.
- The user must explicitly choose a date when the event label is missing or
  ambiguous, may edit the title and reminder, and must select a writable calendar.
  Absolute deadline labels are normalized locally; date-only labels default to
  10:00 on that date and the reminder defaults to the start time.
- The selected writable calendar is persisted as the next default. If it becomes
  unavailable, the user must choose another calendar before confirming.
- Only the foreground `确认并写入` action calls `CalendarWriter.confirm`.
- `CalendarWriter` uses one provider batch for the event and optional reminder,
  and stores a deterministic marker for idempotent retries.
- Calendar permissions are requested at the calendar-selection step, not at app
  startup. Denied permission, back navigation, and validation errors are
  non-mutating.
- The calendar event is independent from the app event's complete/archive state.

## Capture and privacy boundary

The service accepts only the foreground supported chat package and the visible
conversation tree. It does not scan background groups, query a chat database, use
Xposed/hooking, touch the input field, or click send. If the accessibility tree is
empty, the previous snapshot and pending request are invalidated. The current
WeChat adapter reads visible bubble nodes and returns no event when it cannot prove
the needed text. A changed-ID fallback needs a bottom composer, message list,
same-row avatar and long-clickable body. A proven but unreadable bubble may use
on-device OCR only after an app-owned consent dialog; screenshots are not stored
or uploaded by the capture pipeline. OCR outputs are labeled and never sent to
DeepSeek or used for automatic event creation. No geometry evidence means no OCR.

## Raw Messages And History

- Raw text is saved to app-private SQLite before event selection. It is not
  application-encrypted. Backup remains disabled; retention is until the user
  confirms a clear or uninstalls. Clearing pauses the master switch, serializes
  against queued writes, and does not erase separate event evidence.
- History start/end are inclusive device-local dates, validated in both the UI
  and service. The native calendar is intentionally platform-owned. Draft target,
  dates and automatic/manual selection survive activity recreation.
- Preparing a history task does not scroll. The exact displayed conversation
  name must match and the user must press Start on the in-chat overlay. Identical
  names cannot establish unique chat identity and remain a compatibility limit.
- Manual scrolling is the default. Auto scrolling uses only the identified list's
  `ACTION_SCROLL_BACKWARD`, at least 4 seconds apart after storage settles. There
  is no gesture, editable-field action, paste, send, or automatic group switching.
- Leaving WeChat, lock screen, unconfirmed chat, changed settings, unavailable
  text, save/scroll failure, three unchanged pages, or task limits pause scrolling.
  Resume is explicit. Limits are 200 observed screens/scroll attempts or 15 minutes
  from initial start; pausing cannot reset the budget. Process death never resumes.
- Dates come from visible whole separator labels, not dates inside message text.
  Missing dates remain unknown and are counted separately, not claimed as in-range.
  An all-known screen older than the start ends traversal but never proves full
  coverage. Undated repeats can be updated when neighboring overlap exposes a day.
- Deduplication aligns adjacent screens with ordered, nonambiguous overlap; it is
  not global text hashing. A transactional, bounded checkpoint recognizes an exact
  viewport replay in the same stream within two minutes of service reconnection.
  A schema-v1 to v2 migration preserves existing messages. Single repeated words,
  title collisions, content changes, older restarts or nonoverlapping screens can
  produce duplicates/gaps. Preserve evidence
  rather than silently discard it. Session progress reports gaps, not a fake percent.
- A ready/running/paused history target is isolated from live AI analysis; history
  scans and OCR results do not trigger DeepSeek. No archive-wide AI batch is added.
- Raw records load 50 at a time, newest captured records first. History scope is
  separate from all captured records. Read failure offers retry without mutation.
- Diagnostic copy contains status, counts, timestamps, OS/device and WeChat version;
  never conversation titles, message bodies, screenshots or API keys.

## Async, failure, and recovery contract

- Tree reads are throttled at 180ms with a 1.5-second recovery check. Raw messages
  persist independently; local events need not wait for cloud. Cloud bursts are
  debounced for roughly 2.2 seconds.
- The service uses an accessibility overlay; no separate application-overlay grant
  is required for this path. Unreadable states still show a diagnostic entry. Mount
  failures are recorded; a dismissed overlay stays hidden until chat/window changes.
- OCR waits 250ms after hiding the overlay, rechecks foreground and bubble bounds,
  and ignores results after scene changes/cancel. Shots are at least 8 seconds apart;
  at most three failed attempts per scene, a 20-second timeout, and one success per
  unchanged layout. Scrolling or explicit retry rearms it; no idle capture loop.
- Switching package, conversation, settings, or service lifecycle cancels the old
  request and increments a generation token. Late responses are ignored.
- DeepSeek timeouts and HTTP 401/402/429/model errors map to short Chinese recovery
  messages. Raw response bodies are not shown.
- The connection test has a visible busy state, a cancel action, and a result live
  region. Leaving the screen cancels the request.
- Event storage writes a same-directory temporary JSON file, syncs it, and replaces
  the prior file atomically. Corrupt existing JSON blocks writes instead of
  reverting to stale preferences. Legacy preferences migrate on first mutation.
  The merged timeline is capped at 8 entries and evidence at 6 entries; the total
  event count is not capped. Host filesystem failures are covered by tests;
  device process-death and low-storage testing remain pending.
- DeepSeek responses must finish normally, contain a complete typed event object,
  and stay within 65,536 characters. Invalid responses fall back to local rules;
  only the user may mark an event completed. Valid empty optional fields clear
  heuristic due/action/consequence guesses.

## Priority Evidence (1.19)

- Extract multiple independent notices from the visible screen. Meeting
  announcements, meeting IDs and follow-up reminders may be grouped when they
  have the same known sender, no conflicting known message dates, and no
  conflicting parsed meeting times. Explicit corrections replace the deadline
  evidence while retaining the original event identity.
- Each accepted event has a type, summary, importance, suggested next step and
  source evidence. Meetings use the separate `班会 / 会议` category. Academic or
  recruitment information can be useful without an immediate action request;
  it is displayed and retained with monitoring status.
- Conditional exemptions such as "already submitted, no need to resubmit" do
  not cancel an action required of other recipients. Acknowledgements, bare
  mentions and ordinary questions do not create events on their own.
- These are local wording and context rules calibrated against anonymized
  regression samples, not a trained model or a guarantee of semantic coverage.
- P0 requires action, a calendar-validated deadline within 24 hours, and a sender
  role or all-members signal. Sender labels are not verified identities.
- P1 covers action with a future deadline or source/all-members signal; P2 covers
  weaker action or expired notices pending review; P3 covers weak/cancelled items.
- Relative dates require a message-day separator. Invalid or ambiguous dates are
  marked for review and cannot trigger P0. Past deadlines do not become new urgent
  actions. Adjacent corrections retain the original event identity and evidence.
- DeepSeek cannot raise priority above the local evidence gate or replace the
  source-validated date with its own date. It may lower priority or clear a date.
- Existing event records are not erased or bulk reclassified during upgrade.
  Invalid legacy date labels are marked for review in the UI, preserving evidence.

## Accessibility and localization

- All interactive targets are at least 48dp and icon-only controls have Chinese
  content descriptions.
- Event rows expose three independent semantic actions: detail, complete/restore,
  and archive/unarchive. Decorative child text is hidden from accessibility.
- Important result/status text uses a polite live region where it changes in place.
- System bars, display cutouts, and the IME are applied through window insets.
- `zh-CN` is the release locale; the brand and API model names stay unchanged.
- Reduced-motion behavior follows `ValueAnimator.areAnimatorsEnabled()`.

## Android-only verification boundary

Browser E2E, CSS scrollbar assertions, HTML select popup tests, and web pixel
regression are not applicable to this traditional Android View project. The
replacement evidence is Gradle build, pure JVM and Robolectric tests, Android lint,
APK metadata and resource inspection, plus the bounded real-device audit recorded
in `docs/device-audit-1.5.md` and the installed-APK rule checks recorded in
`docs/device-acceptance-1.19.md`. Installed-APK checks do not replace a WeChat UI
pass; the 1.19 UI pass remains pending manual device unlock.
Untested configurations remain explicitly pending.
Robolectric layout measurements do not constitute screenshot or pixel verification.

