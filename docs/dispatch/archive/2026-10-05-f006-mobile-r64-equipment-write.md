# F006-MOBILE-R64-20261005：设备操作修复

Base SHA：`40ea81634ddff24b09da22bc56622f9e393fc44a`。Owner：root。状态以本 PR 合并结果为真值；本最终提交归档代码实现任务并释放其 scope，真机后续验收仍由 F006-MOBILE-CAMPAIGN-20261003 跟踪。

F006 手机设备管理员实际创建、编辑任务测试设备成功；切换维护状态返回400，客户端JSON状态参数与控制器要求的查询参数不一致。空名称创建返回400时界面没有提示。详情页把数据库ID显示为设备编号。

实现范围仅8个 RN 文件：equipmentApiClient、EquipmentManagementScreen、EquipmentDetailScreen、3个对应测试和2个 processing locale。状态请求改为查询参数；创建/编辑名称必填，错误可见并保留输入；过期 actor 响应不污染新会话；详情使用业务编号，缺失时明确未提供，停用显示停用。没有后端、Repository、Entity、权限或迁移改动。

目标测试3套69项通过；TypeScript exit0；独立只读源码审查P0/P1=0，diff check通过。原真机失败证据保留，源代码审查和本地测试不等于修复后手机验收。

本批不重启Java/Python、不改变Mini连接或配置、不发布公共OTA。合并后构建独立 F006 手机测试包，继续实际点击、F006限定数据库与操作日志回读。其他租户业务请求0，历史设备没有写入；具体私有证据保留于本机测试目录，未把工厂数据写入仓库。
