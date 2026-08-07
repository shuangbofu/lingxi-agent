---
name: wiki-ingest
description: "从兼容的文档证据服务中检索和读取项目 Markdown、目录、版本、差异与可选媒体证据。"
---

# 文档证据读取

首次使用命令前读取 [命令参考](references/wiki_ingest.md)，并只使用其中声明的命令签名。

## 定位

本能力按必填能力参数 `projectCode` 从兼容的文档证据服务读取项目资料。提供方可以是 Lingxi 示例资源服务，也可以是组织内部的 Wiki、知识库或文档同步服务。

## 能力边界

- 只读取 `projectCode` 指定项目中的文档证据，不跨项目猜测或扫描。
- 允许检索正文、读取命中上下文、目录、原始内容、本地版本和差异。
- 提供方声明存在媒体证据时，可以读取或重新分析提供方已经保存的媒体；不直接修改外部 Wiki 或原始文档。
- 文档内容和版本由提供方负责，能力不把本地版本解释成外部系统的完整编辑历史。
- 不创建、修改、删除或发布文档，不臆造未注册的平台命令。

## 默认检索路径

1. 单个表达式使用 `wiki source-search`。拆出多个需要分别取证的业务词、字段、动作或旧称时，合并为一次 `wiki source-search-batch --queries "词1,词2,..." --mode LITERAL`。
2. 根据命中的 `sourceDocumentId`、路径、版本和片段选择下一步。正文命中需要扩大范围时调用 `source-context`，需要连续长文时使用 `source-read` 分段读取。
3. Markdown 或转换正文缺少细节时使用 `source-read-raw`；需要目录语境时使用 `source-tree`。
4. 用户询问改动时间时使用 `source-changes`。需要比较完整版本时，再使用 `source-history` 和 `source-diff`。
5. 只有命中明确包含媒体资源时才使用 `source-media` 或 `source-image-reanalyze`。提供方不支持媒体时保留该事实，不猜测图片内容。

## 引用与结论

- 使用返回的 `renderUrl` 引用文档，具体证据优先使用 `matches[].renderUrl`；不要根据服务地址或内部 ID 自行拼接链接。
- 区分原文事实、版本差异、跨文档归纳和推断。
- 多份文档冲突时保留各自来源、时间和适用范围，不用较新的文件静默覆盖旧版本。
- 材料不足时指出具体缺口，不把检索不到等同于从未发生。
