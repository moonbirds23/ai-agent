package com.zzp.aiagent.rag.enhance;

import com.zzp.aiagent.rag.model.RagContext;

public interface RagContextPacker {

    PackedRagContext pack(RagContext context, RagSearchCriteria criteria);
}
