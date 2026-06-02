package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.rag.PackedRagContext;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.domain.rag.RagContext;

public interface RagContextPacker {

    PackedRagContext pack(RagContext context, RagSearchCriteria criteria);
}
