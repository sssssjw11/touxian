# 1.10 real-device acceptance (2026-09-24)

Device: Xiaomi 25098PN5AC, Android physical display 1220x2656. App: versionCode 11, versionName 1.10, preserve-data install. Testing used visible WeChat chats only; no messages were sent. Chat contents and screenshots are intentionally not stored in this report.

| Flow | Observed result | Status |
| --- | --- | --- |
| Intent mode in a text chat | The overlay updated from visible bubbles, displayed whole-screen context count, importance, confidence and source, and remained active across navigation. | Pass |
| Switch to event monitoring and back | The persisted mode changed to `EVENT`, then `INTENT`; diagnostics followed the selected mode. An out-of-scope group reported "conversation not in scope". | Pass |
| Overlay size | Expanded -> compact -> 52dp ball -> compact worked by touch on the phone. | Pass |
| Intent mode storage isolation | Archive database remained 520192 bytes and event JSON 96098 bytes across observed intent updates and mode changes. This is a file-size check, not a row-count audit. | Pass, limited |
| Group title accessibility | A visible group title was absent from WeChat's accessibility text; only the unread count appeared in the header tree. This prevents automatic event scope matching even when the drawn title is correct. | Fail |
| Manual title OCR | In another two-line group title, the OCR suggestion used the full second line instead of the truncated first line. The final save-and-bind step was interrupted by phone navigation, so the end-to-end binding remains unverified. | Partial |
| Intent relevance | A system line about inviting a member into a group was labeled as an action request. It should not create a request signal. | Fail |
| Confidence discrimination | Two different chats/action contexts both displayed 85%, so the current dynamic heuristic still saturates in common cases. | Fail |
| Opacity and layout | At the user's 16% setting, the overlay background faded but mode buttons stayed opaque; low-opacity text became hard to read and type styles looked inconsistent. Expanded text covered substantial chat area. | Fail |
| Chat-list transition | Intent analysis waited for a readable chat rather than analyzing the conversation list. The compact overlay still occupied list space. | Partial |

## Next changes, in order

1. Ignore or separately classify WeChat system membership lines before request detection. Rebalance confidence using distinct evidence so routine chats do not cluster at 85%.
2. Improve title discovery using conservative OCR/manual confirmation when the accessibility header is blank; do not infer a title from chat content. Verify the final manual binding on-device.
3. Make opacity apply consistently to every overlay surface and ensure readable text contrast; unify type scale and reduce expanded height/space. Keep the ball as the lowest-footprint state.

Recheck all three on the same phone with no outgoing WeChat messages. Keep raw chat text and captures out of the repository.
