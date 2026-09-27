# 1.8 iteration: faster WeChat triage and manual intent analysis

## Goal and scope

Reduce taps from a visible WeChat conversation to a monitored source or a
checkable intent clue. WeChat remains the only automatically captured app.
In-chat free analysis and custom input do not require a conversation name.

## Delivered behavior

1. Overlay background opacity uses the full 0-100% range, retaining the prior
   92% default. Text, icons and targets remain opaque.
2. The collapsed and expanded overlay have a mark action. It rereads the active
   WeChat tree and adds the confirmed title to recognition keywords exactly
   once. The first mark changes an empty (all-conversation) scope into a
   restricted one and tells the user. A blocked conversation's message body
   is not persisted before the mark. On WeChat versions that hide the title
   node, an explicit tap tries local header OCR and opens a confirmation page
   where the name or keyword can be corrected. This does not imply automatic
   capture can identify that title later.
3. The WeChat overlay's analyze button reads the latest visible counterpart
   text and up to four preceding text turns. It works without title recognition
   or a matching recognition keyword. A short follow-up can be interpreted in
   light of an earlier request. The result stays in the WeChat overlay and
   shows importance, rule-match confidence, literal evidence, relevant context,
   next step and source. This one-shot analysis does not save an event or send
   a reply. Confidence is a heuristic strength score, not a statistical
   probability.
4. A global toolbar action and an attention-screen row open a free-text mode.
   The user pastes or types chat and taps Start. Optional speaker prefixes
   separate the user's turns from the counterpart's; the latest counterpart
   text is evaluated with the same local rules and context window as the
   WeChat overlay. The result is labeled `手动输入 · 本地规则`. No event, message,
   cloud call or reply is created by this mode.

## Verification gates

- JVM/Robolectric: opacity endpoints, keyword idempotence and scope narrowing,
  blocked-chat marking, untitled in-chat analysis after a poll, custom-text
  context parsing and the result screen.
- Android: build, lint, preserved-data `adb install -r`, launch and custom-input
  result check, settings slider endpoints and return to app. On the user's
  authorized WeChat, check the current-chat overlay, marking and manual
  recognition without sending a message. Verify version and basic interaction.
- Package: deliver the tested debug APK and SHA-256. Debug signing is for
  development; it is not a store release.

## Observed on the connected phone

- Preserved-data install succeeded on the authorized Android 17 phone.
- In a WeChat group whose title was not available as an accessibility node,
  the collapsed analyze action opened an in-WeChat result with the latest
  readable counterpart message, preceding visible turn, importance, confidence
  and source. It remained visible after the next capture poll.
- The mark action opened an editable local title confirmation page and returned
  to the same WeChat chat after confirmation. No message was sent.
- The 0-100 opacity endpoints and free-text result were checked in focused
  JVM/Robolectric tests; this device pass did not exercise every setting.

## Limits and next iteration

- Conversation title alone cannot prove group membership or distinguish two
  chats with the same visible title. Keyword matching is intentionally unchanged.
- In-chat free analysis needs readable text nodes. Images, voice and OCR body
  text are not used for intent inference.
- Current intent rules are heuristic, not a psychological diagnosis. A future
  iteration can evaluate labeled examples before adding broader semantics.
- Accessibility node compatibility, overlay placement on every device/font
  configuration, OCR accuracy and long-running background behavior still
  require broader device coverage.
