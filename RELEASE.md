# 偷闲总仓库发布记录

## 2026-09-27 · 总仓库初始化

### 同步来源

- 桌面端：`sssssjw11/wzu-notice-scraper@126b45e`
- Android 端：`sssssjw11/attention-guard@f258b5b`

### 发布内容

- 统一根目录 README。
- `desktop/`：温州大学官网抓取器和 Attention Desk 工作台。
- `android/`：偷闲 Android 端 1.22 预览版源码。
- 平台能力、部署方式、构建命令和数据边界说明。

### 验证口径

- 桌面端后端回归：252 项通过。
- 桌面端前端生产构建：`npm run build` 通过。
- Android 端本次未在聚合环境重跑：当前机器未配置 Android SDK；源码、历史单元测试和真机验收以 `android/README.md` 及 `android/docs/device-acceptance-1.22.md` 为准。

### 同步规则

总仓库是发布聚合仓库，不自动跟踪来源仓库。更新任一端时，先在来源仓库完成测试，再把对应目录同步到总仓库，并更新根 README 的提交号和版本记录。
