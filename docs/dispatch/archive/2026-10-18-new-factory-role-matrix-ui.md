# ROLE-MATRIX-UI-20261018

Owner root；Base `019c887ccfd6a6a73c196afd0af5db4672679e65`。五个现有主管岗位真实UI矩阵完成，验收映射及边界见[清单](../../acceptance/new-factory-role-matrix-ui.md)。只修当前有效权限快照的前后端接线，未改角色政策、全局权限、Entity/Repository或迁移，未访问生产。

本地Java27项、相关前端测试、类型检查和构建通过；原实现及餐饮缺键回归有实际断言失败的红→绿证据；权限独立终审SCOPED PASS。最终冻结后由PR同head必需CI背书并手动合并，交付回执保存在私有证据目录。

原有业务记录逐行不变，相关其他工厂指纹不变。只保留明确标注的三个测试账号、采购草稿、物料类型和待执行生产计划；未触发收货、报工、结算或资金重复扣减。liveness/readiness通过，AI预热503另记，不扩大修复。

实现及目标验证结束，归档本任务并释放scope；最终PR/CI/merge状态以GitHub和私有交付回执为准。其他ACTIVE任务不变。
