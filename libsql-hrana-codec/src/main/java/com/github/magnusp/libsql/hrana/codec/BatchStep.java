package com.github.magnusp.libsql.hrana.codec;

public record BatchStep(BatchCond condition, Stmt stmt) {
    public BatchStep(Stmt stmt) {
        this(null, stmt);
    }
}
