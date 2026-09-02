-- LIUSHANMEN: 把汉堡四成品的 BOM 从「四个单成员 family(各钉旧工艺 190–193)」切到「一个 family(钉工艺 197 修订 315)」。
-- 为什么直接改库(owner 2026-09-02 授权): 工艺发布要求「该修订上有完整 ACTIVE family」, 而 BOM 草稿激活又要求
--   「所钉工艺已是该产品的启用工艺」—— 把既有单成品 BOM 转成联产 family 在 API 上是个死锁, 产品缺口另开单。
-- 只改 status: 旧 ACTIVE → ARCHIVED (在途计划钉的是 recipe id, 状态无关, 见 PlanBomItems.fromPinnedRecipes / ProcessSheet 按 id 取);
--            新 DRAFT(family a07e9883, rev 315) → ACTIVE。⛔ 不删任何行。
BEGIN;
\echo === 改前 ===
SELECT left(r.id,8), p.name, r.version, r.status, r.workflow_id, r.workflow_revision_id, left(coalesce(r.bom_family_id,''),8)
  FROM bom_recipes r JOIN product_types p ON p.id=r.product_type_id
 WHERE r.factory_id='LIUSHANMEN' AND p.code IN ('CPLIUSHANMEN0018','CPLIUSHANMEN0019','CPLIUSHANMEN0020','CPLIUSHANMEN0021') ORDER BY p.name, r.version;
UPDATE bom_recipes SET status='ARCHIVED', updated_at=now()
 WHERE factory_id='LIUSHANMEN' AND status='ACTIVE'
   AND id IN ('e085deab-3c22-4dec-8704-06b4c1c580a5','65958c0c-721d-4dc8-8f5b-2aad97ddc88c','f3b2cf8d-a523-43aa-991a-2e6248e88a56','52d227ab-adaf-4a4a-bee3-4bfb80997efc');
UPDATE bom_recipes SET status='ACTIVE', updated_at=now()
 WHERE factory_id='LIUSHANMEN' AND status='DRAFT' AND bom_family_id LIKE 'a07e9883%' AND workflow_revision_id=315;
\echo === 改后 (预期: 4 个 ARCHIVED v1 + 4 个 ACTIVE v2 同 family) ===
SELECT left(r.id,8), p.name, r.version, r.status, r.workflow_id, r.workflow_revision_id, left(coalesce(r.bom_family_id,''),8)
  FROM bom_recipes r JOIN product_types p ON p.id=r.product_type_id
 WHERE r.factory_id='LIUSHANMEN' AND p.code IN ('CPLIUSHANMEN0018','CPLIUSHANMEN0019','CPLIUSHANMEN0020','CPLIUSHANMEN0021') ORDER BY p.name, r.version;
\echo === 闸: 修订 315 上 ACTIVE 且同 family 的成员数 (必须 4) ===
SELECT count(*) FROM bom_recipes WHERE factory_id='LIUSHANMEN' AND status='ACTIVE' AND workflow_revision_id=315 AND bom_family_id LIKE 'a07e9883%';
COMMIT;
