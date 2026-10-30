CREATE TABLE book (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    author_id INTEGER NOT NULL REFERENCES author (id),
    title     TEXT NOT NULL,
    price     REAL
);

CREATE INDEX book_author_id_idx ON book (author_id);
