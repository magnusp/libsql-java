package tech.libsql.hrana.codec;

import java.util.List;

public record Batch(List<BatchStep> steps) {}
