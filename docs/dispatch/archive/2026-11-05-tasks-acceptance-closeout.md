# CLAUDE-TASKS-ACCEPTANCE-20261105：执行收尾

Owner：01a1a954/root。实现、必要验证、PR 合并、正式部署和受影响业务复测完成；代码与执行资源释放。实际业务代表/QA 签署仍待本人完成，不代签。此前阶段记录见 `2026-11-05-tasks-final-delivery.md`，其“待合并/部署”是历史状态，以本记录为准。

## 实现与交付

PR #1469 已正常 squash 合并，生产版本 `e976bdc0281ea99fae65e4db9213c825e27c09ec`；最终 feature head `dcb1a09dcf4c479b44693bb087b07f844414b24c` 与合并版本整树一致。原 #1459/#1461 关闭并由统一交付替代，未声称独立发布；已合旧 PR 未重做。

- D-S5-01：厂内物料名称和业务编号替代内部引用；未知或外厂引用不泄露标签。
- 自动采购 PostgreSQL 参数类型修复；取消父计划下的旧排程不再占线，保留真实 JPA 门禁。
- 缺料重排显示正式 Workflow/BOM、日产能、日量和单位依据；未知能力不承诺精确工期。漂移 409、锁后刷新、厂区及单位契约保持适用。
- 质量原料下拉改用本厂 AVAILABLE 六字段窄投影，保留原 DataScope；未扩大原仓储接口/库存 AI 权限，未改扫描器或测试基线。

## 验证与部署

- 最终官方 build/stage：77 显式类、83 XML、584 tests，0 fail / 0 error / 0 skip；包含真实 PostgreSQL 采购 1 项、Shortage JPA 8 项、质量契约 5 项、曾失败的数据工具权限 8 项。
- 最终 head CI run `42003719146` SUCCESS：fast 7247，0 fail / 0 error / 225 条件 skip；PG 312，0 fail / 0 error / 2 条件 skip。关键类已在实际日志定位。当前 full-suite SKIPPED，不把旧树全量冒充新树全量。
- Web 质量四套 18 项、重排三套 12 项与最终 6GB vue-tsc 通过，同内容证据按覆盖范围复用。
- 2026-11-05 正式统一入口从 clean exact origin/main 发布：`DEPLOY_EXIT=0`，`RELEASE_FINAL_STATUS=deployed` 恰好一次。Java/Web 均复用可信制品，build_count=0；green/10020 健康，Web HTTP 200、四方哈希一致。预热传输不可达只尝试一次，按官方缓存通道继续，无重复 Maven/Vite。
- JAR SHA-256：`533ad7a93d368b2e210e98564e1c312dd5466e9615e51c0e2647c48cf9e49b53`；Web index：`a7745e0e9ca77990feed1e9a0715e4ca5a478d32d17e1adcbefe6e4f00e09fb7`。

私有证据：`final-deploy-receipt.json`、`guarded-final-build-receipt.json`、`guarded-final-test-summary.json`。首轮 CI/缓存路径失败保留，未绕门禁；完整官方服务级回执已复用。

## 真实业务验收

唯一私有 `scenarios.csv`：**44/47 通过，0 失败，1 未测，2 原批准豁免**。历史同内容通过证据复用，原始失败和工具错误保留。

- S5：财务 UI 显示双方物料名称/编号；存在差异时“相符”不可选；实际选择“不符”与原因并确认，GET 为 MISMATCHED，可开票量和阴性对照正确。首票选择器错误后正式结算；第二张唯一补充票完成 UI，两票保留，无付款/库存/收货。
- S4-02/03/04/05/10：真实 UI 正式依据可见，不可行换线有原因；旧方案实际 409、状态不变；新方案单次应用，真实排程/动作/结果一致。服务成功后客户端超时，GET 恢复既有结果，无重放；未宣称覆盖所有后端部分失败。
- S4-11：同一测试订单由销售、生产、采购、财务、仓储、质量 UI 完成链路。唯一采购单、财务 OA 实际通过、单次重排、10kg 收货及附件上传/登记/确认成功。质量 UI 选中本次批次、可用量 10kg；普通原料默认 AVAILABLE，无人工来料放行待办，不冒充人工放行。缺口 0 后 UI 关闭，GET 为 CLOSED。
- 全链首轮质量 placeholder 工具错误后正式清理成功；续跑仅质量查看和关闭原事项，未重放采购/审批/重排/收货。最终 APS/生产计划取消、测试线 inactive，原 ghost 父/子不变。已确认 QA 库存 10kg、完成采购/收货与差异发票按实际 ID 留账，无 SQL 回滚、假取消、付款或无人管理长期调度。

私有证据：`postrelease-business-ledger.json`、`s4-final-ui-run.json`、`s4-full-ui-finish-journal.jsonl`、`s5-final-ui2-result.json` 及真实截图。接口没有请求 ID 的位置留空，不生成假 ID。

## 未完成项与资源

- S6-10 未测：实际业务代表/QA 签署缺失，签署包已准备且签名栏为空。S5-09/10 联产品成本保留原批准豁免，不算新实现能力；B03 默认仓库正式业务确认无签署。
- 历史真机与本轮模拟器证据分开，实体设备不再使用；本组模拟器、临时 PG 和发布锁均释放，未停其他服务。
- AVD 事件仍 HOLD：子执行者曾对既有 Pixel_9a 发出未带只读的失败 wipe 启动；原 userdata 无前置备份/哈希，保全和恢复未证实。现场停用保留，已告知用户；不得称为无损或已恢复。
- 主仓他人 detached/dirty 改动未动，旧无 `.git` 目录未写代码；worktree 和历史证据保留，不自行删除，不改其他 AI 配置。
