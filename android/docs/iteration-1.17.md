# 1.17 iteration: user-confirmed calendar writing

## Scope

This iteration adds an explicit, foreground-only calendar flow for events already
shown in the Attention Guard ledger. It does not add background synchronization,
notification ingestion, or automatic task creation.

## User flow

1. Open an event detail and tap `加入日历待办`.
2. Review or edit the title, date, time/all-day setting, reminder and target calendar.
3. Tap `确认并写入` once the preview is correct.
4. The result page reports whether a new event was written or an existing marker was found.
5. `打开日历查看` is the only follow-up action after a successful confirmation.

Back navigation, an empty target selection, an invalid date, a denied permission,
or closing the page leaves the system calendar unchanged.

## Guardrails

- Calendar permissions are requested only when the user chooses a calendar.
- Relative or ambiguous event dates are never silently converted into a date.
- Provider writes are an atomic event/reminder batch.
- Each event carries a private Attention Guard marker used for idempotent lookup.
- App completion and archive actions do not edit or delete the calendar event.

## Verification

- JVM/Robolectric coverage includes preview-without-write, missing-date validation,
  calendar selection requirements, provider transaction behavior, reminder linkage,
  duplicate confirmation, lost local receipt recovery, deleted-event re-add, and
  denied-permission handling.
- APK built and installed on Xiaomi `25098PN5AC` on September 26, 2026.
- Installed package reports `versionName=1.17`, `versionCode=18`.
- No real calendar event was created during the device UI smoke check without an
  explicit user confirmation.
