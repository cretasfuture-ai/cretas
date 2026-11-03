-- 工艺保密产品的专属别名 (Steve 2026-11-03 Q1 选 A)
--
-- 训练样本过滤只认保密产品的全名 / 编码 / ID 和这里登记的专属别名 (例如全名「CP-DL-五香虎皮猪肘」, 别名「五香虎皮猪肘」)。
-- 由保密负责人在现有的保密设置里填 (PUT /api/mobile/{factoryId}/process-secrets/policies/{productTypeId}
-- 的 aliases 字段; 与改策略同一道权限: 首次启用 = 工厂总监, 之后 = 该产品的保密负责人)。
-- 存 JSON 字符串数组; NULL = 没有别名。别名与全名同样匹配: 原样出现; ASCII 首尾按边界; 品类词 / 纯数字 / 单字不收。
-- 读取方: Java ProcessSecretSampleFilter (FactoryNameDirectoryRepository.findPolicyAliases),
-- Python common.process_secret_sql.PROTECTED_TERMS_SQL / ALL_PROTECTED_TERMS_SQL。

ALTER TABLE process_secret_policies ADD COLUMN IF NOT EXISTS aliases TEXT;

ALTER TABLE process_secret_policies DROP CONSTRAINT IF EXISTS ck_process_secret_policy_aliases_array;
ALTER TABLE process_secret_policies ADD CONSTRAINT ck_process_secret_policy_aliases_array
    CHECK (aliases IS NULL OR left(btrim(aliases), 1) = '[');
