package com.github.magnusp.libsql.itest;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class HikariCpIntegrationIT extends SqldContainerBase {

    @Test
    void testHikariPoolConcurrentBorrowAndReturn() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:libsql:" + getHttpUrl());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(3000);

        try (HikariDataSource ds = new HikariDataSource(config)) {
            // Initialize test schema
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS pool_test (thread_id INT, val TEXT)");
            }

            int numThreads = 16;
            int iterationsPerThread = 25;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            List<Callable<Void>> tasks = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                tasks.add(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        try (Connection conn = ds.getConnection()) {
                            // Alternate between autocommit and transactions
                            if (i % 2 == 0) {
                                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pool_test VALUES (?, ?)")) {
                                    ps.setInt(1, threadId);
                                    ps.setString(2, "autocommit_" + i);
                                    ps.executeUpdate();
                                }
                            } else {
                                conn.setAutoCommit(false);
                                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pool_test VALUES (?, ?)")) {
                                    ps.setInt(1, threadId);
                                    ps.setString(2, "tx_" + i);
                                    ps.executeUpdate();
                                }
                                conn.commit();
                                conn.setAutoCommit(true);
                            }
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get(); // Ensure no exceptions thrown
            }
            executor.shutdown();

            // Validate total rows inserted
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pool_test")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(numThreads * iterationsPerThread);
            }
        }
    }
}
