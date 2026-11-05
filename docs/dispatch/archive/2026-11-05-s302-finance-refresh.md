# S302 财务报废审批刷新权限

任务 TASKS-S302-FINANCE-REFRESH-20261105，Owner 01a1a954/root，Base c81d53bfb7d18406fefcc6ba96638f6679ae1b9c。

真实财务审批已提交成功，但页面同时请求仅供质量新建申请使用的成品候选，财务没有该权限，导致 403 和审批后旧状态残留。仅让质量写角色加载候选；财务仍使用既有处置列表/明细及审批接口，不扩展权限、屏蔽错误或改变库存守卫。

范围为 quality/disposals/list.vue 与 list.approvalButtons.integration.test.ts。两套行为测试13项通过，覆盖带成品上下文的财务审批刷新至 APPROVED、受限候选零请求及既有质量角色；vue-tsc -b exit0。Java/RN 内容未变，复用已冻结通过证据，不重复全量。

官方 Web build、CI、合并和部署各以真实回执为准。此候选归档在合入 main 时生效，代码 scope 随合并释放；后续父任务对已批准处置32只读复测，禁止重复报废/批准或回滚已确认库存。财务 UI 工具详情弹层超时及权限错误原证据保留。
