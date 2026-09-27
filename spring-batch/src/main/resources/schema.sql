-- Business tables. Spring Batch creates its own BATCH_* tables (the job repository) itself.
CREATE TABLE IF NOT EXISTS bank_transaction (
    file_id    VARCHAR(100)   NOT NULL,
    id         BIGINT         NOT NULL,
    account    VARCHAR(28)    NOT NULL,
    amount     DECIMAL(18, 2) NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    amount_azn DECIMAL(18, 2) NOT NULL,
    booked_on  DATE           NOT NULL,
    -- A restart that wrote a row twice would fail here: the primary key proves "no duplicates"
    PRIMARY KEY (file_id, id)
);

CREATE TABLE IF NOT EXISTS rejected_transaction (
    file_id VARCHAR(100)  NOT NULL,
    line    VARCHAR(500),
    reason  VARCHAR(500)  NOT NULL
);

CREATE TABLE IF NOT EXISTS account_summary (
    file_id   VARCHAR(100)   NOT NULL,
    account   VARCHAR(28)    NOT NULL,
    tx_count  INT            NOT NULL,
    total_azn DECIMAL(18, 2) NOT NULL,
    PRIMARY KEY (file_id, account)
);
