package com.github.magnusp.libsql.hrana.codec;

import java.util.Map;

public record BatchResult(
    Map<Integer, StmtResult> stepResults,
    Map<Integer, HranaError> stepErrors
) {}
