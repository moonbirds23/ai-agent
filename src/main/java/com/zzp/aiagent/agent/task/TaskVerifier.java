package com.zzp.aiagent.agent.task;

import java.util.List;
import java.util.Map;

/**
 * Verifies whether a task was actually completed based on
 * authoritative {@link ToolExecutionRecord} evidence, NOT the model's text claims.
 */
public final class TaskVerifier {

    private TaskVerifier() { /* utility */ }

    /**
     * Verify a task based on the records in the ledger.
     *
     * @param taskType suspected task type (derived from tool calls)
     * @param records  all execution records for this turn
     * @return verification result with authoritative status
     */
    public static VerificationResult verify(TaskType taskType, List<ToolExecutionRecord> records) {
        return switch (taskType) {
            case IMAGE_GENERATION     -> verifyImageGeneration(records);
            case WEB_IMAGE_SEARCH     -> verifyWebImageSearch(records);
            case REFERENCE_COLLECTION -> verifyReferenceCollection(records);
            case IMAGE_ANALYSIS       -> verifyImageAnalysis(records);
            case GALLERY_MANAGEMENT   -> verifyGalleryManagement(records);
            case GALLERY_SEARCH       -> verifyGallerySearch(records);
            case WEB_RESEARCH         -> verifyWebResearch(records);
            case STYLE_DISCOVERY      -> verifyStyleDiscovery(records);
            case CREATIVE_WORKFLOW    -> verifyCreativeWorkflow(records);
            case CHAT                -> VerificationResult.success("对话完成", Map.of());
            case NEED_CLARIFICATION   -> VerificationResult.needMoreInfo("需要更多信息");
        };
    }

    // ── per-task-type verification ──────────────────────────────────

    /** Image generation: must have generateImage success + imageUrl/imageBase64. */
    private static VerificationResult verifyImageGeneration(List<ToolExecutionRecord> records) {
        ToolExecutionRecord gen = lastSuccess(records, "generateImage");
        if (gen == null) {
            return VerificationResult.failed("本轮没有成功调用图片生成工具");
        }
        Map<String, Object> output = gen.output();
        boolean hasImage = output.containsKey("imageUrl") || output.containsKey("imageBase64");
        if (!hasImage) {
            return VerificationResult.failed("图片生成工具已调用但未返回有效图片数据");
        }
        return VerificationResult.success("图片已生成", output);
    }

    /** Web image search: must have imageSearch/pexelsSearchPhotos success with candidates > 0. */
    private static VerificationResult verifyWebImageSearch(List<ToolExecutionRecord> records) {
        ToolExecutionRecord search = lastSuccess(records, "imageSearch");
        if (search == null) search = lastSuccess(records, "pexelsSearchPhotos");
        if (search == null) {
            return VerificationResult.failed("本轮没有成功搜索网络图片");
        }
        Object count = search.output().get("candidateCount");
        if (count instanceof Number n && n.intValue() <= 0) {
            return VerificationResult.failed("网络图片搜索未返回可展示的候选图片");
        }
        return VerificationResult.success("已找到网络图片候选", search.output());
    }

    /** Reference collection: must have download/import tool success with pictureId. */
    private static VerificationResult verifyReferenceCollection(List<ToolExecutionRecord> records) {
        String[] tools = {"downloadImage", "importImage", "searchAndDownload", "pexelsSearchAndImport"};
        for (String tool : tools) {
            ToolExecutionRecord r = lastSuccess(records, tool);
            if (r != null && (r.output().containsKey("pictureId") || r.output().containsKey("pictureIds"))) {
                return VerificationResult.success("图片已保存到图库", r.output());
            }
        }
        // Check if any tool was called but failed
        boolean anyCalled = false;
        for (String tool : tools) {
            if (records.stream().anyMatch(r -> r.toolName().equals(tool))) {
                anyCalled = true;
                break;
            }
        }
        if (anyCalled) {
            return VerificationResult.failed("图片下载/导入工具已调用但未返回有效的图库记录");
        }
        return VerificationResult.failed("本轮没有成功下载或导入图片到图库");
    }

    /** Image analysis: must have analyzeImage success with analysis fields. */
    private static VerificationResult verifyImageAnalysis(List<ToolExecutionRecord> records) {
        ToolExecutionRecord r = lastSuccess(records, "analyzeImage");
        if (r == null) {
            return VerificationResult.failed("本轮没有成功分析图片");
        }
        Map<String, Object> out = r.output();
        boolean hasFields = out.containsKey("subject") || out.containsKey("style")
                || out.containsKey("colors") || out.containsKey("composition");
        if (!hasFields) {
            return VerificationResult.failed("图片分析工具已调用但未返回有效的分析结果");
        }
        return VerificationResult.success("图片分析完成", out);
    }

    /** Gallery management: must have manageFavorite success with pictureId. */
    private static VerificationResult verifyGalleryManagement(List<ToolExecutionRecord> records) {
        ToolExecutionRecord r = lastSuccess(records, "manageFavorite");
        if (r == null) {
            return VerificationResult.failed("本轮没有成功执行图库管理操作");
        }
        if (!r.output().containsKey("pictureId")) {
            return VerificationResult.failed("图库管理操作未返回有效的图片记录");
        }
        return VerificationResult.success("图库操作已完成", r.output());
    }

    /** Gallery search: searchGallery was called (empty results are OK). */
    private static VerificationResult verifyGallerySearch(List<ToolExecutionRecord> records) {
        ToolExecutionRecord r = lastSuccess(records, "searchGallery");
        if (r == null) {
            return VerificationResult.failed("本轮没有成功搜索图库");
        }
        return VerificationResult.success("图库搜索完成", r.output());
    }

    /** Web research: webSearch or webFetch success. */
    private static VerificationResult verifyWebResearch(List<ToolExecutionRecord> records) {
        boolean hasSearch = lastSuccess(records, "webSearch") != null;
        boolean hasFetch = lastSuccess(records, "webFetch") != null;
        if (!hasSearch && !hasFetch) {
            return VerificationResult.failed("本轮没有成功执行网页搜索或抓取");
        }
        return VerificationResult.success("网页研究完成", Map.of());
    }

    /** Style discovery: listStyleTemplates was called. */
    private static VerificationResult verifyStyleDiscovery(List<ToolExecutionRecord> records) {
        ToolExecutionRecord r = lastSuccess(records, "listStyleTemplates");
        if (r == null) {
            return VerificationResult.failed("本轮没有成功查询风格模板");
        }
        return VerificationResult.success("风格模板查询完成", r.output());
    }

    /** Creative workflow: at minimum one generation or multi-tool chain present. */
    private static VerificationResult verifyCreativeWorkflow(List<ToolExecutionRecord> records) {
        // Aggregate result for the full pipeline
        boolean hasGenerated = lastSuccess(records, "generateImage") != null;
        boolean hasSearched = lastSuccess(records, "searchGallery") != null
                || lastSuccess(records, "pexelsSearchPhotos") != null
                || lastSuccess(records, "imageSearch") != null;
        if (!hasGenerated && !hasSearched) {
            return VerificationResult.failed("本轮没有完成创作工作流中的关键步骤");
        }
        if (hasGenerated && hasSearched) {
            return VerificationResult.success("创作工作流已完成：已搜索参考图并生成图片", Map.of("pipelineComplete", true));
        }
        if (hasGenerated) {
            return VerificationResult.partialSuccess("已生成图片（未搜索参考图）", Map.of("pipelineComplete", false));
        }
        return VerificationResult.partialSuccess("已搜索参考图（未生成图片）", Map.of("pipelineComplete", false));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static ToolExecutionRecord lastSuccess(List<ToolExecutionRecord> records, String toolName) {
        if (records == null) return null;
        for (int i = records.size() - 1; i >= 0; i--) {
            ToolExecutionRecord r = records.get(i);
            if (r.success() && r.toolName().equals(toolName)) return r;
        }
        return null;
    }

    /**
     * Infer the dominant task type from the tool calls actually made.
     * Used when no explicit task plan exists (Phase C behaviour).
     */
    public static TaskType inferTaskType(List<ToolExecutionRecord> records) {
        if (records == null || records.isEmpty()) return TaskType.CHAT;

        boolean hasGen = records.stream().anyMatch(r -> "generateImage".equals(r.toolName()));
        boolean hasSearch = records.stream().anyMatch(r -> "imageSearch".equals(r.toolName())
                || "pexelsSearchPhotos".equals(r.toolName()));
        boolean hasDownload = records.stream().anyMatch(r -> isDownloadTool(r.toolName()));
        boolean hasAnalysis = records.stream().anyMatch(r -> "analyzeImage".equals(r.toolName()));
        boolean hasWebSearch = records.stream().anyMatch(r -> "webSearch".equals(r.toolName())
                || "webFetch".equals(r.toolName()));
        boolean hasGallerySearch = records.stream().anyMatch(r -> "searchGallery".equals(r.toolName()));
        boolean hasFavorite = records.stream().anyMatch(r -> "manageFavorite".equals(r.toolName()));
        boolean hasStyles = records.stream().anyMatch(r -> "listStyleTemplates".equals(r.toolName()));

        // Multi-tool workflows take priority
        if (hasGen && (hasSearch || hasGallerySearch)) return TaskType.CREATIVE_WORKFLOW;
        if (hasGen) return TaskType.IMAGE_GENERATION;
        if (hasDownload) return TaskType.REFERENCE_COLLECTION;
        if (hasSearch) return TaskType.WEB_IMAGE_SEARCH;
        if (hasAnalysis) return TaskType.IMAGE_ANALYSIS;
        if (hasFavorite) return TaskType.GALLERY_MANAGEMENT;
        if (hasGallerySearch) return TaskType.GALLERY_SEARCH;
        if (hasWebSearch) return TaskType.WEB_RESEARCH;
        if (hasStyles) return TaskType.STYLE_DISCOVERY;

        return TaskType.NEED_CLARIFICATION;
    }

    private static boolean isDownloadTool(String name) {
        return "downloadImage".equals(name) || "importImage".equals(name)
                || "searchAndDownload".equals(name) || "pexelsSearchAndImport".equals(name);
    }
}
