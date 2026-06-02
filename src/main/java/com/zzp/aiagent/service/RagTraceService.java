package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.rag.RagTrace;

public interface RagTraceService {

    void record(RagTrace trace);
}
