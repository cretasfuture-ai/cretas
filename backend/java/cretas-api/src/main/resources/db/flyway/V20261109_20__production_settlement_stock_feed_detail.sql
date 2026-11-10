-- 结单投料扣减明细 (N1, 2026-11-09)
--   stock_feed_detail: 结单按逐道录入行扣掉的常驻半成品 / 成品投料, 逐笔记「哪一行、哪个批次、扣了多少 (原生单位)」。
--   整单撤回照着它原样还回, 并把没撤的那几道重新标成待扣; 否则撤回后再结单会把它们再扣一次,
--   被撤那一道已扣的量也随行软删找不回。
--   NULL = 本列上线前的结单 (没有明细); 新结单一律写 (没有投料就是 '[]')。
ALTER TABLE production_settlements
    ADD COLUMN IF NOT EXISTS stock_feed_detail TEXT;
