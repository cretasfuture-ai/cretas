-- 训练样本「挂起待重判」(Steve 2026-10-29 选 A, 过滤规则待办第 2 / 9 条)
--
-- 收训练样本 (意图样本 ai_training_samples / 低置信样本 active_learning_samples) 时, 本厂名单
-- (工艺保密词 + 人员姓名 + 客户名称) 查不出来, 就按「上次成功的名单」判; 判下来没问题的样本不直接进
-- 样本表, 先放在这里, 由定时重判 (TrainingSampleHoldRejudger) 按当前名单再判一次: 过了才写进样本表,
-- 提到保密词 / 本厂人名客户名的删掉, 一直判断不了的超过保留期删掉。
--
-- 单独一张表而不是给两张样本表加状态列: 样本表的读者很多 (关键词学习、合成数据、反馈 …), 加状态列就得
-- 每个读者都记得过滤, 漏一个就把没判完的样本用掉; 放在这张表里, 除了重判任务没有人读它。
--
-- payload: 这条样本将要写进样本表的内容 (JSON, 已过内容规则; 未授权工厂已脱敏)。
-- authorized_at_hold: 挂起时该厂是否已授权 (原样存); 放行时授权已撤销 / 降级的, 重新按当时的授权处理。
-- 撤回训练时这张表与两张样本表一起按 factory_id 删除 (docs/security/training-withdrawal-runbook.md)。

CREATE TABLE IF NOT EXISTS training_sample_secrecy_holds (
    id                  BIGSERIAL    PRIMARY KEY,
    sample_kind         VARCHAR(30)  NOT NULL,
    factory_id          VARCHAR(50)  NOT NULL,
    payload             TEXT         NOT NULL,
    authorized_at_hold  BOOLEAN      NOT NULL,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL,
    last_attempt_at     TIMESTAMP,
    CONSTRAINT ck_tssh_kind CHECK (sample_kind IN ('INTENT', 'ACTIVE_LEARNING')),
    CONSTRAINT ck_tssh_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_tssh_factory CHECK (btrim(factory_id) <> '')
);

CREATE INDEX IF NOT EXISTS idx_tssh_created ON training_sample_secrecy_holds (created_at, id);
CREATE INDEX IF NOT EXISTS idx_tssh_factory ON training_sample_secrecy_holds (factory_id);
