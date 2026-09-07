package tech.libsql.itest;

import org.junit.jupiter.api.Test;
import tech.libsql.client.LibsqlClientConfig;
import tech.libsql.client.LibsqlHttpClient;
import tech.libsql.hrana.codec.Col;
import tech.libsql.hrana.codec.PipelineReqBody;
import tech.libsql.hrana.codec.PipelineRespBody;
import tech.libsql.hrana.codec.Row;
import tech.libsql.hrana.codec.Stmt;
import tech.libsql.hrana.codec.StmtResult;
import tech.libsql.hrana.codec.StreamRequest;
import tech.libsql.hrana.codec.StreamResponse;
import tech.libsql.hrana.codec.StreamResult;
import tech.libsql.hrana.codec.Value;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LibsqlHttpClientIT extends SqldContainerBase {

    @Test
    void testNonInteractiveOneShotSelect() {
        LibsqlClientConfig config = new LibsqlClientConfig(getHttpUrl(), null);
        LibsqlHttpClient client = new LibsqlHttpClient(config);

        StmtResult result = client.executeOneShot(new Stmt("SELECT 1 as num, 'hello' as greeting"));

        assertThat(result.cols()).hasSize(2);
        assertThat(result.cols().get(0).name()).isEqualTo("num");
        assertThat(result.cols().get(1).name()).isEqualTo("greeting");

        assertThat(result.rows()).hasSize(1);
        Row row = result.rows().get(0);
        assertThat(row.values().get(0)).isEqualTo(new Value.Integer(1L));
        assertThat(row.values().get(1)).isEqualTo(new Value.Text("hello"));
    }

    @Test
    void testAutocommitWriteBurstDoesNotStall() {
        // Verifies mitigation for Bug 1: 128-stream 10-second stall
        LibsqlClientConfig config = new LibsqlClientConfig(getHttpUrl(), null);
        LibsqlHttpClient client = new LibsqlHttpClient(config);

        client.executeOneShot(new Stmt("CREATE TABLE IF NOT EXISTS burst_test (id INTEGER PRIMARY KEY, v TEXT)"));

        long start = System.currentTimeMillis();
        for (int i = 0; i < 150; i++) {
            client.executeOneShot(new Stmt("INSERT INTO burst_test VALUES (?, ?)", List.of(Value.of(i), Value.of("v" + i))));
        }
        long duration = System.currentTimeMillis() - start;

        // 150 one-shot writes should complete within < 3-4 seconds, well below the 10-second idle stall
        assertThat(duration).isLessThan(10000L);

        StmtResult countResult = client.executeOneShot(new Stmt("SELECT COUNT(*) FROM burst_test"));
        assertThat(countResult.rows().get(0).values().get(0)).isEqualTo(new Value.Integer(150L));
    }

    @Test
    void testInteractiveTransactionLifecycleWithBaton() {
        LibsqlClientConfig config = new LibsqlClientConfig(getHttpUrl(), null);
        LibsqlHttpClient client = new LibsqlHttpClient(config);

        // 1. BEGIN IMMEDIATE (prevents Bug 2 lock upgrade deadlocks)
        PipelineReqBody beginReq = new PipelineReqBody(null, List.of(new StreamRequest.Execute(new Stmt("BEGIN IMMEDIATE"))));
        PipelineRespBody beginResp = client.sendPipeline(beginReq, null);

        String baton = beginResp.baton();
        assertThat(baton).isNotNull();

        // 2. CREATE TABLE
        PipelineReqBody ddlReq = new PipelineReqBody(baton, List.of(
                new StreamRequest.Execute(new Stmt("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)"))
        ));
        PipelineRespBody ddlResp = client.sendPipeline(ddlReq, null);
        baton = ddlResp.baton();
        assertThat(baton).isNotNull();

        // 3. INSERT
        PipelineReqBody insertReq = new PipelineReqBody(baton, List.of(
                new StreamRequest.Execute(new Stmt("INSERT INTO users VALUES (?, ?)", List.of(Value.of(1L), Value.of("Alice"))))
        ));
        PipelineRespBody insertResp = client.sendPipeline(insertReq, null);
        baton = insertResp.baton();
        assertThat(baton).isNotNull();

        // 4. COMMIT and CLOSE
        PipelineReqBody commitReq = new PipelineReqBody(baton, List.of(
                new StreamRequest.Execute(new Stmt("COMMIT")),
                StreamRequest.Close.INSTANCE
        ));
        PipelineRespBody commitResp = client.sendPipeline(commitReq, null);
        assertThat(commitResp.baton()).isNull(); // Closed stream returns null baton

        // 5. Fresh one-shot query to verify persistence
        StmtResult selectResult = client.executeOneShot(new Stmt("SELECT name FROM users WHERE id = 1"));
        assertThat(selectResult.rows()).hasSize(1);
        assertThat(selectResult.rows().get(0).values().get(0)).isEqualTo(new Value.Text("Alice"));
    }
}
