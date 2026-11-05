# 原安全链源码交付与发布前置

Owner：01a1a954-e8fa-7a10-8eda-3f5ba0872102。2026-11-05。

## 已合并源码

- #1451：`1fd507de1e3dee2fd7482a8d617c4a512a4dd278`。
- #1457：`0b2bdd6b8844ff865f898bcea1cea763d6442d76`；Unicode 角色边界与注册写入保护。
- #1438：最终 head `bae4338bf60c42526935fa3eb5cee59fbbe4d1fb`，合并 `e40ee26673ae6df68f503aecc0664f903186dd9b`；平台授权分析、单账号限流、真实 PG 授权迁移及内部 no-capture。

`SEC-PLAN-AUDIT-1438-20261105` 的源码 scope 归档并释放；生产发布继续由 `SEC-PLAN-AUDIT-MAIN-FREEZE-20261105` 保持唯一 Owner，不能把本归档当作部署完成。

## 验证

- 最终 Java 内容树全量：[41978228303](https://github.com/cretasfuture-ai/cretas/actions/runs/41978228303)，19407总计、19115通过、292条件跳过、0失败/错误。实际测量 merge tree 与冻结候选 `894e0970531ed73f6ab4aaaab4806e94c319c1fd` 相同；相关9类78项全部实际通过。
- Python 随机 set-order fixture 改为逐项覆盖29个重分类意图，保留退休拒绝及工厂权限断言；整文件42项通过。
- 该 Python-only 测试变更未改变 Java 内容树；复用上述全量证据，当前 head 的 [41985007055](https://github.com/cretasfuture-ai/cretas/actions/runs/41985007055) fast 与 PG 均成功，full 按标签条件跳过。当前 Python/Web/RN/其他必需 CI 均成功，不把跳过说成执行。
- 统一 build/stage：34类286项、0失败/错误/跳过，JAR SHA256 `e2d4f7dcd3033a1f6bf7f466dee4faa8be9170481e60b5e61e162930965fb301`，仅预上传。Java/Web 内容树可复用；测试与源码变化按组件分别验证。

## 发布前置与独立清理

发布前发现主线 Python lifespan 仍会启动餐饮预约模拟和默认开启的餐饮 ETL。用户已明确删除餐饮范围，同时发现开关可启用的平台订单/菜单同步和外部餐饮基准刷新，因此先移除这四类餐饮后台入口及其退出清理，再进行官方 Python→Java/Web 发布，避免重启恢复餐饮任务。

本批删除11个生命周期语句，代码变换核对其余生命周期 AST 完全保留；最终新增测试10项实际通过，并复用同源码22项no-capture/secrecy通过证据，覆盖四类writer改名/重新导入负向、真实lifespan创建并取消共享缓存任务、默认工厂API注册及既有no-capture/secrecy。测试替换外部I/O，不连接业务库、不启动真实定时写入。

完整 Java/Python/Web/RN 餐饮专属实现清理、工厂供货与历史评测复用迁出仍在独立分支实施，另交 PR。生产数据、已应用迁移与历史评测记录不删除。本文件不表示生产部署、线上餐饮全清或业务验收已经完成。
