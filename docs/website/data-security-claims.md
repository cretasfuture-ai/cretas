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
| C1-headline-ai | 「用 AI 问不出来。」（过渡用的标题） | live | 依据同 C1-ai-scope。C1-headline 变成 live 时删掉这一句 |
| C2-platform-access | 配方代号显示、授权查看、平台访问留痕、工厂可查访问记录 | pending | 工艺保密（ProcessSecret，「方案审计与执行」在做）＋ 平台读取审计（第 3 项） |
| C3-core-data | 未经书面授权，不用配方、工艺参数、成本和经营数字训练模型（Steve 2026-10-22 定的口径） | live | 2026-10-22 线上只读核查：`training_consent` 表 0 行，也就是没有任何工厂授权过；导出脚本 `export_distillation_dataset.py` 默认拒绝（V20261001_01）。注意：这是从现在起的承诺，没有核查 6 月至 9 月授权闸上线之前是否导出过样本 |
| C3-consent-gate | 默认关闭；两档授权；没有授权就拒绝导出 | live | 同上 |
| C3-general-knowledge | 改进模型靠通用流程和公开行业知识 | live | 意图标注、评测用的是说法文本，不是业务数字和配方；跨厂共享说法的问题记在 C1-no-cross-learning |
| C3-no-capture | 默认不授权时，连训练素材都不记录 | pending | 线上 `smart_bi_distillation_samples` 仍在按工厂记录（2026-10-22：MOCK_REST 263、SEED_R 87、SEED_F 80、F006 73、LIUSHANMEN 49、CATTLE_3701_001 7、factory 为空 272）。要改成按授权才记录，并清理已存的样本（第 2 项） |
| C4-provider | 阿里云百炼（通义千问）；按量付费 API 的调用数据不用于训练 | live | 阿里云官方页面 https://help.aliyun.com/zh/model-studio/ 的 FAQ「阿里云百炼会用我的数据进行训练吗？」写的是「使用百炼按量付费 API 和 Token Plan 团队版时，不会将您的数据用于模型训练」。Coding Plan 和 Token Plan 个人版不在这个承诺内，Steve 2026-10-22 确认账号都是按量付费。官方没写数据在哪里处理，所以页面上不写「境内处理」 |
| C4-redact / C4-fail-closed | 发送前替换名称；替换失败就不发 | pending | Python `llm_redactor` 已上线（只替换名称）；Java `WorkdeskOutputSummarizer` 还没脱敏；fail-closed 在「方案审计与执行」分支上 |
| C5-hosting | 阿里云中国境内服务器，用户到系统全程 HTTPS | live | nginx TLS 1.2/1.3（`ops/nginx-vhosts-139/admin.cretaceousfuture.com.conf`）；服务器 IP 属于阿里云。注意：网关到应用服务器（139 → 47:8083）是明文，所以只写「你和系统之间」 |
| C5-daily-backup | 每天凌晨自动备份，保留 7 天 | live | 2026-10-22 线上只读核查：crontab 03:00 `backup-db.sh` ＋ `/etc/cron.d/pg_backup`，`/etc/cron.d/cretas-backup-retention` 保留 7 天；当天三个库的备份文件都在 |
| C5-offsite-encrypted | 备份加密，并在独立存储里另存一份 | pending | 现在和数据在同一台机器上，而且没有加密。2026-10-22 Steve 定了钥匙保管：服务器放一份，Steve 自己离线存一份。实施由「方案审计与执行」负责（第 5 项），要求做一次从异地副本恢复的演练 |
| C6-rights | 可以导出全部数据、彻底删除、查看访问记录 | pending | 还没有整厂导出和删除功能（停用只是标记 inactive）；访问记录依赖第 3 项 |

## 隐私政策（`platform/privacy.html`）

2026-10-22 按 Steve 的要求改成只写已成立的内容：
- 删掉「定期进行安全审计」；
- 「仅对授权人员可见」改成如实写明「我方经授权的工作人员可以访问」；
- 删除改为「邮件申请、15 个工作日内处理」（人工流程）；
- 补充 AI 对话记录、语音和图像的收集，核心数据不训练，不向其他企业提供，阿里云百炼的训练说明，以及备份保留 7 天。

Steve 2026-10-22 确认：百炼账号都是按量付费；privacy@cretaceousfuture.com 能收信；删除申请 15 个工作日内处理可以接受。已在 #1097 上线，线上页面已核对：新句子都在，旧的「定期进行安全审计」「确保数据仅对授权人员可见」已经没有了。

### 旧版（2026-03-03）当时核对出的问题

- 「我们定期进行安全审计」：没有找到对应的流程或记录。
- 「请求删除您的账户和相关数据」：没有整厂删除功能。目前只能人工处理，要按 C6 补上。
- 「访问权限基于角色进行严格管控，确保数据仅对授权人员可见」：平台角色可以看所有工厂，而且读取不留痕（C2）。

Steve 2026-10-22 决定修改，改法见上一节。
