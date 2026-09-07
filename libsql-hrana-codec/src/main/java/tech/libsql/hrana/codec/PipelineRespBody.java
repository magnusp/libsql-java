package tech.libsql.hrana.codec;

import java.util.List;

public record PipelineRespBody(String baton, String baseUrl, List<StreamResult> results) {}
