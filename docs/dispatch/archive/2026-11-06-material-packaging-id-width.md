# MATERIAL-PACKAGING-ID-WIDTH-20261106

Base 0b525443584073548bdfe218759d0d01a7310f08；Owner food/root；执行merge_executor；WT C:/Users/Steve/cretas-food-material-packaging-20261106 / codex/material-packaging-id-width-20261106。真实F006正常创建RMT_uuid共40字符，MaterialPackagingHierarchy旧material_type_id varchar36导致PUT包装层级409/SQL22001，correlation d8123edd-d46c-4e51-b5d0-69196141dc75。5个自有master-data对象保留，未创建PO/合并，独立状态JSON只含自己IDs。

唯一允许写范围：backend/java/cretas-api/src/main/java/com/cretas/aims/entity/MaterialPackagingHierarchy.java中的materialTypeId长度；src/main/resources/db/flyway/V20261105_95__widen_material_packaging_material_type_id.sql；相应MaterialPackagingHierarchy真实JPA startup与PG migration/long-id回归测试（新文件后缀ContractTest或StartupGuardTest，主源码读取闸命名匹配CI）。不要改MaterialPackagingSpecs实体/正常ID生成器/前端、其他Entity/Repo方法、Supplier/Material合并、SOP、CI与发布脚本、权限矩阵、ACTIVE或本archive；需要额外scope先回报。

修复要求：层级引用宽度与真实RawMaterialType.id/现有新包装spec的191一致，迁移只兼容扩宽，不改任何ID/业务行/租户数据，不删FK/unique/check/软删。证明从旧varchar36真实PG schema执行该migration后，正常40字符RMT来源可通过JPA持久化并读回；重复material unique、FK与原层级paired CHECK仍有效，事务失败不得留半层级/spec。H2/mock不能替代真实PG；至少真实JPA Context成功，CI JPA gate必要通过后才合。

唯一号预留V20261105_95，晚于当前线上94、早于普通param case的V20261106_1；正式发布前仍fresh main/线上/各组冻结唯一号核验，不启out-of-order。旧binary读新宽度兼容，无新enum/写开关，两阶段扩宽不需要创建新业务事实。root保留最终diff、风险、官方制品/CI、commit/push/PR/合并/生产与同F006 QA续验，执行者不生产请求/不提交/不开PR/不派子代理。私有测试port57049、独立data/DB，仅该Owner；禁止其他PG、Docker、WSL、JVM进程干扰，finally停自有PG。

最终Base更新342dc816fd24deecb85ea0cdbe60a0ae9247daf4，已依次精确汇合1487/1490正式main，保留SOP prod启用配置及所有已发布物料/Supplier代码。唯一新增CI范围由root维护java-gate-pr.yml的PG显式选择器：完整保留最新main前缀（含Material/Supplier两项）后仅追加MaterialPackagingHierarchyIdWidthPostgresContractTest；与审计后续union需保留双方新增项，不覆盖/缩减。

执行者初轮回执有两处强断言尚未实际覆盖，root已直接补测试E23EA911：新JPA写40字行返回真实id，legacy@Column36由该id实际hydrate完整40字；两表用DataSourceTransactionManager/TransactionTemplate让23505异常传播触发Spring回滚。5s锁超时/55P03及36保持→释放后191、旧36行/spec快照、独立23505/23514/23503、二次migration0均保留；独立风险最终clear，无P0/P1。最终7类17/0/0/0于main4e6组合真实PG通过，日志material-packaging-root-final-target.log，57049 finally stop0。后续汇合1490仅既有SOP配置/前端/Contract，以上测试字节未变；正式7类build/stage会在最终组合再门禁。

Scope最终冻结/本行已归档；仍待官方制品、最新head必需CI/JPA gate、PR/合并、生产迁移95及同5个F006对象业务续验，不把旧PG通过当新正式交付。全程outOfOrder=false、无现有ID/业务行修写，事务5s锁超时不可绕过；生产发布前重核有序94→95→未来V20261106_1与实时锁/长事务。
