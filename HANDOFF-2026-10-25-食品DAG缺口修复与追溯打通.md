# 交接：食品 DAG 能力缺口修复 + 批次追溯打通（2026-10-25）

上一个 chat 做了三件事：对照「食品 DAG 工艺模型」核查能力缺口 → 修了一批已证实的错数和缺口并上线 → 把批次追溯从成品一路打通到首道原料。本文件是唯一交接件；细节去看下面列出的 PR、清单页和记忆文件。

## 一、现在线上是什么版本

- prod 后端 + web-admin = `origin/main` 的 **`4079f4268f`**（#1158 合并后的那次发布，蓝槽 10010 active）。下一次发布的 `--base-sha` 用它。
- 本轮 8 个 PR 全部合并、全部上线，都在运行中的 jar 里 grep 到了改动标记（#1147 的标记在上一版备份 jar 里为 0，阴性对照成立）。
- **App（RN）还没推 OTA**——Steve 明确说先别推。见第四节。

## 二、本轮合并并上线的 PR

| PR | 内容 | 线上验证 |
|---|---|---|
| #1145 | 效期：过期成品拒出货、过期原料拒投料、FEFO 按到期日排序、成品每日 02:05 置 EXPIRED（逐批隔离）。口径：到期日 < 东八区今天才算过期 | 标记在 jar |
| #1143 | 半成品余额统一一个公式（含盘点调整，不再扣成负数）；web 逐道录入的 Workflow 任务产量按有效行累加（App 路径本来就对） | 标记在 jar |
| #1140 | 成本差异报表不再用「实际×0.95」当标准，无独立标准返回 comparable=false；公开溯源去掉写死的「食品安全认证」 | 公开溯源实调：certificationInfo=null |
| #1141 | 插单：原料按真实 BOM+库存核对，设备/人员无数据源标「未验证」，任何未验证不判可行；时段按本厂查；条件 UPDATE 原子占用 | 标记在 jar |
| #1147 | 追溯补写：新边型 PRODUCTION_TO_WIP（生产批→在制库存批）、App 报工领料写边、成品对齐修复、来源未知不再伪造自环、回填扩展两段 | 回填真写 +51 边，其余类型/工厂 0 变化 |
| #1156 | 回填变成无副作用的一次性进程：lineage-backfill profile 下不开调度、不开 web、不连 MQTT/ISAPI、跑完按退出码退出 | 上线后 ShedLock 15 分钟内 17 次执行，调度没被误关 |
| #1157 | 闭包触发器补「祖先×后代」交叉组合（迁移 V20261103_81），并只增补齐历史闭包 | closure 631→1245（+614，与预演一致），edges 225 不变 |
| #1158 | 追溯深度上限统一 50（单一常量 `LineageGraphAssembler.MAX_DEPTH_CAP`）；**公开溯源只给成品+原料**（Steve 裁定） | 样例 10898 内部能追到 depth 19 的 CMR-20260831；公开响应含 CMR、0 处 WIP/工序批号/root id，depth 字段全 null |

另外清理过：16 个闲置的 Claude 会话已归档（PR 已合并或无 PR、10 月初以来没动的）。

## 三、怎么发布 / 回填（踩过的坑）

- 发布：干净的 `origin/main` detached worktree 里跑
  `./scripts/deploy/release-cretas.sh --phase deploy --base-sha <线上当前 SHA> --tests '<显式测试类名,逗号分隔>' --confirm-prod YES-PROD`
  - `--base-sha` 必须是**线上正在跑的那个 commit**，传 HEAD 会判成「没有改动」直接 none。线上版本看 `~/.cache/cretas/release-reports/` 最新一份 `deployed` 回执的 head。
  - `--tests` **只接受显式类名**，不认通配符。
  - 东京中转现在 SSH 不通，预热会 `skipped`，发布自动走本地 Maven，正常。
  - 判据：`DEPLOY_EXIT=0` 且日志里 `RELEASE_FINAL_STATUS` 恰好 1 次；然后 grep 运行中 jar 的 ASCII 标记 + 实调一次。
- 回填（已经跑过，一般不用再跑；新数据由在线写入负责）：
  `SPRING_PROFILES_ACTIVE` 用命令行参数 `--spring.profiles.active=pg-prod,lineage-backfill`，环境变量 `LINEAGE_BACKFILL_FACTORIES=F006,LIUSHANMEN LINEAGE_BACKFILL_DRY_RUN=true|false`，先 source `/www/wwwroot/cretas/.env.prod`。**报告写进 prod 的 `logs/cretas-backend.log`，不在 stdout**。上次用的脚本：scratchpad `deploy-verify/backfill.sh`、`counts.sh`（见第七节路径）。
- prod 只读 SQL：`sudo` 会吞掉 `PGOPTIONS`，要在会话里先 `-c "set default_transaction_read_only = on"`，并 `show` 一下确认是 on。
- 本机复现 CI `fast-contracts` 要 `-Xmx12g` 单 fork（与 CI 一致）；3g/6g 会 Java heap space OOM，读数作废。

## 四、还没做的（按优先级）

1. **App OTA（Steve 说先别推，等确认）**。积压的 RN 改动：
   - `CostVarianceReportScreen.tsx` / `CostReportScreen.tsx`：不可比较显示「无法比较」，空值不再显示 0.0%（#1140）
   - `UrgentInsertScreen.tsx` + `types/dispatcher.ts`：资源未验证 / 不可行 / 可行 四态（#1141）
   - `PublicTraceScreen.tsx` + `traceabilityApiClient.ts`：认证信息为空不渲染（#1140）
   - `LineageChainTimeline.tsx` + 中英文 `processing.json`：公开溯源原料分组「原料来源」、depth 可为 null（#1158）
   - 注意：`PublicTraceScreen` **在 App 里没有任何入口**——消费者扫码实际走哪个页面要先确认。
2. **需要 Steve 先定规则的缺口**（清单页第 04、06、11、13、14 条）：按图开工（建议「上游产出的料够了就解锁」而不是等任务完成）、配比容差、质检放行（半成品待检状态）、CCP 监控录入、工序间时限。
3. **跨客户领用半成品（清单第 16 条）**：先做一次数据库反例复现，确认是否真会发生再定修多大。
4. 本轮顺带发现、没修的（都已核实代码位置）：
   - OEE 良品数 = 产出 × 0.95（`ProductionReportServiceImpl` ~:1216），同一类假数
   - 公开溯源找不到工厂时回落写死「认证工厂」
   - 插单推送通知 `Long.valueOf(planId)` 对 UUID 恒抛异常，通知从没发出去过
   - 插单 `markSlotAsSelected` / `releaseSlot` 仍是 findById 直接改状态，绕过条件更新
   - 订单预留、`BatchConsumptionServiceImpl.adjustConsumption` 两条路径仍可在到期次日 00:00–02:05 用到过期批
   - App 路径 `WipInventoryServiceImpl` ~:1939 写边仍在业务事务里直接 save，不经 recorder（两份写法）
   - 重物化时边上的数量不随之更新（幂等键只认库存批 id）
   - 文员池化锚 `CLK-SEMI-*`、纯 SFI 中间道没有可挂的生产批，不写边
   - LIUSHANMEN 8-29 那批 45 个在制库存批的来源生产批无效，回填按「不猜」跳过
   - 开放的 #902 与已合并的 #1143 有语义冲突（它的测试不插行就断言产量 48），谁后合谁补测试

## 五、环境变化（这两天发生的，别按旧记忆来）

- **仓库已转 public**，CI 全部走 GitHub 托管机（#1149），自建 runner 全部退役；`fast-contracts` 单 fork 12g 约 10–14 分钟。#1149 之前触发的 PR 检查永远排队，要 merge main 重推。
- Cretasfuture 的 gh token 现在**能推 `.github/workflows/*`**（本轮 #1157 推过）。
- **F006 已于 10-24 下午重置为演示租户**，历史在 `backup_f006_20261024` schema。
- 阿里云主账号 AK：Steve 说已吊销（另一会话记录）。

## 六、核查依据（新 chat 想复查时看）

- 缺口清单页（21 条，含复核结果）：https://claude.ai/artifact/YEu512LokCjUBnWwpCKqWE
- Codex 那边的行业录音分析与六项对照：本机副本 `C:\Users\Steve\Documents\codex-mac-yong-20261024\`（原件在 MacBook，`ssh codex-mac`，`/Users/jietaoxie/Documents/Codex/2026-10-24/yong/outputs/`）
- 记忆：`project_food_dag_capability_audit_2026_10_24.md`、`feedback_public_trace_shows_raw_origin_not_process_steps.md`、`project_repo_public_ci_all_github_hosted_2026_10_25.md`

## 七、本轮留下的脚本

核对脚本已另存一份到 `C:\Users\Steve\Documents\codex-mac-yong-20261024\handoff-scripts\`（backfill.sh、counts.sh、markers.sh、public10898.sh、trace.sh、chain10898.sql）。原件在 scratchpad，会话结束可能被清：

`D:\Temp\claude\C--Users-Steve-my-prototype-logistics\9b76b3e9-ac1f-43f0-ade1-9edb18125971\scratchpad\`
- `deploy-verify\markers.sh`（运行中 jar 标记 + 备份 jar 阴性对照）、`counts.sh`（边/闭包计数）、`backfill.sh`（回填）、`public10898.sh`（公开溯源泄漏检查）、`chain10898.sql`
- `fc-speedup\abandoned-context-sharing.patch`：被 #1154 取代的「共用 Spring 上下文」半成品改动，留档不用
