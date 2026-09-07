package com.github.magnusp.libsql.hrana.codec;

import java.util.List;

public record DescribeResult(
    List<DescribeParam> params,
    List<DescribeCol> cols,
    boolean isExplain,
    boolean isReadonly
) {}
