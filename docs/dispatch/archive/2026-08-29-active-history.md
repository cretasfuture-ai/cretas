# 2026-08-29 Active History

- `BUG-LIUSHANMEN-CLERK-REWORK-FLAG-20260829` — `review`（待 PR/CI、exact-main Java 发布与生产回读）— Owner: `/root` — Base SHA `ef6fb0802ced92172ffa9470d75d0066472f7d59`。LIUSHANMEN 汉堡线生产验收确认 Web「逐道录入」多产出虽然冻结 Workflow 节点已带 `data.isRework=true`，但 clerk SFI 入账仍写成普通半成品。本批把冻结节点的回料角色投影到逐道录入配置，经服务端校验传到 SFI；客户端 `rework` 字段只读且单/多产出均由服务端覆盖，既有回料行 sticky true，普通半成品保持 false，多产出成本反向重分摊也保留该标记。不改 Entity、Repository、Flyway。目标验证两组共 104 tests 全部通过（64 + 40，failures/errors 均 0），`git diff --check` 通过；生产精确回料行修正、下一批可领用与 16 格纯度矩阵在合并发布后继续实测。
