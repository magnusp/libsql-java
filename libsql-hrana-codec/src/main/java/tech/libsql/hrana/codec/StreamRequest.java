package tech.libsql.hrana.codec;

public sealed interface StreamRequest {
    record Close() implements StreamRequest {
        public static final Close INSTANCE = new Close();
    }

    record Execute(Stmt stmt) implements StreamRequest {}

    record BatchReq(Batch batch) implements StreamRequest {}

    record Sequence(String sql, java.lang.Integer sqlId) implements StreamRequest {}

    record Describe(String sql, java.lang.Integer sqlId) implements StreamRequest {}

    record StoreSql(int sqlId, String sql) implements StreamRequest {}

    record CloseSql(int sqlId) implements StreamRequest {}

    record GetAutocommit() implements StreamRequest {
        public static final GetAutocommit INSTANCE = new GetAutocommit();
    }
}
