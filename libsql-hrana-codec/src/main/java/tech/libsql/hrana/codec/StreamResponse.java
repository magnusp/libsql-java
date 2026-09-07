package tech.libsql.hrana.codec;

public sealed interface StreamResponse {
    record Close() implements StreamResponse {
        public static final Close INSTANCE = new Close();
    }

    record Execute(StmtResult result) implements StreamResponse {}

    record BatchResp(BatchResult result) implements StreamResponse {}

    record Sequence() implements StreamResponse {
        public static final Sequence INSTANCE = new Sequence();
    }

    record Describe(DescribeResult result) implements StreamResponse {}

    record StoreSql() implements StreamResponse {
        public static final StoreSql INSTANCE = new StoreSql();
    }

    record CloseSql() implements StreamResponse {
        public static final CloseSql INSTANCE = new CloseSql();
    }

    record GetAutocommit(boolean isAutocommit) implements StreamResponse {}
}
