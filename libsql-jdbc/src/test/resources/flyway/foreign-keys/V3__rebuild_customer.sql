-- SQLite cannot alter a column type, so widening one means rebuilding the table:
-- copy into a temporary table, drop the original, rename. With foreign keys
-- enabled, DROP TABLE runs an implicit DELETE FROM, and that fires ON DELETE
-- actions in child tables. This is the shape that removed every job row in the
-- downstream report; the test asserts the cascade rather than a lost update.
CREATE TABLE customer_dg_tmp (
    id         TEXT PRIMARY KEY,
    account_id TEXT NOT NULL,
    email      TEXT NOT NULL DEFAULT ''
);

INSERT INTO customer_dg_tmp (id, account_id) SELECT id, account_id FROM customer;

DROP TABLE customer;

ALTER TABLE customer_dg_tmp RENAME TO customer;
