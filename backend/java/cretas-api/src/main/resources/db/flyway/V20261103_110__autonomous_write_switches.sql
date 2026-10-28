-- S6-04 kill switch: a factory can stop every delegated scheduled write at once, without revoking
-- grants one by one. No row means the factory has not switched writes off (grants still decide each
-- run). Switching off takes effect at the next fire; a run already at the gateway is not undone.
-- Each change keeps who made it and why; the row is updated in place, never deleted.
CREATE TABLE autonomous_write_switches (
    factory_id      VARCHAR(50)  PRIMARY KEY,
    writes_enabled  BOOLEAN      NOT NULL,
    reason          VARCHAR(200),
    changed_by      BIGINT       NOT NULL,
    changed_at      TIMESTAMP    NOT NULL
);
