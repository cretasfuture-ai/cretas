-- 训练样本「挂起待重判」(Steve 2026-10-29 选 A, 过滤规则待办第 2 / 9 条; 2026-11-03 Q5 + 复审 N3/N4)
--
-- 收训练样本 (意图样本 ai_training_samples / 低置信样本 active_learning_samples) 时, 本厂名单
-- (工艺保密词 + 人员姓名 + 客户名称) 查不出来, 就按「上次成功的名单」判; 判下来没问题的样本不直接进
-- 样本表, 先放在这里, 由定时重判 (TrainingSampleHoldRejudger) 按当前名单再判一次:
--   * 过了 → 删掉挂起行, 再走原来的收样本入口写进样本表 (授权 / 脱敏照常处理);
--   * 提到保密词 / 本厂人名客户名 → 标记 REJECTED 并保留 (原因 + 时间), 留给人工复核, 不删 (Q5);
--     原话原文当场丢掉 (复审第二轮第 1 条: payload 换成 {"raw_text_dropped":true}, ck_tssh_rejected_no_raw 钉住);
--   * 一直判断不了 → 超过保留期 (默认 7 天) 删掉。
--
-- 单独一张表而不是给两张样本表加状态列: 样本表的读者很多 (关键词学习、合成数据、反馈 …), 加状态列就得
-- 每个读者都记得过滤, 漏一个就把没判完的样本用掉; 放在这张表里, 除了重判任务没有人读它。
--
-- payload: 重判用的**原话原文** (JSON; 复审 N3: 存脱敏后的文本会把数字编号、被换成占位符的名字藏起来,
-- 重判就漏了)。所以待判 (PENDING) 的行含原话: 放行 / 过期即删, 随工厂删除一起删 (factory_id 是租户列),
-- 也在撤回手册的删除清单里 (docs/security/training-withdrawal-runbook.md)。

CREATE TABLE IF NOT EXISTS training_sample_secrecy_holds (
    id                  BIGSERIAL    PRIMARY KEY,
    sample_kind         VARCHAR(30)  NOT NULL,
    factory_id          VARCHAR(50)  NOT NULL,
    payload             TEXT         NOT NULL,
    review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reject_reason       VARCHAR(40),
    rejected_at         TIMESTAMP,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL,
    last_attempt_at     TIMESTAMP,
    CONSTRAINT ck_tssh_kind CHECK (sample_kind IN ('INTENT', 'ACTIVE_LEARNING')),
    CONSTRAINT ck_tssh_review_status CHECK (review_status IN ('PENDING', 'REJECTED')),
    CONSTRAINT ck_tssh_rejected CHECK ((review_status = 'REJECTED') = (rejected_at IS NOT NULL)),
    CONSTRAINT ck_tssh_rejected_no_raw CHECK (review_status <> 'REJECTED' OR payload = '{"raw_text_dropped":true}'),
    CONSTRAINT ck_tssh_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_tssh_factory CHECK (btrim(factory_id) <> '')
);

-- 重判任务按「最近一次尝试 (没试过的按挂起时间)」先后取待判的行: 判不了的行试过一次就排到后面, 不会堵住队头 (N4c)
CREATE INDEX IF NOT EXISTS idx_tssh_pending ON training_sample_secrecy_holds (review_status, last_attempt_at, created_at, id);
CREATE INDEX IF NOT EXISTS idx_tssh_factory ON training_sample_secrecy_holds (factory_id);
