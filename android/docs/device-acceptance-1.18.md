# Attention Guard 1.18 真机验收记录

## 设备与包

- 设备：Xiaomi `25098PN5AC`
- 系统：Android 17
- 包版本：`1.18` / `versionCode 19`
- 测试范围：微信当前文字聊天；只读识别，不发送消息

## 回归结果

### 悬浮球展开

- 前置：微信停留在获授权的个人聊天页，悬浮球显示在右侧。
- 操作：点击悬浮球中心（真机坐标约 `1135,520`）。
- 结果：通过。悬浮球转换为紧凑工具条，没有消失，也没有崩溃。
- 证据：`.tmp/bubble-click-after-clean.png`

### 意图分析入口

- 操作：点击工具条的意图分析图标。
- 结果：通过。意图分析面板正常展开，显示整屏语境、重要性、语境置信度、情绪线索、情绪置信度和来源时间。
- 证据：`.tmp/intent-after-tap.png`

### 服务稳定性

- 点击后应用进程仍存活。
- 无障碍服务仍处于 Bound/Enabled 状态。
- `Crashed services:{}` 为空。
- `dumpsys window windows` 可见 `com.attentionguard.app` 的无障碍悬浮窗，窗口可触摸区域正常。
- 新包连续执行 6 次“小球 ↔ 紧凑工具条”往返，PID 保持不变，没有新增 ANR。

## 本次修复

`AttentionOverlayController` 改为固定 `FrameLayout` 宿主，整体替换内容树，避免在已挂载的悬浮窗中逐个移除/重挂载子 View。该路径规避了 Xiaomi Android 16/17 构建中 `ViewGroup.resolveDrawables()` 的空子 View 崩溃。

另外，触摸回调不再同步重建已挂载窗口：点击、模式切换、刷新、收起和拖动结束统一在 `ACTION_UP` 返回主循环后重绘，避免 Xiaomi 输入分发等待窗口重排而触发 5 秒 input ANR。

## 后续回归

1. 紧凑工具条收起为小球，再次点击恢复工具条。
2. 事件监测/意图分析模式来回切换。
3. 切换群聊并验证标题与可见消息识别。
4. 日历写入确认页与默认日期/时间/提醒设置。
