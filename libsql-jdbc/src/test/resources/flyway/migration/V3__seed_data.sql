INSERT INTO author (name, country) VALUES ('Ursula K. Le Guin', 'US');
INSERT INTO author (name, country) VALUES ('Octavia E. Butler', 'US');

INSERT INTO book (author_id, title, price)
SELECT id, 'A Wizard of Earthsea', 9.99 FROM author WHERE name = 'Ursula K. Le Guin';

INSERT INTO book (author_id, title, price)
SELECT id, 'Kindred', 12.50 FROM author WHERE name = 'Octavia E. Butler';
