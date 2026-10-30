CREATE VIEW author_summary AS
SELECT a.id, a.name, COUNT(b.id) AS book_count
FROM author a
LEFT JOIN book b ON b.author_id = a.id
GROUP BY a.id, a.name;
