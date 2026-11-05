# Artifact 参数汇总与 AI 成本分摊

任务：ARTIFACT-PARAM-AI-DELIVERY-20261105。Owner：食品 DAG。
Base：91bfa2c9b42ac5e98ca67b02e0de6686339b5913。

复用原参数汇总分支的后端、Web 页面和去重测试，补齐历史报工归属冲突的保密边界。限制调用者的每个批次、报工、任务产品引用均必须能解析为同厂未删除对象；任一无权产品、失效引用或矛盾归属均从汇总和明细排除。

AI 成本分摊沿用现有画布的 WEIGHT/FIXED_RATIO 与输出 fixedCostShare，不新增业务执行入口。与既有 preview-only、revision 和发布校验保持一致。最终交付前补充冻结版本与目标验证结果。

独立风险复核已确认参数源码修复覆盖普通产品掩盖保密/失效来源的问题；最终 Java、真实 PostgreSQL、CI、官方发布与业务验收仍待完成，不以旧切片通过记录替代。

本切片不包含 SOP 附件迁移、物料自动替换、质量/返工或餐饮退役，避免捆绑其他 Owner 及未关闭的并发问题。原分支和 worktree 保留。

## 冻结交付证据

产品及测试冻结版本：15f53c0147434e7a3f617094a31537a49cb555f4。后续本提交仅归档 dispatch，不改已验证产品树。

- 官方 16 类选择器实际运行 164 项，0 failure / 0 error / 0 skipped。参数真实 Spring 16 项、Repository Context 1 项及 runtime compiler 32 项包含在内。
- 最终 Repository 查询另在私有 PostgreSQL 实际执行通过 1/0/0/0，断言数据库元数据为 PostgreSQL；结束 OWNED_POSTGRES_STOP_EXIT=0。未用 H2 充当 PostgreSQL。
- Web 相关 43 项通过；最后 nullable patch 调整后的 20 项通过，类型检查退出 0；重叠项不相加。官方 Web 制品构建成功。
- 独立安全复核覆盖多来源归属 P1、显式份额漏 method 的静默降级 P1、fallback/null parity P2；最终修复通过源码复核。
- 官方 build/stage 回执 cretas-1793881145-124.json：exit 0，FINAL=built 恰一次，deploy_mode=none。JAR SHA256 cbf9baa455a38bbf7afd3a06dc7c33cd3177c3c93676e9d8ab0a92d9bca3d5f4。Java tree 6d2186bcb391ffa17d9995b3cf13d266efc0efb8，Web tree 1677a18337295a2be8cce747b5056915eecc80a2。
- PR #1481 已提交推送。此记录为合并前冻结证据：最终 CI、合并、正式部署及 F006 业务验收各按其真实回执记录，当前不宣称完成。

原子构建完整结束后归档本切片 scope；后续 main/生产窗口由协调状态与真实锁管理。SOP/物料及其余 Artifact 未完项继续独立执行。

补充验证：最终产品树上的 ProcessSheetServiceImplTest 实跑45/0/0/0，覆盖工序表多报告载体去重。CI head657694a2a7的fast-contracts为7266项、仅JavaPrGateTieringContractTest固定PG前缀断言失败；PG与Web均成功。修复仅把新增PG查询测试从列表开头移到末尾，保留原有选择器与断言；该分层合同4/0/0/0复验通过。产品/测试目录无改动，Java/Web制品hash继续复用，最终head CI仍须完成。
