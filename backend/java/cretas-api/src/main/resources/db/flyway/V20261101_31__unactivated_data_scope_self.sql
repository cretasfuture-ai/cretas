-- ============================================================
-- 修复: unactivated 角色的 role_definitions.data_scope 种子值仍是 ALL
-- ============================================================
-- 背景 (2026-09-04, PR #586 review 追加发现):
--   V20261101_28__create_role_definitions_table.sql 把 role_definitions 表真正建
--   了起来, 并种子了 30 行, 其中 unactivated 那一行种成了 data_scope='ALL'。
--   同一个 commit 里 DataScopeResolver.resolveScopeForRole() 的 Java switch 已经把
--   "role_code 无法被 FactoryUserRole.fromRoleCode 识别" 这条路径 fail-closed 到
--   SELF —— 但那段 switch 只在 role_definitions **查不到这一行**时才会跑到。
--   unactivated 恰好**有**种子行, 所以 resolveScopeForRole() 第一步
--   `roleDefinitionRepository.findByRoleCode("unactivated")` 就能命中, 直接返回
--   DB 里的 'ALL', switch 里那个刚修好的 case 从未被执行到 —— DB 行赢过 switch。
--
--   而 unactivated 是 CreateUserRequest 的默认角色 (roleCode 字段未显式赋值时的
--   Java 字面量默认), User.isActive 默认 true, 登录只查 isActive 不查 roleCode —— 于
--   是任何刚创建、还没来得及分配正式角色的新用户, 都能立即登录, 并命中一批"读接口对任何
--   已登录用户开放" (Bug #318 fix 沿用的既有模式, 例如 CustomerController.getCustomerList,
--   MaterialBatchServiceImpl.getMaterialBatchList 等 6 个 @DataScope("created_by") 方法所在
--   的 controller 方法都没有 @RequirePermission), 用 ALL scope 读到全工厂数据。
--   prod 现存 82 个用户里 role_code='unactivated' 的是 0 个 (只读 psql 核实), 所以
--   这个口子今天没有正在被读取的存量数据在漏, 但只要下一次创建用户就会立刻打开。
--
-- 这个 migration 只改 unactivated 这一行的 data_scope, 幂等 (UPDATE, 影响 0 或 1
-- 行都不报错; 表/行不存在的环境同样安全 —— 见下方 DO 块)。
--
-- ⚠️ V20261101_30 已被另一条并行线占用 (2026-09-04, PR #589 终审时确认), 本文件
-- 跳号直接编到 _31。V20261101_30 从此永久废弃 —— ⛔ 不要有人事后再去补建它: 本仓两个
-- profile (application-pg.properties / application-pg-prod.properties) 都设了
-- spring.flyway.out-of-order=false (默认值), 一旦 V20261101_31 在某个环境上先应用了,
-- 再补一个版本号更小的 V20261101_30 会被 Flyway 判定为 out-of-order 直接拒绝迁移。
-- 捡到这个号的人请换一个更高的版本号 (recount: git ls-tree -r --name-only origin/main
-- -- .../db/flyway | grep -oE 'V20261101_[0-9]+' | sort -t_ -k2 -n | tail -1)。
--
-- 🔴 unactivated.data_scope 收紧到 SELF 这件事本身由
-- UnactivatedDataScopeMigrationContractTest 守着: 任何版本号比本文件更高的迁移,
-- 只要在 'unactivated' 附近重新写出 'ALL' 却没有 'SELF', 该测试就会红 —— 防的正是
-- "以后谁加个 re-seed 就把这里静默改回宽权限"这件事。
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'role_definitions'
    ) THEN
        UPDATE role_definitions
        SET data_scope = 'SELF',
            updated_at = NOW()
        WHERE role_code = 'unactivated'
          AND data_scope IS DISTINCT FROM 'SELF';
    END IF;
END $$;
