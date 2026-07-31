---
status: draft
updated: 2026-07-27
---

# 待复核问题

以下问题来自旧项目规则。近期 Agent、Advisor 和 WorkflowEngine 已有较大调整，因此在建立修复计划前必须先用当前代码和测试复现。

1. 流式路径中 `ExceptionGuard` 对 `BusinessException` 的兜底可能不生效。
2. 内容安全仍可能只有关键词字面匹配，缺少语义审核。
3. 无有效上下文的“继续上一个任务”等指令可能触发 Prompt 补全幻觉。

旧的完整审查报告已移动到 `archive/reviews/2026-06/`，其中的问题不能默认视为仍然存在。
