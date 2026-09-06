-- ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906 step 2 (可写 L1), owner 2026-09-06 裁定。
--
-- 1) permission_admin × system: '-' → 'rw'
--    prod 只读读数 (2026-09-06): lsm001 (LIUSHANMEN, permission_admin) 被 V20260422_01 的种子
--    锁在权限矩阵页外 (@RequirePermission system:read / system:read_write 都不过)。
--    owner 裁定写权限给 permission_admin, 所以是 rw 不是 r。
--    ⚠️ L1 是全平台表: 这一行对所有工厂的 permission_admin 生效。
UPDATE platform_role_permissions
   SET permission_level = 'rw', updated_at = NOW()
 WHERE role_code = 'permission_admin' AND module_code = 'system' AND deleted_at IS NULL
   AND permission_level <> 'rw';

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES ('permission_admin', 'system', 'rw')
ON CONFLICT (role_code, module_code) DO NOTHING;

-- 2) L1 改动审计 (append-only)。
--    行上的 updated_by / updated_at 是 last-write-wins, 看不到历史; 这张表每次写一格追加一行,
--    与 L1 写同一事务 (审计失败整笔回滚)。changed_by NOT NULL: token 取不到用户 ⇒ 拒绝写, 不写 NULL。
CREATE TABLE IF NOT EXISTS platform_role_permission_audit (
  id              BIGSERIAL PRIMARY KEY,
  role_code       VARCHAR(64)  NOT NULL,
  module_code     VARCHAR(32)  NOT NULL,
  old_level       VARCHAR(8)   NULL,          -- NULL = 原来没有这一行 ('-' 是「明确不给」, 两者不同)
  new_level       VARCHAR(8)   NOT NULL,
  changed_by      BIGINT       NOT NULL,
  changed_by_role VARCHAR(64)  NULL,
  factory_id      VARCHAR(32)  NULL,          -- 从哪个工厂的页面改的 (L1 本身全平台)
  reason          VARCHAR(200) NULL,
  changed_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prp_audit_role_module ON platform_role_permission_audit (role_code, module_code);
CREATE INDEX IF NOT EXISTS idx_prp_audit_changed_at ON platform_role_permission_audit (changed_at);

COMMENT ON TABLE platform_role_permission_audit IS 'L1 platform_role_permissions 每次单格写入的追加审计 (step-2, 2026-09-06)';
