# LIUSHANMEN 2026-09-02 汉堡四成品 → 一张产品族图：直接改库的记录

⛔ 这些是**只读审计记录**，不是 Flyway 迁移，不要再执行。owner 当日明确授权（「实在不行你也可以用 SSH 去修改数据库」）。

背景：工艺 197 v2（锚 美式汉堡饼）发布要求「该修订上有完整 ACTIVE BOM family」，而 BOM 草稿激活又要求「所钉工艺已是启用工艺」，
工作区绑调料还要求物料有成本价（14/24 味在 prod 无价）—— 把四个单成品 BOM 转成联产 family 在 API 上是死锁。
产品侧修法见同一 PR 的 `WorkflowBomSynchronizationService`（预检接受钉在目标修订上的完整 DRAFT family，发布时整体激活）。

| 文件 | 做了什么 | 没做什么 |
|---|---|---|
| `_01_seasoning_copy_into_family_drafts.sql` | 把旧 4 份 ACTIVE 配方的调料行复制到新 family 的 4 份草稿，节点键换成各线 `p:<line>:4` | 不编价（price_source 留 null）；API 已插的物料跳过 |
| `_02_bom_family_status_switch.sql` | 旧 4 份 ACTIVE → ARCHIVED；新 4 份 DRAFT → ACTIVE | 不删行；在途计划钉的 recipe id 仍能取到 |
| `_03_bom_family_is_current_flip.sql` | `is_current` 从旧移到新 | — |

实测读数（改后）：修订 315 上 ACTIVE 且同 family 的成员 4；调料 1/5/7/11 行；工艺 197 v2 PUBLISHED + 启用；小吃吧/汉堡排旧图已停，熟肉饼(192) 因在途计划 `PLAN-1788008667414-B141A9D8` 仍启用。
