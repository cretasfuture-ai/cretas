# WEB-SITE-20261012 — 官网去餐饮与内容动效优化

- Owner: `/root`; DIRECT implementation in `C:/Users/Steve/cretas-website-remove-restaurant-20261012`.
- Base SHA: `3ee3db1dad527404cd251c61947e6844628cd9cf`.
- Scope: `platform/` 官网八个主页面、餐饮旧入口、sitemap、共享 CSS/四个动画脚本及本次静态发布工具；不涉及后台业务、数据库、API 或 Nginx 配置。

## Result

删除首页、导航、页脚、演示中心、SmartBI 与定制案例中的餐饮推广。旧餐饮方案入口转到工厂方案，旧餐饮演示入口转到演示中心，并设置 noindex。首页采用工厂生产图片；标题、接入步骤和产品能力文案改为具体业务流程，画面及运营流标明功能示意。

全屏 7 秒开场改为 1.2 秒非遮挡品牌提示，手机跳过；缩小入场位移和按钮磁吸，进度条使用 transform；缩短桌面展示固定滚动距离，案例与接入步骤回归自然阅读。手机原生滚动，保留减少动效与无 JS 的可读路径。

## Validation before publication

- `python platform/scripts/test_publish_restaurant_removal.py`: Windows: 16 passed / 2 Linux-only skips；139 上 /tmp 隔离 Linux: 18 passed。覆盖未确认默认只读、expected-head/dirty/main前进拒绝、基线/路径拒绝、幂等备份、失败回滚、HTTPS失败真实文件恢复、并发修改阻断回滚及 mode/uid/gid 恢复、已发布版本HTTPS失败零回滚、临时symlink防护及全批次并发回滚拒绝。
- Four changed JS files: `node --check` passed; `git diff --check` passed.
- Browser: 8 primary pages × desktop 1440/mobile 390: no dining links/copy and no horizontal overflow.
- Homepage: desktop 1440/tablet 768/mobile 390 × normal/reduced motion: 6 passed; mobile/reduced motion zero pinned sections and no Lenis; all reveal content visible after scrolling; no pageerror.
- No-JS mobile homepage: intro hidden and reveal content visible.
- Read-only production baseline: 16 files verified. Three live files drift from Git: retired restaurant landing page, v6.js observer-proxy fix (preserved), v6-premium.js older duplicate animation owners (replaced by main's single-owner structure).
- Local evidence: `C:/Users/Steve/cretas-website-removal-evidence-20261012/` includes before HTML/assets, screenshots, and publisher test log.

## Release boundary

Java/Web orchestrator does not cover `platform/`; this change includes a narrow static publisher. It defaults to read-only; writes require an explicit YES-PROD flag, exact expected HEAD and clean exact origin/main. It holds a server flock, validates the whole live baseline, saves original bytes/metadata to a HEAD-keyed manifest outside the webroot, publishes assets before pages, restores content/metadata on transaction failure, and automatically performs guarded rollback on HTTPS verification failure. The rollback path checks current/new and backup/old hashes for the entire batch before restoring anything. Only the 16 allowlisted website files are uploaded. PR/CI, merge, production publication and live browser acceptance remain separate states and are recorded in the final delivery receipt.
