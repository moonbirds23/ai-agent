package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based planner for the current user turn.
 * <p>
 * It gives the backend an explicit delivery target before tools run. The
 * post-hoc inference in {@link TaskVerifier} remains a fallback for unknown
 * turns and legacy tests.
 */
@Component
public class TaskPlanner {

    public TaskPlan plan(ChatRequest request, String turnId) {
        String text = request.message() != null ? request.message().strip() : "";
        String mode = request.mode();
        boolean hasImage = hasImage(request);
        boolean hasReferences = request.referencePictureIds() != null && !request.referencePictureIds().isEmpty();

        if (requestsGenerationWithoutTools(text)) {
            return new TaskPlan(
                    turnId,
                    TaskType.NEED_CLARIFICATION,
                    "生成图片必须调用工具，请确认是否允许调用生图工具",
                    List.of(TaskStep.of("confirm_tool_use",
                            "确认是否允许调用生图工具", true, null)),
                    false, false, false, Map.of());
        }

        TaskType type = classify(text, mode, hasImage, hasReferences);
        List<TaskStep> steps = stepsFor(type, text, hasImage, hasReferences);

        java.util.Map<String, Object> slots = new java.util.LinkedHashMap<>();
        if (hasReferences) {
            slots.put("referencePictureIds", request.referencePictureIds());
        }
        return new TaskPlan(
                turnId,
                type,
                text.isBlank() ? defaultGoal(type) : text,
                steps,
                hasImage,
                requiresGeneration(type),
                requiresExternalSearch(type),
                java.util.Map.copyOf(slots));
    }

    private TaskType classify(String text, String mode, boolean hasImage, boolean hasReferences) {
        if (ChatRequest.MODE_IMAGE_ANALYSIS.equals(mode)) {
            return hasImage ? TaskType.IMAGE_ANALYSIS : TaskType.NEED_CLARIFICATION;
        }
        if (ChatRequest.MODE_IMAGE_GENERATION.equals(mode) || Boolean.TRUE.equals(textContainsGeneration(text))) {
            return (hasImage || hasReferences || containsAny(text, "参考", "类似", "风格", "图库", "搜索"))
                    ? TaskType.CREATIVE_WORKFLOW
                    : TaskType.IMAGE_GENERATION;
        }
        if (hasImage && containsAny(text, "分析", "描述", "看看", "识别", "评价")) {
            return TaskType.IMAGE_ANALYSIS;
        }
        if (containsAny(text, "下载", "导入", "保存参考", "收集参考")) {
            return TaskType.REFERENCE_COLLECTION;
        }
        if (containsAny(text, "搜索图片", "找图片", "找图", "pexels", "素材")) {
            return TaskType.WEB_IMAGE_SEARCH;
        }
        if (containsAny(text, "搜索图库", "图库里", "找一下图库", "参考图")) {
            return TaskType.GALLERY_SEARCH;
        }
        if (containsAny(text, "收藏", "取消收藏", "删除", "重命名", "标签", "分类")) {
            return TaskType.GALLERY_MANAGEMENT;
        }
        if (containsAny(text, "风格模板", "有哪些风格", "模板")) {
            return TaskType.STYLE_DISCOVERY;
        }
        if (containsAny(text, "网页", "搜索一下", "查一下", "联网")) {
            return TaskType.WEB_RESEARCH;
        }
        return TaskType.CHAT;
    }

    private static List<TaskStep> stepsFor(TaskType type, String text,
                                           boolean hasImage, boolean hasReferences) {
        List<TaskStep> steps = new ArrayList<>();
        switch (type) {
            case CREATIVE_WORKFLOW -> {
                boolean requestsGallerySearch = containsAny(text,
                        "图库", "搜索图库", "图库里", "从图库", "参考图", "先找", "查找");
                if (hasImage) {
                    steps.add(TaskStep.of("analyze_current_image", "分析当前图片", false, "analyzeImage"));
                }
                if (hasReferences) {
                    steps.add(TaskStep.of("read_references", "读取用户参考图", false, "getPictureInfo"));
                }
                if (requestsGallerySearch) {
                    steps.add(new TaskStep("search_gallery", "搜索本地图库", true,
                            "searchGallery", List.of(), Map.of("query", text, "limit", 5),
                            StepStatus.PENDING));
                }
                List<String> dependencies = requestsGallerySearch
                        ? List.of("search_gallery") : List.of();
                steps.add(new TaskStep("generate_image", "生成图片", true,
                        "generateImage", dependencies, Map.of("promptIntent", text),
                        StepStatus.PENDING));
                steps.add(TaskStep.of("verify_image", "校验图片结果", true, null));
            }
            case IMAGE_GENERATION -> {
                steps.add(TaskStep.of("generate_image", "生成图片", true, "generateImage"));
                steps.add(TaskStep.of("verify_image", "校验图片结果", true, null));
            }
            case IMAGE_ANALYSIS -> steps.add(TaskStep.of("analyze_current_image", "分析当前图片", true, "analyzeImage"));
            case WEB_IMAGE_SEARCH -> steps.add(TaskStep.of("search_web_images", "搜索网络图片候选", true, "pexelsSearchPhotos"));
            case REFERENCE_COLLECTION -> steps.add(TaskStep.of("collect_references", "下载或导入参考图", true, "pexelsSearchAndImport"));
            case GALLERY_MANAGEMENT -> steps.add(TaskStep.of("manage_gallery", "执行图库管理操作", true, null));
            case GALLERY_SEARCH -> steps.add(new TaskStep("search_gallery", "搜索本地图库",
                    true, "searchGallery", List.of(), Map.of("query", text, "limit", 5),
                    StepStatus.PENDING));
            case WEB_RESEARCH -> steps.add(TaskStep.of("research_web", "搜索或抓取网页信息", true, "webSearch"));
            case STYLE_DISCOVERY -> steps.add(TaskStep.of("list_styles", "查询风格模板", true, "listStyleTemplates"));
            case NEED_CLARIFICATION -> steps.add(TaskStep.of("ask_clarification", "向用户追问必要信息", true, null));
            case CHAT -> steps.add(TaskStep.of("respond", "生成对话回复", true, null));
        }
        return List.copyOf(steps);
    }

    private static boolean requiresGeneration(TaskType type) {
        return type == TaskType.IMAGE_GENERATION || type == TaskType.CREATIVE_WORKFLOW;
    }

    private static boolean requiresExternalSearch(TaskType type) {
        return type == TaskType.WEB_IMAGE_SEARCH || type == TaskType.REFERENCE_COLLECTION
                || type == TaskType.WEB_RESEARCH;
    }

    private static boolean hasImage(ChatRequest request) {
        return (request.imageBase64() != null && !request.imageBase64().isBlank())
                || (request.imageUrl() != null && !request.imageUrl().isBlank());
    }

    private static Boolean textContainsGeneration(String text) {
        return containsAny(text, "生成", "画一张", "帮我画", "请画", "绘制", "做一张", "出一张",
                "制作一张", "生图", "generate", "draw", "create an image");
    }

    private static boolean requestsGenerationWithoutTools(String text) {
        return Boolean.TRUE.equals(textContainsGeneration(text))
                && containsAny(text,
                "不要调用工具", "不要调用任何工具", "不要使用工具",
                "别调用工具", "跳过工具",
                "do not call tools", "without tools");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String defaultGoal(TaskType type) {
        return switch (type) {
            case IMAGE_ANALYSIS -> "分析当前图片";
            case IMAGE_GENERATION, CREATIVE_WORKFLOW -> "生成图片";
            case NEED_CLARIFICATION -> "补充任务信息";
            default -> "完成本轮对话";
        };
    }
}
