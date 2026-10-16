SET LOCAL lock_timeout = '5s';
CREATE TABLE factory_role_data_scopes (
    factory_id VARCHAR(50) NOT NULL REFERENCES factories(id),
    role_code VARCHAR(50) NOT NULL,
    data_scope VARCHAR(32) NOT NULL CHECK (data_scope IN ('ALL','SELF','SELF_AND_BELOW','DEPT_AND_BELOW')),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by BIGINT NOT NULL REFERENCES users(id),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (factory_id, role_code),
    CHECK (role_code NOT IN ('factory_super_admin','platform_admin','unactivated'))
);
