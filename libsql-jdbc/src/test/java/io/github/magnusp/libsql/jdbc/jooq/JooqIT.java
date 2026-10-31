package io.github.magnusp.libsql.jdbc.jooq;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.codegen.GenerationTool;
import org.jooq.impl.DSL;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.jooq.tools.jdbc.JDBCUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Drives the in-tree driver through jOOQ, including code generation.
 *
 * <p>Identity-dependent queries run inside an explicit transaction and in
 * auto-commit. jOOQ's SQLite dialect reads identities with
 * {@code SELECT ... WHERE _rowid_ = last_insert_rowid()}; auto-commit
 * statements share the connection's session stream, so the follow-up select
 * sees the insert.
 */
class JooqIT extends JooqSqldSupport {

    private static final Table<Record> AUTHOR = table(name("author"));
    private static final Field<Integer> AUTHOR_ID = field(name("author", "id"), Integer.class);
    private static final Field<String> AUTHOR_NAME = field(name("author", "name"), String.class);
    private static final Field<String> AUTHOR_COUNTRY = field(name("author", "country"), String.class);

    private static final Table<Record> BOOK = table(name("book"));
    private static final Field<Integer> BOOK_ID = field(name("book", "id"), Integer.class);
    private static final Field<Integer> BOOK_AUTHOR_ID = field(name("book", "author_id"), Integer.class);
    private static final Field<String> BOOK_TITLE = field(name("book", "title"), String.class);
    private static final Field<Float> BOOK_PRICE = field(name("book", "price"), Float.class);
    private static final Field<Integer> BOOK_IN_PRINT = field(name("book", "in_print"), Integer.class);

    private static Connection connection;
    private static DSLContext dsl;

    @TempDir
    Path codegenOutput;

    @BeforeAll
    static void connect() throws Exception {
        createSchema();
        connection = DriverManager.getConnection(jdbcUrl());
        dsl = DSL.using(connection, SQLDialect.SQLITE);
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        dsl.deleteFrom(BOOK).execute();
        dsl.deleteFrom(AUTHOR).execute();
    }

    @Test
    void crudThroughJooqDsl() {
        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            Record author = tx.insertInto(AUTHOR)
                    .set(AUTHOR_NAME, "Ursula K. Le Guin")
                    .set(AUTHOR_COUNTRY, "US")
                    .returning(AUTHOR_ID)
                    .fetchOne();
            assertThat(author).isNotNull();
            Integer authorId = author.get(AUTHOR_ID);
            assertThat(authorId).as("generated author id").isNotNull();

            Record book = tx.insertInto(BOOK)
                    .set(BOOK_AUTHOR_ID, authorId)
                    .set(BOOK_TITLE, "A Wizard of Earthsea")
                    .set(BOOK_PRICE, 9.99f)
                    .set(BOOK_IN_PRINT, 1)
                    .returning(BOOK_ID)
                    .fetchOne();
            assertThat(book).isNotNull();
            Integer bookId = book.get(BOOK_ID);
            assertThat(bookId).as("generated book id").isNotNull();

            Record row = tx.select(AUTHOR_NAME, BOOK_TITLE, BOOK_PRICE)
                    .from(AUTHOR)
                    .join(BOOK).on(BOOK_AUTHOR_ID.eq(AUTHOR_ID))
                    .where(AUTHOR_ID.eq(authorId))
                    .fetchOne();
            assertThat(row).isNotNull();
            assertThat(row.get(AUTHOR_NAME)).isEqualTo("Ursula K. Le Guin");
            assertThat(row.get(BOOK_TITLE)).isEqualTo("A Wizard of Earthsea");
            assertThat(row.get(BOOK_PRICE)).isEqualTo(9.99f);

            assertThat(tx.update(AUTHOR)
                    .set(AUTHOR_COUNTRY, "USA")
                    .where(AUTHOR_ID.eq(authorId))
                    .execute()).isEqualTo(1);
            assertThat(tx.select(AUTHOR_COUNTRY).from(AUTHOR).where(AUTHOR_ID.eq(authorId))
                    .fetchOne(AUTHOR_COUNTRY)).isEqualTo("USA");

            assertThat(tx.deleteFrom(BOOK).where(BOOK_ID.eq(bookId)).execute()).isEqualTo(1);
            assertThat(tx.deleteFrom(AUTHOR).where(AUTHOR_ID.eq(authorId)).execute()).isEqualTo(1);
            assertThat(tx.fetchCount(AUTHOR)).isZero();
        });

        assertThat(dsl.fetchCount(AUTHOR)).isZero();
    }

    @Test
    void insertUpdateAndDeleteReturning() {
        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            Record inserted = tx.insertInto(AUTHOR)
                    .set(AUTHOR_NAME, "Octavia E. Butler")
                    .set(AUTHOR_COUNTRY, "US")
                    .returning(AUTHOR_ID, AUTHOR_NAME)
                    .fetchOne();
            assertThat(inserted).isNotNull();
            Integer authorId = inserted.get(AUTHOR_ID);
            assertThat(authorId).as("RETURNING id").isNotNull();
            assertThat(inserted.get(AUTHOR_NAME)).isEqualTo("Octavia E. Butler");

            Record updated = tx.update(AUTHOR)
                    .set(AUTHOR_COUNTRY, "USA")
                    .where(AUTHOR_ID.eq(authorId))
                    .returning(AUTHOR_ID, AUTHOR_COUNTRY)
                    .fetchOne();
            assertThat(updated).isNotNull();
            assertThat(updated.get(AUTHOR_COUNTRY)).isEqualTo("USA");

            Record deleted = tx.deleteFrom(AUTHOR)
                    .where(AUTHOR_ID.eq(authorId))
                    .returning(AUTHOR_ID)
                    .fetchOne();
            assertThat(deleted).isNotNull();
            assertThat(deleted.get(AUTHOR_ID)).isEqualTo(authorId);
            assertThat(tx.fetchCount(AUTHOR)).isZero();
        });

        assertThat(dsl.fetchCount(AUTHOR)).isZero();
    }

    @Test
    void jdbcBatchInsert() {
        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            Record author = tx.insertInto(AUTHOR)
                    .set(AUTHOR_NAME, "Batch Author")
                    .returning(AUTHOR_ID)
                    .fetchOne();
            assertThat(author).isNotNull();
            Integer authorId = author.get(AUTHOR_ID);
            assertThat(authorId).isNotNull();

            List<Query> inserts = IntStream.rangeClosed(1, 4)
                    .mapToObj(i -> tx.insertInto(BOOK)
                            .set(BOOK_AUTHOR_ID, authorId)
                            .set(BOOK_TITLE, "Volume " + i)
                            .set(BOOK_PRICE, i * 2.5f)
                            .set(BOOK_IN_PRINT, 1))
                    .map(Query.class::cast)
                    .toList();

            assertThat(tx.batch(inserts).execute()).containsExactly(1, 1, 1, 1);
            assertThat(tx.fetchCount(BOOK)).isEqualTo(4);
            assertThat(tx.select(BOOK_TITLE).from(BOOK).orderBy(BOOK_ID).fetch(BOOK_TITLE))
                    .containsExactly("Volume 1", "Volume 2", "Volume 3", "Volume 4");
        });
    }

    @Test
    void transactionRollsBackOnException() {
        try {
            dsl.transaction(configuration -> {
                DSLContext tx = DSL.using(configuration);
                tx.insertInto(AUTHOR)
                        .set(AUTHOR_NAME, "Rolled Back")
                        .set(AUTHOR_COUNTRY, "XX")
                        .execute();
                throw new IllegalStateException("force rollback");
            });
            fail("transaction should have propagated the exception");
        } catch (RuntimeException expected) {
            assertThat(expected).hasStackTraceContaining("force rollback");
        }

        assertThat(dsl.fetchCount(AUTHOR, AUTHOR_NAME.eq("Rolled Back")))
                .as("rows visible after rollback")
                .isZero();
    }

    @Test
    void codeGenerationProducesTypedTablesAndRecords() throws Exception {
        Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("io.github.magnusp.libsql.jdbc.LibsqlDriver")
                        .withUrl(jdbcUrl()))
                .withGenerator(new Generator()
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.sqlite.SQLiteDatabase")
                                .withIncludes("author|book")
                                .withIncludeCheckConstraints(false))
                        .withGenerate(new Generate()
                                .withRecords(true)
                                .withPojos(false))
                        .withTarget(new Target()
                                .withPackageName("io.github.magnusp.libsql.jdbc.jooq.generated")
                                .withDirectory(codegenOutput.toString())));

        GenerationTool.generate(configuration);

        Path tables = codegenOutput.resolve("io/github/magnusp/libsql/jdbc/jooq/generated/tables");
        Path author = tables.resolve("Author.java");
        Path book = tables.resolve("Book.java");
        assertThat(author).exists();
        assertThat(book).exists();
        assertThat(tables.resolve("records/AuthorRecord.java")).exists();
        assertThat(tables.resolve("records/BookRecord.java")).exists();

        assertThat(Files.readString(author))
                .contains("TableField<AuthorRecord, Integer> ID")
                .contains("generatedByDefaultAsIdentity");
        assertThat(Files.readString(book))
                .contains("TableField<BookRecord, Integer> AUTHOR_ID")
                .contains("nullable(false)");
    }

    @Test
    void autoCommitInsertExposesGeneratedIdentity() {
        Record inserted = dsl.insertInto(AUTHOR)
                .set(AUTHOR_NAME, "Auto Commit")
                .set(AUTHOR_COUNTRY, "SE")
                .returning(AUTHOR_ID)
                .fetchOne();

        assertThat(dsl.fetchCount(AUTHOR, AUTHOR_NAME.eq("Auto Commit")))
                .as("the row itself is inserted")
                .isEqualTo(1);
        assertThat(inserted)
                .as("jOOQ reads identities with SELECT ... WHERE _rowid_ = last_insert_rowid();"
                        + " auto-commit statements share the connection's session stream, so the"
                        + " select sees the insert.")
                .isNotNull();
        Integer authorId = inserted.get(AUTHOR_ID);
        assertThat(authorId).isNotNull().isPositive();
        assertThat(dsl.select(field("last_insert_rowid()", Integer.class))
                .fetchOne(0, Integer.class))
                .as("last_insert_rowid() survives between auto-commit statements")
                .isEqualTo(authorId);
    }

    @Test
    void libsqlUrlIsNotDetectedByJooq() {
        assertThat(JDBCUtils.dialect(connection))
                .as("jOOQ 3.21 derives the dialect from DatabaseMetaData.getURL(), and it"
                        + " has no jdbc:libsql: prefix. Pass SQLDialect.SQLITE explicitly.")
                .isEqualTo(SQLDialect.DEFAULT);
    }

    @Test
    void sqliteAliasUrlIsDetectedAsSqliteDialect() throws Exception {
        try (Connection alias = DriverManager.getConnection("jdbc:sqlite:" + httpUrl())) {
            assertThat(JDBCUtils.dialect(alias))
                    .as("getURL() preserves the jdbc:sqlite: prefix, which jOOQ recognizes")
                    .isEqualTo(SQLDialect.SQLITE);
        }
    }
}
