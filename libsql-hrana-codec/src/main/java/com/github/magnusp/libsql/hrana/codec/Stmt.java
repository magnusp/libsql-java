package com.github.magnusp.libsql.hrana.codec;

import java.util.List;

public record Stmt(
    String sql,
    java.lang.Integer sqlId,
    List<Value> args,
    List<NamedArg> namedArgs,
    Boolean wantRows
) {
    public Stmt(String sql) {
        this(sql, null, List.of(), List.of(), true);
    }

    public Stmt(String sql, List<Value> args) {
        this(sql, null, args, List.of(), true);
    }
}
