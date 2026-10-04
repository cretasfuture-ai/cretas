# F006-MOBILE-R9-TIME-STATS-20261004

状态 in-progress；Owner root/Codex；执行 f006_mobile_r2_nav；Base d0db852ef565c0ca688f0e9e5586b9aef59299dd；WT C:/Users/Steve/cretas-f006-mobile-r9-20261004；branch codex/f006-mobile-r9-time-stats-20261004。

证据：F006 hr_admin Oct1-4部门接口返回1020h/23workers/185clockIns，真实workers为空；只读SQL以F006/deleted_at NULL/clockInTime半开日期限定得到0记录/0员工/0分钟。服务getDailyStatsRange未读库、date-seeded Random伪造每日统计，weekly/monthly/yearly/department顶层等继承。createEmptyDepartmentStats实际是零值；三个createMock*无活跃调用，不能误称500h为活跃fallback。workType/productivity小时乘10是估算，不能称实际产量。工资真接口null/工资单0。

唯一代码scope：backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/TimeStatsServiceImpl.java；新增backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/TimeStatsServiceImplContractTest.java。不改Repository/Entity/DTO/controller/其他Service/权限映射/迁移/共享helper/pom或前端。复用既有findByFactoryIdAndClockDateBetween/countActiveUsers。实际record按factory/notDeleted/clockInTime非null与半开window二滤；日期按clockInTime.toLocalDate以和查询契约一致，不把不匹配clockDate带进另日。

范围读取一次真实记录，逐日聚合真实工时/打卡/员工/迟到早退。范围activeWorkers为全范围去重，regular/overtime按实际分钟与原标准日规则，不固定85/15%，不用top10取全厂。明确已有attendance/工时利用率口径与null边界，不用88.5、不把估算产量填实值。无生产来源workType/productivity totalOutput/每人每小时产量nullable不可用，空denominator指数未知不能1。空departmentMap为空，不伪造全体员工样本；删除三个未调用mock与date Random。无脑拼接fallback、假工资、权限扩大禁止。

临时WIP扩容理由：R6前端13文件、R4质量8文件与本两Java文件无重叠，独立worktree/target/生成物，修复后台假数据可与UI纠错并行缩短等待；当前3代码Owner，仍小于项目临时4。Maven在本WT单一worker顺序执行，共享.m2仅正常缓存；不共享target/node_modules，不装依赖。不触R3/R2源码和root发布资源。回执后立刻释放本执行并发槽位，root独立审查与最后目标验证/合并/发布。

目标验证：新TimeStatsServiceImplContractTest与GateSelectorCoverageContractTest；行为对照必须包含空范围0事实/null推导、两人多日真实分钟/regular/overtime/去重与daily→range、异厂/null行/softdelete/日期外/错clockDate与窗口边界、department无样本空Map、topN截断不改变汇总、月/周/年复用真实范围、无真实产出不造10单位/88.5。真实Mockito Service方法行为，不能只contains。JDK C:/Program Files/Zulu/zulu-21，Maven C:/Users/Steve/tools/apache-maven-3.9.9/bin/mvn.cmd；-o -B test -Dtest=TimeStatsServiceImplContractTest,GateSelectorCoverageContractTest -Dcretas.testIncludes=**/*.java；MAVEN_OPTS -Xmx3g -XX:ActiveProcessorCount=2，test argLine -Xmx1g -XX:ActiveProcessorCount=2 -XX:+EnableDynamicAgentLoading -Xshare:off。日志私有r9-time-stats-*.json/log。无Repository/JPA query改动不新增查询启动门禁。

禁止Gitcommit/push/PR/台账写、生产DB/业务HTTP/服务器/设备/runner、任何依赖安装/删除/链接/孙代理。非scope需求回root，由root裁决。回执含task/Base/实际文件/命令结果/阻塞/下一动作。不把单测当线上真实数据验收。

源码终审后的里程碑：真实范围/工人按clockInTime天数/同厂元数据Map与nullable趋势、JSON/CSV未知已闭合；11 Service行为+4选择器=15项F0/E0/S0，root只读复核源码。当前只保存本地，不表示合入、发布或线上数据回读。后续将同步main报表/班次保护，再在独立R11 Controller权限scope完成后统一目标验证；cleanup/recalculate生命周期未改，不能宣称全部写接口已审完。

候选归档：root 已合入本地 fresh origin/main 88de770f5d，独立权限与导出终审 P0=0/P1=0，最终五类160项F0/E0/S0通过。四源文件 scope 冻结并从在飞台账移除；尚待可信制品 build/stage、PR CI、合并、生产发布和 F006 数据/手机回读，不声明这些阶段已完成。联合发布 Base 采用 R9 登记 d0db852ef565c0ca688f0e9e5586b9aef59299dd；R11 原实现 Base 27756a50e702fb2fffcdf487c11e1c2ea4e68611保留。
