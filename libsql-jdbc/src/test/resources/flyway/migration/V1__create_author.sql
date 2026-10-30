-- A migration file with more than one statement: Flyway splits on semicolons.
CREATE TABLE author (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT NOT NULL,
    country TEXT
);

CREATE INDEX author_name_idx ON author (name);
