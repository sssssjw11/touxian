# 1.18 iteration: calendar defaults and deadline prefill

## Changes

- The last explicitly selected writable calendar is remembered and preselected
  on the next confirmation page.
- Absolute deadline labels are normalized through the local deadline parser,
  including ISO labels and unambiguous month/day labels already present in event
  records.
- A date without an explicit clock defaults to `10:00` on that date.
- The reminder defaults to `开始时` (`0` minutes) for timed calendar drafts.
- The user can still change the date, time, all-day setting, reminder and target
  calendar before the explicit write action.

## Verification notes

- Updated JVM/Robolectric coverage for date-only defaults, month/day normalization,
  remembered calendar selection, explicit all-day behavior and duplicate lookup.
- Rebuilt and installed on the connected Xiaomi `25098PN5AC` after the change.
- The full device pass records the confirmation preview, manual date selection,
  remembered target calendar, successful write receipt and repeat-submit lookup.
- No message was sent from WeChat; calendar writes occurred only after the
  explicit confirmation action during the device test.
