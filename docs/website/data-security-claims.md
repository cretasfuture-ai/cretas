# 官网「数据安全」页 · 承诺台账

页面：`platform/data-security.html`。页面上每一句承诺都带 `data-claim="<ID>"` 和 `data-claim-status`。

**2026-10-22 Steve 改了口径**：页面直接按「承诺」写全，不等每一项都落地；他接受文案比现状超前。页面上原来 `pending` 的条目现在标为 `commitment`（不再显示「待上线」标记）。
下表照样维护，作用是让团队知道哪些承诺**还欠着实现**：`commitment` 状态的行就是待兑现的清单，由「方案审计与执行」逐项落地。

原来的规则（已被上面的口径取代）：只有 `live` 的承诺能出现在线上。 发布前，把所有 `pending` 块删掉（不能只是隐藏：HTML 源码是公开的）。
某条变成 `live` 的条件是：代码已经合并、已经部署，而且线上验证过；证据要写进下表。
预览时，`pending` 块会显示橙色虚线框和「待上线」标签（样式只在本页里，删掉块之后就不会出现）。

| ID | 页面上的说法（摘要） | 状态 | 证据 / 还缺什么 |
|---|---|---|---|
| C1-ai-scope | AI 查数据时工厂身份取自登录凭证，模型不能指定工厂 | live | Java `ai/tool/AbstractTool.java` 只从服务端上下文读 factoryId；`ToolPrincipalPolicy` 从数据库重新加载用户、工厂、权限（2026-10-22 只读核查） |
| C1-api-scope | 每个读写工厂数据的接口都只按登录身份取数 | pending | 2026-10-22 已修复 `analysis-cache`（#1094，线上：不带凭证 401，读本厂 200，读他厂 404）。还缺 Python `PUBLIC_PREFIXES` 下其余路径、信任客户端 factory_id 的端点做同类排查（交给「方案审计与执行」第 7 项） |
| C1-no-cross-learning | AI 学到的说法不跨工厂 | pending | 要关掉 KeywordPromotion / CrossFactoryKnowledge 的跨厂共享，并清理线上已经提升过的行（第 1 项） |
| C1-headline | 「看不到。」 | pending | 依赖 C1-api-scope 和 C1-no-cross-learning 都变成 live |
| C1-headline-ai | 「用 AI 问不出来。」（过渡用的标题） | 已删除 | 改为直接写 C1-headline「看不到。」 |
| C2-platform-access | 配方代号显示、授权查看、平台访问留痕、工厂可查访问记录 | pending | 工艺保密（ProcessSecret，「方案审计与执行」在做）＋ 平台读取审计（第 3 项） |
| C3-process-never / C3-desensitized / C3-excluded / C3-no-cross-process | 工艺内容任何情况不进训练；只用汇总指标和脱敏问答；排除清单；不分析比对工厂工艺 | live | 最终口径（Steve 2026-10-24 在「方案审计与执行」确认）。#1128 于 10-24 19:18 上线。线上探针由「方案审计与执行」验证：未授权工厂的普通问答脱敏后存入（工厂名换代号、电话换占位、金额分桶）；已授权的 F006 问配方不存；回答里带工艺细节的不存；factory_config_agent 不再生成全局模板。尚未写入页面的：基于公开标准的流程建议（模板库为空）、工艺保密产品不参与训练（功能未对客户开启）、专属模型 |
| C4-provider | 阿里云百炼（通义千问）；按量付费 API 的调用数据不用于训练 | live | 阿里云官方页面 https://help.aliyun.com/zh/model-studio/ 的 FAQ「阿里云百炼会用我的数据进行训练吗？」写的是「使用百炼按量付费 API 和 Token Plan 团队版时，不会将您的数据用于模型训练」。Coding Plan 和 Token Plan 个人版不在这个承诺内，Steve 2026-10-22 确认账号都是按量付费。官方没写数据在哪里处理，所以页面上不写「境内处理」 |
| C4-smartbi-redact | 智能报表分析发给模型前替换客户、门店、产品名称；替换失败就不发送 | live | #1096 上线（方案审计与执行，2026-10-22）。我独立核对：线上 8083 进程 17:37 启动，`common/llm_client.py` 里有 fail-closed（`EgressRedactionError`，失败时不发送）。名称替换只在 SmartBI insights 注册了 `RedactionScope` 的路径上生效，其余 Python 调用只用正则兜底 PII；**数字照常发送**，所以页面上不写「数字也脱敏」 |
| C4-assistant-redact | 工厂 AI 助手（Java）发给模型前同样替换名称、失败不发 | pending | origin/main 0407cf7c85：`WorkdeskOutputSummarizer` 没有脱敏，Java 侧也没有脱敏类，查询结果原样发给 DashScope。已转告「方案审计与执行」 |
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
