package io.github.magnusp.libsql.jdbc.hibernate;

import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the in-tree driver through Hibernate ORM, with a focus on optimistic
 * locking. The probes assert what Hibernate needs from the driver rather than
 * re-testing Hibernate itself.
 */
class HibernateIT extends HibernateSqldSupport {

    @Test
    void persistsAndReloadsIdentityAndScalarTypes() {
        Integer authorId = createAuthor("Ursula K. Le Guin", "US");
        Integer bookId = createBook(authorId, "A Wizard of Earthsea", "9.99", true);

        assertThat(authorId).as("identity generated key").isNotNull().isPositive();
        assertThat(bookId).as("identity generated key").isNotNull().isPositive();

        read(session -> {
            Author author = session.find(Author.class, authorId);
            assertThat(author).isNotNull();
            assertThat(author.getName()).isEqualTo("Ursula K. Le Guin");
            assertThat(author.getCountry()).isEqualTo("US");
            assertThat(author.getVersion()).as("initial @Version").isZero();

            Book book = session.find(Book.class, bookId);
            assertThat(book).isNotNull();
            assertThat(book.getTitle()).isEqualTo("A Wizard of Earthsea");
            assertThat(book.getPrice()).isEqualByComparingTo("9.99");
            assertThat(book.isInPrint()).isTrue();
            assertThat(book.getAuthor().getId()).isEqualTo(authorId);
            assertThat(book.getVersion()).as("initial @Version").isZero();
            return null;
        });
    }

    @Test
    void versionIncrementsOnUpdate() {
        Integer authorId = createAuthor("Octavia E. Butler", "US");

        inTransaction((Session session) -> {
            session.find(Author.class, authorId).setCountry("USA");
        });

        read(session -> {
            Author author = session.find(Author.class, authorId);
            assertThat(author.getCountry()).isEqualTo("USA");
            assertThat(author.getVersion())
                    .as("Hibernate must bump @Version on a successful update")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void versionedBookUpdateIncrementsVersion() {
        Integer authorId = createAuthor("Book Version", "SE");
        Integer bookId = createBook(authorId, "First Edition", "9.99", true);

        inTransaction((Session session) -> {
            Book book = session.find(Book.class, bookId);
            book.setPrice(new BigDecimal("12.50"));
            book.setInPrint(false);
        });

        read(session -> {
            Book book = session.find(Book.class, bookId);
            assertThat(book.getPrice()).isEqualByComparingTo("12.50");
            assertThat(book.isInPrint()).isFalse();
            assertThat(book.getVersion())
                    .as("a versioned update through a foreign-key association must succeed")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void staleMergeOfADetachedEntityFails() {
        Integer authorId = createAuthor("Stale Merge", "US");

        Author detached = read(session -> session.find(Author.class, authorId));
        assertThat(detached.getVersion()).isZero();

        inTransaction((Session session) -> {
            session.find(Author.class, authorId).setCountry("SE");
        });

        detached.setCountry("NO");
        assertThatThrownBy(() -> inTransaction((Session session) -> {
                    session.merge(detached);
                }))
                .as("merge of a detached entity with an old @Version must fail")
                .isInstanceOfAny(OptimisticLockException.class, StaleStateException.class);

        read(session -> {
            Author author = session.find(Author.class, authorId);
            assertThat(author.getCountry()).as("the stale merge must not land").isEqualTo("SE");
            assertThat(author.getVersion()).isEqualTo(1);
            return null;
        });
    }

    @Test
    void staleDeleteFails() {
        Integer authorId = createAuthor("Stale Delete", "US");
        Author stale = read(session -> session.find(Author.class, authorId));

        inTransaction((Session session) -> {
            session.remove(session.find(Author.class, authorId));
        });

        assertThatThrownBy(() -> inTransaction((Session session) -> {
                    session.remove(stale);
                }))
                .as("the versioned DELETE matches no row; Hibernate needs the driver's"
                        + " update count to be exactly 0")
                .isInstanceOfAny(OptimisticLockException.class, StaleStateException.class);

        read(session -> {
            assertThat(session.find(Author.class, authorId))
                    .as("the row stays deleted")
                    .isNull();
            return null;
        });
    }

    @Test
    void optimisticForceIncrementBumpsVersionWithoutAFieldChange() {
        Integer authorId = createAuthor("Force Increment", "US");
        long before = read(session -> session.find(Author.class, authorId).getVersion());

        inTransaction((Session session) -> {
            session.find(Author.class, authorId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        });

        read(session -> {
            Author author = session.find(Author.class, authorId);
            assertThat(author.getVersion())
                    .as("explicit optimistic force-increment must go through the same"
                            + " versioned UPDATE path")
                    .isEqualTo(before + 1);
            return null;
        });
    }

    @Test
    void hibernateSchemaValidationAcceptsTheDriverMetadata() {
        // A second SessionFactory validates the tables the first one created. Hibernate
        // reads them back through DatabaseMetaData, so this exercises the driver's
        // metadata instead of Hibernate's own DDL.
        try (SessionFactory validating = configuration()
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .buildSessionFactory()) {
            assertThat(validating.isOpen()).isTrue();
        }
    }

    @Test
    void hqlJoinAndPaginationUseTheDriversLimitSupport() {
        Integer authorId = createAuthor("Pagination Author", "SE");
        createBook(authorId, "Volume 1", "1.00", true);
        createBook(authorId, "Volume 2", "2.00", true);
        createBook(authorId, "Volume 3", "3.00", false);

        read(session -> {
            List<Book> page = session
                    .createQuery("select b from Book b join fetch b.author"
                            + " where b.inPrint = true order by b.title", Book.class)
                    .setFirstResult(1)
                    .setMaxResults(2)
                    .getResultList();

            assertThat(page)
                    .extracting(Book::getTitle)
                    .containsExactly("Volume 2");
            assertThat(page.getFirst().getAuthor().getName()).isEqualTo("Pagination Author");
            return null;
        });
    }

    @Test
    void batchInsertKeepsIdentityAndVersion() {
        Integer authorId = createAuthor("Batch Author", "SE");

        inTransaction((Session session) -> {
            Author author = session.find(Author.class, authorId);
            for (int i = 1; i <= 5; i++) {
                session.persist(new Book(author, "Batch " + i, new BigDecimal(i + ".00"), true));
            }
        });

        read(session -> {
            List<Book> books = session.createQuery(
                            "select b from Book b where b.author.id = :authorId order by b.title",
                            Book.class)
                    .setParameter("authorId", authorId)
                    .getResultList();
            assertThat(books).hasSize(5);
            assertThat(books).allSatisfy(book -> {
                assertThat(book.getId()).isNotNull().isPositive();
                assertThat(book.getVersion()).isZero();
            });
            return null;
        });
    }

    @Test
    void timestampVersionRoundTripsAndIncrementsOnUpdate() {
        Integer noteId = inTransaction(session -> {
            Note note = new Note("first");
            session.persist(note);
            return note.getId();
        });

        Instant firstVersion = read(session -> session.find(Note.class, noteId).getVersion());
        assertThat(firstVersion).as("Hibernate seeds the timestamp version on insert").isNotNull();

        inTransaction((Session session) -> {
            session.find(Note.class, noteId).setText("second");
        });

        read(session -> {
            Note note = session.find(Note.class, noteId);
            assertThat(note.getText()).isEqualTo("second");
            assertThat(note.getVersion())
                    .as("the versioned UPDATE must match the stored timestamp text exactly")
                    .isAfter(firstVersion);
            return null;
        });
    }

    @Test
    void staleTimestampVersionMergeFails() {
        Integer noteId = inTransaction(session -> {
            Note note = new Note("original");
            session.persist(note);
            return note.getId();
        });

        Note stale = read(session -> session.find(Note.class, noteId));
        Instant staleVersion = stale.getVersion();

        inTransaction((Session session) -> {
            session.find(Note.class, noteId).setText("current");
        });

        stale.setText("stale");
        assertThatThrownBy(() -> inTransaction((Session session) -> {
                    session.merge(stale);
                }))
                .as("the versioned UPDATE must match no row once the timestamp moved")
                .isInstanceOfAny(OptimisticLockException.class, StaleStateException.class);

        read(session -> {
            Note note = session.find(Note.class, noteId);
            assertThat(note.getText()).isEqualTo("current");
            assertThat(note.getVersion()).isAfter(staleVersion);
            return null;
        });
    }
}
