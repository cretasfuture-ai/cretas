# Dispatch 完成记录 — 2026-08-25

## `ML-LIUSHANMEN-TRAY-AWARE-HARD-NEGATIVE-20260825` — `review complete`

- **实现**：VisionLab 新增默认关闭的 `training.isolated_missing_repeat`。只对任务级划分后进入 train、人工确认缺一个类别且只有一个互补类别框的单目标裁图重复采样；validation 永不复制，多框邻盘歧义和两类都缺的空框图不加权，声明缺失类别却又存在该类别框时直接失败。旧数字 `0/1` 与新字符串类别格式均受支持；权重策略进入不可变 dataset ID，默认值 `1` 保持既有 ID，代码合入不会自动触发重训。
- **真实数据验证**：累计 573 个有效人工样本在 repeat=2 时形成 `label-27e393962ffa`：463 个 unique train + 63 个训练副本，110 个 validation 原样保留；累计识别 81 个单类 isolated missing，其中 train 63、validation 18。人工标注、保护集、原图、既有模型均未改写。
- **候选裁决**：候选 `label-27e393962ffa-20260825-010837` 的 PT/ONNX parity mismatch 为 0，但 label-presence 从基线 104/110 回退到 103/110；白标和彩标各新增 1 个 FP，虽彩标少 1 个 FN，仍按零新增误报门禁在生产回放前拒绝。最后 epoch 复核结果相同，不存在 checkpoint 误选。生产回放未执行、自动部署关闭、生产 v8 SHA-256 保持 `bfd8a3adccbbe0bf5ccbe84eec205858204f05013c97e9e1cc6fb192a9b1f3f2`，生产写入为 0。
- **验证**：`B:\anaconda3\python.exe -m py_compile tools/vision-lab/vision_lab.py` 通过；`B:\anaconda3\python.exe -m pytest tools/vision-lab/tests -q` 为 264 passed、27 subtests passed；`git diff --check` 通过；PR #41 的 tracked-secret-scan 通过。
- **运行时收尾**：共享 SQLite `last_trained_dataset_id` 已恢复为 `label-c2f6d81d9c9c`，避免旧 exact-main 计划任务重复训练；生产模型登记 SHA 仍为 `bfd8a3ad...`。实现 scope 已释放；失败候选和不可变数据集仅保留为本地审计证据，不能授权部署。

## `FIX-LIUSHANMEN-PRESENCE-OWNERSHIP-20260825` — `review complete`

- **预检修复**：label-presence 不再把 padded crop 边缘的邻盘预测直接计为目标托盘 FP；人工审核框始终保留为目标托盘真值，只有人工真值中缺失类别的边缘预测才按 `pad_ratio=0.14` 近似排除并逐项审计。baseline/candidate 的 rule、ratio、normalized margin 任一缺失或不一致都会 fail closed；完整原图生产回放继续作为最终 ownership 真值。
- **类别平衡能力**：新增 `training.isolated_missing_repeat_by_class`，允许对白标/彩标缺失样本分别设置 1–10 倍，仅复制 task-level train 中人工确认、单互补框的 isolated missing；validation、模糊/排除、unjudgeable、多框歧义和受保护回放样本不复制。未配置时继承旧 scalar 并保持历史默认 dataset ID。
- **真实数据与候选**：累计 573 张唯一人工样本中，train 的缺白标/缺彩标为 54/9。类别平衡数据集 `label-d707c55a9594` 保留 unique train/val 463/110，只增加 18 个缺彩标训练副本。候选 `label-d707c55a9594-20260825-022454` 的 PT/ONNX mismatch=0；修正后的 presence 从 baseline 104/110 提升到 106/110，两类 FP 均为 0。
- **生产链回放裁决**：同一候选在 310 张生产运行时回放仍将受保护缺彩标照片 `29c7e4bb-b9f3-4407-8eae-1c25174102ac` 从 `MISSING_COLOR_LABEL` 判成 `CLEAR`，protected/independent 均为 6/7；19+77+200 共 296 张正常图全部零误报。门禁失败，候选 `NOT_DEPLOYED`；生产模型 SHA-256 保持 `bfd8a3adccbbe0bf5ccbe84eec205858204f05013c97e9e1cc6fb192a9b1f3f2`，生产写入为 0，原图/人工真值/保护集均未改写。
- **验证与下一步**：`py_compile`、VisionLab 全套测试和 `git diff --check` 通过。现有缺彩标真例不足，后续只从新照片中挖掘模型判 `CLEAR` 但存在缺彩标差异/不确定性的目标托盘交人工审核；不得把已暴露保护样本回灌训练，也不得推全量照片给审核员。实现 scope 已释放。

## `SOP-RAG-SYNC-20260825` — `merged`

- **工厂实现与合并**：PR #54 已合入 `af4024ae7100ae0a55df83e9fd1a8a6385b929d5`；仅更新现有工厂在线 SOP、唯一 `f006-production-full-chain-sop.md` 知识源、工厂确定性回答与 AI Assist 工厂 tab。口径覆盖 Workflow 当前配置/已开工历史/旧会话冲突、撤销重结后的真实停产门禁、分次入库及幂等、质检证据和处置审批、成品盘点/报废/可售批次，以及含税价和长度采购单位。
- **生产部署**：从 clean exact `origin/main` 通过 release preflight；142 个 production SmartBI migration 与 main 逐文件 SHA-256 一致，当前 main runner dry-run 确认 pending=0。Python 服务发布并健康，工厂 `/lsmsop/` 与共享 `/aiassist.html` 从 main 原子替换并保留备份；仅工厂 canonical source 原子更新 76→79 块，`.NEW=0`。
- **独立验收**：Python 目标测试 138 passed，GitHub tracked-secret-scan 与 Web Admin vitest/typecheck 均通过；三页线上/仓库 SHA-256 一致。工厂真实回答 7/7 仅引用工厂 source；餐饮真实回答 2/2 仅引用餐饮三个 registered source，餐饮页面/tab/source 与块创建时间保持 no-op，正式块分别为 87、250、176。
- **安全与收尾**：Python、Embedding、PostgreSQL、Redis 健康；生产 ERP 业务写入为 0，未触碰测试环境与 `.env`，保留发布备份和两个 worktree。工厂 scope 已释放，餐饮 scope 从未占用。

## `E2E-F006-PRODUCTION-POST-REPORT-20260824` — `merged`

- **完整历史链路**：真实 F006 UI 已覆盖原料/WIP/成品盘点及盘盈盘亏审批、`6+3+1` 分次与短收入库、全部合格/部分不合格/全部不合格质检、隔离/返工/复检、成品报废报损及销售/仓储不合格批次拦截；所有结果以独立 runKey 原始截图和 API/库存回读为准。
- **缺陷修复与部署**：`BUG-F006-FINISHED-GOODS-WASTE-READBACK-010` 已合入并发布；部署后重新登录回读证明申请单显示真实成品批次、产品、`10盒` 和历史申请人，库存守恒 `DEPLETED / 0`。Entity/Flyway/Repository 变更已通过对应迁移/JPA 门禁。
- **范围收尾**：既有仓储、质检、财务和价格普通流程不再重复写入；原生产浏览器和 RN 已退出，其他租户写入 `0`。真实历史素材已与后续专项分离并标记对应部署 SHA；本任务和 scope 锁释放。

## `E2E-F006-WORKFLOW-CURRENT-VERSION-20260824` — `merged`

- **产品验收**：已开始正式报工的计划 B 保持旧 Workflow；未报工计划 A/C 及 `IN_PROGRESS` 但无正式报工的计划 D 自动绑定最新当前配置。历史版本只列出确有已报工计划引用的版本并展示绑定计划；旧快照只读，恢复历史仅更新当前配置，无“另存独立版本”。
- **并发与安全**：独立双浏览器旧图提交返回明确 HTTP 409 `PRODUCT_PROCESS_WORKFLOW_CONFLICT`，刷新后无覆盖和无额外历史；相同图重试保持零写幂等。仓储主管真实退出/重新登录后页面及直接 API 均为 403，手工 `/snapshot` 在真实 Controller 契约中退役返回 404。
- **修复、测试与发布**：`BUG-F006-WORKFLOW-STALE-PUBLISHED-IDENTITY-011` 通过 PR #52 合入并部署 `035db9a84c2eb93aa25a1616792525d978b987e6`；Java `49/49`、真实 PostgreSQL 双提交顺序 `4/4`、Web `56/56`、typecheck/build 和部署后写入型 E2E 均通过。`92.433` 秒真实截图视频已交付；本任务和 scope 锁释放，不吸收工序去重/BOM backlog。

## `E2E-F006-PRODUCTION-GAP-CLOSURE-20260825` — `merged`

- **修复与部署**：`BUG-F006-BY-STOCK-REVERSAL-REPOST-STOP-012` 通过 PR #53 合入并部署 `89624e7562885e76cc4926a003bfa94fbc92807b`；唯一可信 Maven 生命周期通过 Java `58/58`。真实 upstream `47.100.235.168:10010`、PID `2148416`、启动时间 `09:13:05`、JAR mtime `09:12:58`、直连及公网 health 均独立验证。
- **当前部署后 E2E**：同一 F006 存货计划完成两轮正式报工、S1/S2 各 `1盒`、总监撤销 S2 后合法重结及最终 `COMPLETED / POSTED`。另一计划四项原料各 `2.01kg` 形成 `8.04kg` WIP，包装 `8kg→10盒`，剩余精确 `0.04kg` 由独立二次加工真实开工合法消耗。
- **超收原子性**：本 runKey 唯一订单生产报产 `10盒`；真实 UI 输入 `11盒` 被 HTTP 409 完整中文拒绝，刷新/API 证明待收 `10`、已收 `0`、SKU 批次 `17`、库存 `209盒`、可用 `154盒`、目标批次/过账流水均不变。随后合法实收 `10盒`，唯一新增成品批次、库存 `209→219`、可用 `154→164`，结单 `POSTED`。
- **视频、限制与收尾**：runKey `E2E-F006-PROD-GAPS-20260825-082131` 留存 `185` 张逐步真实截图、`37` 条 action、`111.033` 秒部署后生产视频和 `150` 秒明确标注的仓储质检历史视频。原始连续 WebM、Android 真机/OTA、原料成本归集和独立销售经理登录未伪称通过；其他租户写入 `0`。已实际退出生产页面并关闭浏览器；本任务和 scope 锁释放，独立 blocked/queued backlog 完整保留。

## `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001` — `merged`

- **裁定结论**：不需要再裁定一次 —— 产品真值仓库里已经有了，且四个面全部一致：① `CreateCustomerRequest` 只有 `name` 带 `@NotBlank`，源码注明来由「T10 / spec P2.1: contact fields are now optional per docx feedback (some customers do not have a designated contact person)」；② Web 新建/编辑表单只有 `name` / `status` 必填，由 `customerFormProgressive.source.spec.ts` 钉住（2026-08-12 按 Steve 要求把联系人/联系电话/收货地址改选填时同步改的）；③ Excel 导入逐行必填校验只有一条「客户名称不能为空」；④ 销售准入**不存在**要求客户资料补齐的门禁。
- **台账原描述过期**：ACTIVE 里那句「与原五项强制及销售单补全门禁冲突」不是待裁决的冲突，而是**过期描述**（本仓形态 B⁵：描述过期比没有描述更糟，它让下一个人跳过核实）。
- **新增闸**：`CustomerRequiredFieldsContractTest`（4 条，反射量注解结构而非源码文本），钉住「创建只有 name 必填 / 联系人·电话·地址·邮箱·账单地址·税号·执照号均非必填 / 编辑不强制任何字段」，并带阳性对照（字段确实存在，避免拼错字段名造成恒真式）。要改回必填是可以的，但必须同时改这道闸 —— 那时它是一次显式产品决定，而不是漂移。验收：`mvn -o -Dtest=CustomerRequiredFieldsContractTest test` → `Tests run: 4, Failures: 0`。
- **生产写入**：`0`，本项全程只读代码与配置，未修改任何生产客户。scope 锁释放。
