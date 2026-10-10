# 偷闲 Android 1.26.1

版本 1.26.1 / versionCode 29，包含 1.25.2 和 1.26 的完整 Android 改动。

首页可以直接进入对象档案，查找同名对象并查看新增消息与画像更新状态。自由分析与悬浮卡补充本地语境解释、场景化回应和手动 DeepSeek；报告可筛选栏目，原文可展开同一记录的前后文。会话关联档案后直接返回，转移与解除关联均说明影响。

- [安装、升级及校验](../android/docs/releases/v1.26.1.md)
- [本轮改动与接口说明](../android/docs/iteration-1.26.1.md)
- [八步流程及前后截图](../android/docs/design/1.26.1/review.md)
- [APK 校验文件](../android/docs/releases/touxian-1.26.1-debug.apk.sha256)

41 个测试类共 431 项通过，APK 构建与签名校验通过，使用与先前本地 1.25.2 / 1.26 相同的 debug 证书。SQLite 保持 v4，分析格式升至 v3，旧消息和录制保留，旧会话报告仍可打开。

APK 与 Android 源码包通过本次本地交付提供。未进行真机或真实 DeepSeek 验收；原生渲染截图使用示例聊天。代码提交在已有草稿 PR 中，尚未合并或发布 GitHub Release。
