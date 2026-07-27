# AI 工作区

这是 Vibe Coding 的项目控制面，保存 AI 需要但不属于产品代码的需求、计划、问题、交接和历史记录。

## 每次任务的阅读顺序

1. 阅读根目录 `AGENTS.md` 或 `CLAUDE.md` 中自动加载的核心规则。
2. 阅读本文件，确认当前有效需求和计划。
3. 只加载与任务有关的 `context/`、`requirements/`、`plans/` 或 `issues/` 文件。
4. 执行完成后更新任务状态并写入 `handoffs/`；完成的计划移入 `archive/`。

## 当前工作

### 待澄清需求

- [图库元数据来源字段](requirements/gallery-picture-source.md)
- [真实图生图 API](requirements/image-to-image-api.md)

### 活跃计划

- 当前无活跃计划。

### Backlog

- [Jina CLIP v2 接入](plans/backlog/jina-clip-integration-plan.md)

### 待复核问题

- [已知问题索引](issues/known-issues.md)

## 目录职责

- `context/`：长期有效的 AI 协作与文档规则
- `requirements/`：目标已提出，但范围或方案尚未确认
- `plans/active/`：已经确认、允许执行的任务书
- `plans/backlog/`：暂不执行的候选方案
- `handoffs/`：Codex 规划与 Claude Code 执行结果
- `issues/`：经确认仍存在的问题
- `generated/`：可从代码重新生成的索引
- `archive/`：已完成、已过时或仅供追溯的资料
- `local/`：AI 本地草稿和临时内容，不提交 Git

## 状态规则

工作文档统一使用 `draft`、`active`、`blocked`、`done`、`superseded`、`archived`。没有明确状态的旧文档不得直接当作执行任务书。

## 规划输出

提出或审核方案时必须遵守 [规划与验证输出规则](context/planning-output-rules.md)：实施规划默认只展开当前必要阶段，验证规划需要详细验证前提、风险和替代方案；涉及技术选型时，必须先联网调研官方文档、源码和代表性开源实现，再与用户确认计划。
