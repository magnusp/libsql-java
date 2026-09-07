package tech.libsql.hrana.codec;

public sealed interface StreamResult {
    record Ok(StreamResponse response) implements StreamResult {}
    record Error(HranaError error) implements StreamResult {}
}
