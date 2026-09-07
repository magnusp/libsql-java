package com.github.magnusp.libsql.hrana.codec;

import java.util.List;

public record StmtResult(
    List<Col> cols,
    List<Row> rows,
    long affectedRowCount,
    Long lastInsertRowid
) {}
