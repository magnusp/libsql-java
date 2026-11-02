-- The parent written in an earlier migration than the child, as in the reported
-- application where the reseller row lives in a first fixture migration.
CREATE TABLE reseller (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO reseller (id, name) VALUES ('reseller-1', 'reseller');
