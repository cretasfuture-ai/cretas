# RECEIVING-SCOPE-20261018

Owner root；Base `c847f54b049676480e6a58d21d909d1aa280892a`；branch `codex/production-receiving-row-scope-20261018`。

实现及隔离验收完成，最终合并状态以对应PR和私有交付回执为准。范围仅收货任务规范GET、三类来源可见性、采购分页/精确查询和相关测试；没有Entity、迁移、生产或收货业务写入。

实际页面SELF3、下属6、同部门6，角标和列表一致、生产经理只读；五种隐藏筛选零记录，跨厂和隐藏来源拒绝。冻结运行时本人精确查询各1、隐藏/不存在均空；ALL9。临时夹具15行清理、组织和策略恢复；19表原业务逐行一致，其他厂和原模块/平台权限不变。见[脱敏证据映射](../../acceptance/receiving-task-row-scope-ui.md)。

本地复用29项兼容测试，新增5项Contract和1项Service实跑通过；精确查询调整后只补受影响Service及真实JPA2项，三条新Repository方法实际启动执行。安全终审无源码阻断；最终候选仍须必需PR CI通过后手动合并，禁止auto merge。生产启用、真机及真实用户签署单列。
