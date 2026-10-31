package io.github.magnusp.libsql.jdbc.hibernate;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A throwaway sqld container and Hibernate bootstrap for the ORM probes.
 *
 * <p>The schema is created through JDBC so the probes exercise Hibernate's runtime
 * JDBC use rather than its SQLite DDL output. The validator test still reads the
 * schema back through the driver's metadata.
 *
 * <p>Hibernate generates SQLite identities with a separate
 * {@code select last_insert_rowid()}, so the probes keep auto-commit off. Issue #83
 * records why that query cannot see an insert from an earlier auto-commit statement.
 */
@Testcontainers
abstract class HibernateSqldSupport {

    private static final int PORT = 8080;
    private static final String LIBSQL_IMAGE = requireLibsqlImage();

    @Container
    static final GenericContainer<?> LIBSQL = new GenericContainer<>(DockerImageName.parse(LIBSQL_IMAGE))
            .withExposedPorts(PORT)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
            .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(PORT)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static String jdbcUrl() {
        return "jdbc:libsql:http://" + LIBSQL.getHost() + ":" + LIBSQL.getMappedPort(PORT);
    }

    static Configuration configuration() {
        return new Configuration()
                .addAnnotatedClass(Author.class)
                .addAnnotatedClass(Book.class)
                .addAnnotatedClass(Note.class)
                .setProperty("jakarta.persistence.jdbc.url", jdbcUrl())
                .setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
                .setProperty("hibernate.connection.autocommit", "false")
                .setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.format_sql", "false")
                .setProperty("hibernate.highlight_sql", "false");
    }

    static SessionFactory sessionFactory;

    @BeforeAll
    static void createSessionFactory() throws SQLException {
        createSchema();
        sessionFactory = configuration()
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .buildSessionFactory();
    }

    private static void createSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists author ("
                    + " id integer primary key autoincrement,"
                    + " version bigint not null,"
                    + " country varchar(255),"
                    + " name varchar(255) not null)");
            statement.execute("create table if not exists book ("
                    + " id integer primary key autoincrement,"
                    + " author_id integer not null references author (id),"
                    + " version bigint not null,"
                    + " in_print boolean not null,"
                    + " price numeric(38, 2),"
                    + " title varchar(255) not null)");
            statement.execute("create table if not exists note ("
                    + " id integer primary key autoincrement,"
                    + " version timestamp not null,"
                    + " text varchar(255) not null)");
        }
    }

    @AfterAll
    static void closeSessionFactory() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        inTransaction(session -> {
            session.createMutationQuery("delete from Note").executeUpdate();
            session.createMutationQuery("delete from Book").executeUpdate();
            session.createMutationQuery("delete from Author").executeUpdate();
        });
    }

    static void inTransaction(Consumer<Session> work) {
        inTransaction(session -> {
            work.accept(session);
            return null;
        });
    }

    static <T> T inTransaction(Function<Session, T> work) {
        try (Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T result = work.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw e;
            }
        }
    }

    static <T> T read(Function<Session, T> work) {
        try (Session session = sessionFactory.openSession()) {
            return work.apply(session);
        }
    }

    static Integer createAuthor(String name, String country) {
        return inTransaction(session -> {
            Author author = new Author(name, country);
            session.persist(author);
            return author.getId();
        });
    }

    static Integer createBook(Integer authorId, String title, String price, boolean inPrint) {
        return inTransaction(session -> {
            Author author = session.find(Author.class, authorId);
            Book book = new Book(author, title, new java.math.BigDecimal(price), inPrint);
            session.persist(book);
            return book.getId();
        });
    }

    private static String requireLibsqlImage() {
        String image = System.getProperty("libsql.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "libsql.image is not set. Run the integration tests with Maven so the"
                            + " pinned sqld image from the root pom is used.");
        }
        return image;
    }
}
