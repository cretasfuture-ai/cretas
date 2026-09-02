-- 2026-09-02 LIUSHANMEN (owner 授权直接改库; 只读记录, ⛔ 不是可执行迁移)
-- 与 _02 配套: findCurrentByProduct 认的是 is_current=true AND status=ACTIVE, 光改 status 发布仍报「成品尚未配置任何包材关系」。
BEGIN;
UPDATE bom_recipes SET is_current=false, updated_at=now()
 WHERE factory_id='LIUSHANMEN' AND status='ARCHIVED' AND is_current=true
   AND id IN ('e085deab-3c22-4dec-8704-06b4c1c580a5','65958c0c-721d-4dc8-8f5b-2aad97ddc88c','f3b2cf8d-a523-43aa-991a-2e6248e88a56','52d227ab-adaf-4a4a-bee3-4bfb80997efc');
UPDATE bom_recipes SET is_current=true, updated_at=now()
 WHERE factory_id='LIUSHANMEN' AND status='ACTIVE' AND bom_family_id LIKE 'a07e9883%' AND deleted_at IS NULL;
COMMIT;
