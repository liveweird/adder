CREATE TABLE http_rate_limit_buckets (
    bucket_name VARCHAR(40) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    tokens_remaining INTEGER NOT NULL CHECK (tokens_remaining >= 0),
    refill_at BIGINT NOT NULL,
    PRIMARY KEY (bucket_name, key_hash)
);

CREATE INDEX idx_http_rate_limit_buckets_expiry
    ON http_rate_limit_buckets (refill_at, bucket_name, key_hash);

CREATE TABLE http_rate_limit_capacity_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton)
);

INSERT INTO http_rate_limit_capacity_guard (singleton) VALUES (TRUE);
