# P18 跨日经营指标与质检口径复用验收

任务P18-CROSS-DAY-METRICS-20261017，Owner root，Base 5b4ba7033874eb61839fd148cb391e2070a9ea74。用户“继续”，承接多SKU追溯/经营分析主线，沿用独立分支和新厂FOOD_3101_001本地克隆。scope仅本档案/ACTIVE和代码外验收脚本、回执；未发现产品缺口前不改现有公式或开发新指标。不push/PR/合并/部署，不重复审批或出货。

复用已有FactoryActualProductionMetrics、指标中心、详情抽屉、日期筛选、recompute、versions与完工批次入口。先以原四个批次14/15/16/17核对：完工日均为2026-10-16，合格净重共36kg、实际投入40kg、不良净重4kg，完整成本501.32元；对应出成率90%、生产良品率90%、单位成本13.9256元/kg。17批抽检FAIL的40%是样本口径，未覆盖生产的20合格盒/2不良盒；17日出货事实不把16日完工的生产成本移期。

目标：16日四指标正常值、17日无完工四指标NO_DATA而非0或旧值、16-17合并窗口仍为同一批生产总数；用真实UI在16日强制重算完整成本，PG证明同期间恰新增一条501.32快照且旧快照不变；17日NO_DATA强制重算不产生快照、不借历史值；生产/库存版本、出货单、应收与质检前后不变。最后留在16日经营指标/成本详情供用户体验。

初步HTTP/PG基线通过：新厂账号id2/factory_super_admin；三个窗口的四指标均符合上述预期，来源含actual-flow-v2。baseline.json包含四批业务与版本行逐值基线；位于`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p18/`。未运行其他工厂写入或重建样本。

实际UI重算16日完整成本已通过：501.32，旧快照逐值不变，目标期间恰新增一行，after-recompute.json为PASS；17日NO_DATA强制重算提示“暂无业务数据，未生成新的计算快照”。后续脚本曾把额外GET不同窗口触发的正常缓存刷新也误算为重复手工重算，断言失败；已将空期阴性阶段改为只GET空期，不再次触发正向窗口缓存，保留第一阶段精确重算回执，并单独记录其后正常追加的同值缓存快照。

真实UI发现产品缺口，scope扩至现有IndicatorController.getVersions、Web indicator API/IndicatorDetailDrawer及对应测试：选择17日时历史接口重叠查询混入整月501.32，且前端page=0起、后端1起，实际流又取200条而分页器每页10，导致后续页重复/错位。复用现有本厂/指标重叠查询，在分页前增加可选exactPeriod过滤（默认仍保留旧overlap合同）；实际流详情明确选择精确期间、传正确页码和每页数量，旧口径同期间快照保留。无新增Entity/Repository方法或迁移。

修复实现：可选exactPeriod要求完整有效起止日期，沿用现有租户/指标overlap查询，在total与分页前筛选相同起止日期；未传参数保持旧HTTP语义。实际流四指标抽屉发送exactPeriod=true，page从1起、size=10；返回不同期间时明确报错，不静默混用月值。相同期间旧口径快照仍在表格，图表明确仅使用本页当前口径，空期显示“该统计期间暂无计算记录”。未改公式、Entity、Repository、SQL/JPQL或迁移。

先红后绿：旧P17编译类执行新增HTTP测试时，精确期望total=2却得3，默认overlap对照通过（Java11中1失败、0错误）；旧Web有2条失败（页码/数量/期间请求和错期响应），API透传有1失败。实现后Web四个目标文件46项通过。最终Java全主源码编译及IndicatorControllerTest 12项通过，Web Vite构建通过；未宣称完整后端单测或CI通过。

本地运行与验收：仅重载自有10064进程到P18独立classes（PID62480），PG克隆和31764/18096保持原样，无时钟修改。业务health与Web均HTTP200；第一次50秒就绪探测仍在启动，后续正常完成，无重启重试。既有本地mail健康限制不作本批全部健康通过。

真实HTTP/PG：新厂账号FOOD_3101_001/user2成功。16日完整成本精确历史8行与PG逐ID相符，page1/page2每页1条读回不同且排序一致的记录；默认overlap仍17行，兼容旧调用。17日精确历史0行；缺日期、缺结束日期、逆序期间各400，跨工厂F001访问403。history-api.json为PASS。未修改查询与表结构。

真实UI：16日四卡90%/90%/13.93元每公斤/501.32元，完工表包含原四批及盒/公斤单位；成本详情表仅16日，旧250.66快照仍可查看。正常缓存读取后精确记录增加至9条，未伪造数据以触发十条UI分页；实际分页用上述HTTP两页对照，挂载组件测试覆盖每页10条与第2页传参。17日成本详情显示“暂无业务数据”“该统计期间暂无计算记录”，不混入501.32或250.66。最终恢复16日成本详情，截图与AX已保存，浏览器错误0。

after-empty.json最终PG回读确认生产/库存版本/出货/应收/质检与基线逐值相同，旧指标快照不变；没有重复审批、出货或新增业务批次。16日手工重算恰新增一条、17日NO_DATA不落快照的既有本轮真实UI证据直接复用，计算实现未改；后续正常GET允许追加同值缓存快照，不能误报为重复手工重算。

复现命令与证据：私有目录`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p18/`中build_java.py red/final（独立derived POM，最终已移除worktree临时POM）、check_history.py、check_metrics.py after-empty、source-freeze.json、java-red/final.log、web-final.log、web-build.log、history-api.json、period16-dashboard/period16-cost-final/period17-no-data-final的txt/png。Web命令为`npm test -- src/views/indicator-center/__tests__ src/api/__tests__/indicatorHistory.spec.ts`及`npm run build`，退出码均0。源码与测试冻结六文件SHA256，文档后补不重复构建。

交付范围：独立本地分支待用户体验；本批无push、PR、合并、部署、生产访问或新迁移，不将本地验收称为生产验收。
