CREATE TABLE IF NOT EXISTS work_order (
    work_order_id VARCHAR(100) PRIMARY KEY,
    state VARCHAR(50) NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    event_key VARCHAR(250) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    object_id VARCHAR(100) NOT NULL,
    object_version BIGINT NOT NULL
);
