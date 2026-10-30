CREATE TABLE partial_table (
    id INTEGER PRIMARY KEY
);

INSERT INTO table_that_does_not_exist (id) VALUES (1);
