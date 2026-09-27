# 1.14 iteration: WeChat affect cues and overlay consistency

## Changes

- WeChat accessibility capture now includes `com.tencent.mm:id/bkm` media nodes.
  Image ancestors remain `[图片]`; custom stickers with accessible descriptions
  become named sticker cues, while unnamed stickers become a low-confidence
  `[表情:未命名贴纸]` cue instead of disappearing from the visible context.
- Expanded personal-chat affect vocabulary for common wording such as being
  bullied, feeling hurt, being ignored, support, reassurance and comfort.
  Unnamed stickers are shown as an emotion signal with direction explicitly
  marked as unconfirmed.
- Event-monitoring and intent-analysis compact panels use the same width.
  Expanded panels remain capped at half the display width. Overlay heading,
  label and caption sizes now use one smaller overlay-specific type scale and
  tighter line spacing.

## Verification

- Focused JVM tests passed for WeChat adapter, intent/affect engine and overlay
  controller.
- Debug APK built and installed over the existing data on the connected Xiaomi
  25098PN5AC device: `1.14 / versionCode 15`.
- No WeChat message was sent. Live visual re-check was paused when the phone
  returned to its lock screen; no unlock credential was entered.
