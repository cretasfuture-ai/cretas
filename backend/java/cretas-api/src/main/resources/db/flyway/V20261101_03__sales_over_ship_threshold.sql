-- V20261101_03: 销售发货【超发上限】接入阈值中心
--
-- ⚠️ 本文件原为 V20261101_02, 因与并行分支的 V20261101_02__semi_finished_inventory_is_rework.sql
--    撞号而改号。撞号发生在我做过撞号检查【之后】—— 检查当时是对的, 但保质期只有几十分钟。
--    ⇒ 教训: Flyway 撞号 git 一个字都不报(文件名不同), 只有 FlywayVersionUniquenessTest 会红。
--      开 PR 前必须 rebase 到最新 main 再跑一次那个测试, ⛔ 不能引用开工时的检查结果。
--
-- ## 补的是什么洞 (2026-08-28 生产实测, F006)
--   创建发货单时有一道 0% 硬闸:
--     SalesServiceImpl.createDelivery → 「发货数量超过订单行剩余可安排数量」409
--   但仓管在 warehouseConfirmDelivery 覆盖【实发数量】时只挡负数, **没有任何上限**:
--     SalesServiceImpl:3455-3473  →  if (actual.signum() < 0) throw ...   ← 只有这一条
--
--   实测走通过一次「订 2 发 5」: 库存真扣 5 件, 货真的出门, 订单还被置为 COMPLETED。
--   采购侧早就有 overReceiveRate + purchase_exceptions 两样, 销售侧两样都没有。
--
-- ## 为什么落在 factory_thresholds 而不是新建一张表
--   这张表就是为「工厂级业务阈值」造的, 已被 13+ 个 service 使用, 且自带
--   per-factory → 全局 '*' → 代码 fallback 三级梯子、min/max 校验、单位、
--   默认值(供 reset-to-default)、enabled 开关、乐观锁 version、软删。
--   ⛔ 不要再造第四套配置存储 —— 同一件事有两份, 它一定会漂。
--
-- ## 值的语义 (⚠️ 与 OverReceiveLimit 必须读成同一套, 否则同一个 0 在两条链上含义相反)
--   没有这一行 / enabled=false  → **不限制** (不做上限校验)
--   0                            → 一件都不许超发 (**这是一个真实的限制, 不是「没配」**)
--   0.10                         → 实发最多是计划的 110%
--
--   值是【小数比率】, 与采购侧 cretas.purchase.over-receive-rate 同一约定。⛔ 不是百分数。
--
-- ## 为什么默认给 0.10 而不是「不限制」, 也不是 0
--   · 给 0 → 重演采购侧那个缺陷: 2026-08-25 之前 @Value 默认 :0.00 让「没人配过」
--            和「限制 0%」不可区分, 生产上按 0% 把货堵在门口。⛔ 绝不 seed 0。
--   · 给「不限制」(不 seed 这一行) → owner 明确要求「一定要有一个配置限制」, 不表态不满足要求。
--   · 给 0.10 → 正常确认(实发 == 计划)溢出 0%, 不受影响; 只有想多发 10% 以上才会被拦。
--     全厂存量实测只有 1 条超发行(150%, 且是验收过程中我自己造的), 上线不会拦住在飞业务。
--
--   ⇒ 这是**默认值**, 不是硬编码: 工厂可在「系统设置 → 业务阈值配置 → 销售」按需调整,
--     也可以整行 enabled=false 退回不限制。
--
-- ## 范围
--   只插 1 行全局默认 (factory_id = '*')。⛔ 不给具体工厂插 per-factory 行 ——
--   「没有 per-factory 行」正好表达「跟随全局默认」, 显式写入反而让日后改全局默认失效。
--
-- ## 幂等
--   partial unique index (factory_id, threshold_key) WHERE deleted_at IS NULL
--   (见 V20260823_01__thresholds_hub.sql:44-46) ⇒ ON CONFLICT DO NOTHING 重跑空跑。
--   若有人已手工建过这一行, 本迁移不覆盖它的 threshold_value。
--
-- ## ⚠️ 上线顺序
--   本迁移写入 category='SALES', 而 FactoryThreshold.category 是 @Enumerated(STRING)。
--   **必须与 ThresholdCategory 枚举补齐 SALES 的那次代码同一次上线** ——
--   先有数据后有枚举值, 任何 findByFactoryId 全表查询都会在反序列化时抛
--   IllegalArgumentException, 把整个阈值中心页面打挂(不是只挂这一行)。
--   本迁移与该代码改动在同一个 PR 内, 满足这个条件。

-- 列清单与 V20260823_01 的 seed 逐字一致 (id / enabled / version / created_at / updated_at
-- 都有 DEFAULT, 由建表语句负责 —— 少写一列就少一处可能写错的地方)。
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'sales.delivery.over_ship_rate', 'SALES', 'DECIMAL', '0.10', '0.10',
   '发货超发上限：仓管确认实发数量时，实发最多可超过计划发货量的比率。0 = 一件都不许超发；停用本行 = 不限制。',
   '比率', '0', '1')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
