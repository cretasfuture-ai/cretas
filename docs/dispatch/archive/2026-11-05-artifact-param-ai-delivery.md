# Artifact 参数汇总与 AI 成本分摊

任务：ARTIFACT-PARAM-AI-DELIVERY-20261105。Owner：食品 DAG。
Base：91bfa2c9b42ac5e98ca67b02e0de6686339b5913。

复用原参数汇总分支的后端、Web 页面和去重测试，补齐历史报工归属冲突的保密边界。限制调用者的每个批次、报工、任务产品引用均必须能解析为同厂未删除对象；任一无权产品、失效引用或矛盾归属均从汇总和明细排除。

AI 成本分摊沿用现有画布的 WEIGHT/FIXED_RATIO 与输出 fixedCostShare，不新增业务执行入口。与既有 preview-only、revision 和发布校验保持一致。最终交付前补充冻结版本与目标验证结果。

独立风险复核已确认参数源码修复覆盖普通产品掩盖保密/失效来源的问题；最终 Java、真实 PostgreSQL、CI、官方发布与业务验收仍待完成，不以旧切片通过记录替代。

本切片不包含 SOP 附件迁移、物料自动替换、质量/返工或餐饮退役，避免捆绑其他 Owner 及未关闭的并发问题。原分支和 worktree 保留。
