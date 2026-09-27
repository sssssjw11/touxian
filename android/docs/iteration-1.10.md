# 1.10 iteration: title matching, confidence and overlay space

## Changes

- Scope matching normalizes display formatting before comparing a WeChat title
  with a saved term. It folds full-width characters and case, ignores whitespace
  and common separators, and strips a trailing numeric member count so the same
  group still matches when its count changes. Short ASCII words require word
  boundaries; one-character terms require a full-title match. Terms ending in a
  member count are treated as full titles, not loose substrings. No fuzzy name
  guessing is used for event capture or cloud requests.
- Intent confidence is no longer a constant per category. Local rules weigh the
  focused message's cue count and length, time/question specificity, vague
  references, and the distance to a supporting request within all visible
  turns. It remains an explainable heuristic signal, not a probability.
- The overlay has three persisted sizes: expanded result, compact toolbar and a
  52dp draggable ball. Tapping the first compact control minimizes to the ball;
  tapping the ball restores the toolbar. The expanded intent details are
  scrollable and height-limited while mode and refresh controls stay visible.
- Structural WeChat bubble detection can provide titleless text to intent mode.
  Event monitoring still requires a confirmed title and matching scope.
- When WeChat draws a title but omits its accessibility text, the manual mark
  flow OCRs a taller two-line title region and prefers an untruncated line.
  The edited, confirmed title is scoped to the current window and overlapping
  visible bubbles; a chat change or lost overlap revokes the binding. Events
  from this path carry the manual WeChat source label.

## Focused verification

- Unit checks cover title formatting, changed group counts, accidental short
  matches, confidence variation within one intent, distant context, persisted
  overlay size, compact/ball interaction, titleless structural bubbles and
  conservative title binding.
- Device acceptance: preserve-data install, confirm an in-WeChat intent result,
  switch through all three overlay sizes, return to event monitoring, and check
  that intent refresh does not change archive/event storage. Do not send chat
  messages.
