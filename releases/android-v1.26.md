# 偷闲 Android 1.26 交付说明

版本 1.26 / versionCode 28，debug 签名。完整同步基于 attention-guard@71c2342 的 Android 源码，包含 1.25.2 的标题延续、OCR 核对和稳定悬浮窗修复。

手机端新增实时「深入理解」、通用/朋友/工作/亲密场景、原话校验的结构化解释、可复制回复、对象档案及多会话录制关联。所有 DeepSeek 调用都需用户点按和确认发送范围。

- [使用、升级与包校验](../android/docs/releases/v1.26.md)
- [接口、存储、异步与验证说明](../android/docs/iteration-1.26.md)
- [SHA-256 文件](../android/docs/releases/touxian-1.26-debug.apk.sha256)

验证：37 个测试类共 408 项通过，assembleDebug 成功，新 APK 与既有 1.25.2 的签名证书相同。数据库从 v3 升至 v4 保留旧消息、录制与报告。

APK 与完整源码包通过本次本地交付提供；当前已公开的历史下载仍为 1.25。本次没有连接手机，尚未执行实际覆盖安装及微信手势验收，网络测试使用模拟响应。桌面代码未修改。
