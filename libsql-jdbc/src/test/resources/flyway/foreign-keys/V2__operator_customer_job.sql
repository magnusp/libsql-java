-- One migration does the DDL and the seeding: operator and customer are written
-- first, then two job rows reference all three parents. `job-ignored` uses
-- INSERT OR IGNORE and `job-plain` uses INSERT, so a difference between them
-- isolates constraint handling from ordinary insert ordering.
CREATE TABLE operator (
    id         TEXT PRIMARY KEY,
    account_id TEXT NOT NULL
);

CREATE TABLE customer (
    id         TEXT PRIMARY KEY,
    account_id TEXT NOT NULL
);

CREATE TABLE job (
    id             TEXT PRIMARY KEY,
    reseller_id    TEXT NOT NULL REFERENCES reseller (id),
    customer_id    TEXT NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    operator_id    TEXT NOT NULL REFERENCES operator (id),
    reference_code TEXT NOT NULL UNIQUE,
    status         TEXT NOT NULL DEFAULT 'ONGOING' CHECK (status IN ('ONGOING', 'DONE'))
);

INSERT INTO operator (id, account_id) VALUES ('operator-1', 'account-2');
INSERT INTO customer (id, account_id) VALUES ('customer-1', 'account-3');

INSERT OR IGNORE INTO job (id, reseller_id, customer_id, operator_id, reference_code)
VALUES ('job-ignored', 'reseller-1', 'customer-1', 'operator-1', 'JOB-IGNORED');

INSERT INTO job (id, reseller_id, customer_id, operator_id, reference_code)
VALUES ('job-plain', 'reseller-1', 'customer-1', 'operator-1', 'JOB-PLAIN');
