package tech.libsql.hrana.codec;

import java.util.List;

public record PipelineReqBody(String baton, List<StreamRequest> requests) {
    public PipelineReqBody(List<StreamRequest> requests) {
        this(null, requests);
    }
}
