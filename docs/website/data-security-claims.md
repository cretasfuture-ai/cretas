# 官网「数据安全」页 · 承诺台账

页面：`platform/data-security.html`。页面上每一句承诺都带 `data-claim="<ID>"` 和 `data-claim-status`。

**规则：只有 `live` 的承诺能出现在线上。** 发布前，把所有 `pending` 块删掉（不能只是隐藏：HTML 源码是公开的）。
某条变成 `live` 的条件是：代码已经合并、已经部署，而且线上验证过；证据要写进下表。
预览时，`pending` 块会显示橙色虚线框和「待上线」标签（样式只在本页里，删掉块之后就不会出现）。

| ID | 页面上的说法（摘要） | 状态 | 证据 / 还缺什么 |
|---|---|---|---|
| C1-ai-scope | AI 查数据时工厂身份取自登录凭证，模型不能指定工厂 | live | Java `ai/tool/AbstractTool.java` 只从服务端上下文读 factoryId；`ToolPrincipalPolicy` 从数据库重新加载用户、工厂、权限（2026-10-22 只读核查） |
| C1-api-scope | 每个读写工厂数据的接口都只按登录身份取数 | pending | 2026-10-22 已修复 `analysis-cache`（#1094，线上：不带凭证 401，读本厂 200，读他厂 404）。还缺 Python `PUBLIC_PREFIXES` 下其余路径、信任客户端 factory_id 的端点做同类排查（交给「方案审计与执行」第 7 项） |
| C1-no-cross-learning | AI 学到的说法不跨工厂 | pending | 要关掉 KeywordPromotion / CrossFactoryKnowledge 的跨厂共享，并清理线上已经提升过的行（第 1 项） |
| C1-headline | 「看不到。」 | pending | 依赖 C1-api-scope 和 C1-no-cross-learning 都变成 live |
| C2-platform-access | 配方代号显示、授权查看、平台访问留痕、工厂可查访问记录 | pending | 工艺保密（ProcessSecret，「方案审计与执行」在做）＋ 平台读取审计（第 3 项） |
| C3-training-consent | 未经书面授权不记录、不训练；两档授权 | pending | 导出那一步已有授权闸（V20261001_01）；还缺「记录时也看授权」、finetune 导出加过滤、清理未授权工厂已存的样本（第 2 项） |
| C4-llm-egress | 阿里云通义千问、境内处理、发送前替换名称、替换失败就不发 | pending | Python `llm_redactor` 已上线（只替换名称）；Java `WorkdeskOutputSummarizer` 还没脱敏；fail-closed 在「方案审计与执行」分支上。另外要 Steve 确认通义千问的数据条款和处理地域 |
| C5-hosting | 阿里云中国境内服务器，用户到系统全程 HTTPS | live | nginx TLS 1.2/1.3（`ops/nginx-vhosts-139/admin.cretaceousfuture.com.conf`）；服务器 IP 属于阿里云。注意：网关到应用服务器（139 → 47:8083）是明文，所以只写「你和系统之间」 |
| C5-daily-backup | 每天凌晨自动备份，保留 7 天 | live | 2026-10-22 线上只读核查：crontab 03:00 `backup-db.sh` ＋ `/etc/cron.d/pg_backup`，`/etc/cron.d/cretas-backup-retention` 保留 7 天；当天三个库的备份文件都在 |
| C5-offsite-encrypted | 备份加密，并在独立存储里另存一份 | pending | 现在和数据在同一台机器上，而且没有加密；钥匙保管方式等 Steve 定（第 5 项） |
| C6-rights | 可以导出全部数据、彻底删除、查看访问记录 | pending | 还没有整厂导出和删除功能（停用只是标记 inactive）；访问记录依赖第 3 项 |

## 隐私政策里同样要核对的说法（`platform/privacy.html`，2026-03-03 版）

- 「我们定期进行安全审计」：没有找到对应的流程或记录。
- 「请求删除您的账户和相关数据」：没有整厂删除功能。目前只能人工处理，要按 C6 补上。
- 「访问权限基于角色进行严格管控，确保数据仅对授权人员可见」：平台角色可以看所有工厂，而且读取不留痕（C2）。

这些属于法律文本，要不要改、怎么改，由 Steve 决定，这里只做记录。
