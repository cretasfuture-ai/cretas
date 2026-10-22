# S0/S3 实际验收续行与 S107 草稿处置

- Task: `ERP-S0S3-ACCEPTANCE-20261023`；Base `eb299396dc7c0a27ca22f24efe73112c2e130289`。
- Root/台账唯一写 Owner：`01a1523e-881c-7a92-b7ab-f4b8c5a552df`；原 QA：`01a15272-36e3-76c0-a6a1-977df6dba052`，独占手机、浏览器与 canonical CSV。
- Root 文件 scope 仅本归档和 `docs/dispatch/ACTIVE.md`；源码只读。独立数据库验证 `/root/s107_actual_db_readback` 只读生产；S0/S3 前提调查 `/root/s1_native_date_fix` 只读源码及证据。私有证据根 `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/`。
- 用户本轮已授权核实并处理两张测试草稿。精确对象及 BEFORE 存 `s107-draft-remediation-independent-20261023/`；仅在实时证明 F006、仍为可取消草稿且无收货/库存/财务影响后，由原 QA 从原采购界面正常取消。不得物理删除、清理授权/定时历史或改写原始 FAILED。随后独立 AFTER 核对目标状态、关联影响及阴性对照。
- S0-05/S0-06 原未测/失败保留；历史快照、真实单位/配方及真人耗时不得编造。已通过证据直接复用，只补独立可完成的缺项。
- S3-06 首选已有独立采购行部分收货，先取得当前岗位、余量、物权与版本证据，再按原场景判据由原 QA 执行。S107 两草稿禁止成为 S3 输入。S3-07/08 可独立只读预检；未释放任何额外库存写入或盘点/调拨事务。
- S2 原 Claude Owner 与既有精确 scope 门禁不变，不构成 S1/S0/S3 等待条件。不得接管采购/供应商/Orchestrator 或放宽共享组件边界。
- 验收：原 QA 真实动作/请求/截图及独立 SQL BEFORE/AFTER；canonical 原判据不改、原 FAIL 保留；`python scripts/ci/check_dispatch_active.py`、`git diff --check`。只有实际产品缺陷才登记源码 scope 并修复，不为文档或验证重跑产品全量/构建/发布。

## 当前事实

S1 已按原 QA 判据 10/10 通过，产品发布与验收绑定 e9d5fdc165；新主线包含他人安全修复，不推定同版本。此次 BEFORE 发现线上 Flyway 已到 74，运行制品需实时核实。S3 仍未签收，本页登记不等于实施或验收结果。

## 已取得的结果（2026-10-23）

原 QA 已从正常采购菜单各取消一次两张 F006 测试草稿，刷新详情均显示 CANCELLED。独立只读 SQL BEFORE/AFTER 确认订单及明细保留、对应两条取消审计、收货/库存/应付/付款/发票无新增，其他草稿阴性对照未变；原定时任务仍禁用且历史执行保留。证据：`s107-draft-remediation-original-qa-20261023/` 与 `s107-draft-remediation-independent-20261023/draft-after-receipt.json`。原 +2 对预期 +1 的 FAILED 不因取消而改绿。原 QA 本轮实际退出码 0，业务请求/事务/锁为 0。

运行身份限制：当前 active 为 10020/green，JAR SHA256 `d4537b3cbfdb7224b4d0ffd11ecd09c4bcb0d9d4587124b8f90aae4c9103c388`，未取得来源 commit 绑定；不能沿用 e9d5 的发布回执宣称当前制品相同。初版运行回执手填时间有误，保留原件并以 `runtime-identity-corrected.json` 的远端实测时间及 correction 记录替代。此次不为补来源而重新部署。

S0 仅补只读设备证据：已安装 code 16/version 1.0.5，不能独自证明当前 OTA 来源。S0-05 历史 g/box/case/多包装快照仍缺可归属数据；S0-06 真人操作/等待/改错耗时及正式单位计划依据未齐，保留原未测/失败。S2 原 Owner 的新入口精确领取信息仍未收到；续行请求已写交接目录，未声称对方收到或开工。

S3-06 已取得现有部分收货采购行的独立 BEFORE：订购 2kg/已收 1kg/剩余 1kg，仓管岗位有效；原 QA 启动正常 UI 再收 0.5kg 的功能验证，绑定原 QA 夹具，不称真实物流或 ROI。收货及阴性结果、独立 AFTER、原 QA 签收尚待实际完成；其他库存事务未释放。

## S3-06 原 QA 签收

`execution/s306-original-qa-20261023/final-signoff-20261023/receipt.json` 已签 `PASS_S3_06_ONLY`。真实仓管 UI 完成 0.5kg 部分收货，累计已收 1.5kg/待收 0.5kg；独立 SQL 确认新 0.5kg 公司物权批次及来源。超量、错料、供应商不符、重复确认由 UI 限制与四项 API 辅证分层核对，拒绝无额外收货/批次。只有 S3-06 一行更新，其余 74 行及原判据不变：15 通过/1 失败/59 未测，缺陷 18 closed/1 open；S0仍4通过/1失败/1未测，S1仍10/10。

本次自动生成未付应付 1.41；独立 SQL 原采购行单价 2.5/税率 13 与仓管 DTO 空价格分层说明，`price-evidence-correction.json` 更正旧“原价为空”表述且保留原件。供应商当前余额与对应 AP balance_after 一致，但缺完整 supplier BEFORE；精确收货实体审计也未发现，均不称已验。实际 JAR 来源 commit UNKNOWN 限制仍在。不把功能夹具金额称真实报价/成本/ROI。

原 QA 内层交互 runner 在资源已关闭后因 idle stdin 终止而 exit 1，外层会话正常 exit 0，两个事实分开保存。最终签收没有新生产请求，SAFE。

## S3-08 后续切片

独立前提核验已确认原 QA 历史夹具拥有 0.1kg AVAILABLE/COMPANY_OWNED 库存，未用/未预留，同厂原料仓与生产仓有效；实际 workflow 节点明确 1309 为独立审批人，描述中的 finance_manager 与节点配置不一致，保留差异且不改配置。原 QA 在 S3-06 安全退出后独占启动新调拨/发运/分次接收验证，只使用该独立夹具，排除本轮收货和 S107 两单。精确对象、范围与请求保护存 `coordination/original-qa-s308-activation-20261023.json`；尚未签收 S3-08。

S3-08 实际入口检查发现产品缺口：同厂现有生命周期只有审批后一次确认，没有独立发运与累计分次接收；原 QA 在创建调拨前停止，新业务写 0，独立 AFTER 确认夹具状态/数量/关联不变。`s308-original-qa-20261023/receipt.json` 与 `s308-independent-preflight-20261023/entry-failure-after-receipt.json` 为真实失败及零变化证据。未执行的三类负例不当作已拦截；原 QA 继续独占 canonical 记录。修复已单独登记 `ERP-S308-WAREHOUSE-DELIVERY-20261023`，新增显式仓内发运与累计收货，保留旧原子确认和跨厂行为；源码交付、门禁、发布、原 QA 复验分别取得后才收口。

Claude 新 C2 回执已只读审阅，仍未获精确 scope ACK 且两层 REQUIRES_NEW 存在扣减提交、外层失败的漏执行窗口，集成 HOLD。具体裁决落交接目录 `Root-C2回执裁决与S308迁移协调-20261023.md`；未推定对方收到/领取。候选迁移77与Claude75/76合入前再核顺序。该审阅不阻塞 S3 源码修复。

原 QA 已最终将 S3-08 签 FAIL，缺陷 `ERP-S3-TRANSFER-STAGES-001` OPEN；证据 `execution/s308-original-qa-final-signoff-20261023/receipt.json`。当前 canonical：15 通过/2 失败/58 未测；缺陷 18 closed/2 open。其他 74 行、原判据及 S3-06/S1通过证据不变。三项未执行阴性保留 NOT_EXECUTED；实际新业务写 0、浏览器/手机安全退出。首个源码 WIP 尚未通过交付审查，新增回归/JPA/PG仍必需，不能据旧测试绿或WIP commit称修复完成。

S0 手机正常启动入口已由原 QA 补测，外层会话退出0且资源安全释放，证据 `execution/s0-native-current-launcher-20261023/receipt.json`。安装版本code16/1.0.5；个人页页脚显示v1.0.0，关于入口仅“功能开发中…”。本次未重启App进程、更新或改登录/业务数据；实际生效OTA/runtime/source仍UNKNOWN，不能用页脚或“无可用更新”替代版本来源。S0原失败及未测不变。S308前后端候选仍在修复，尚未合并/发布/原QA复验。
