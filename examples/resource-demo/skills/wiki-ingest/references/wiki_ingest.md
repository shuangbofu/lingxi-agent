# 文档证据读取命令

只使用以下命令签名：

```bash
wiki source-search --query 检索词 [--mode LITERAL|ALL_TERMS|ANY_TERMS] [--include-history true|false] [--limit 20]
wiki source-search-batch --queries 逗号分隔检索词 [--mode LITERAL|ALL_TERMS|ANY_TERMS] [--include-history true|false] [--limit-per-query 20]
wiki source-read --id 源文档ID [--offset 0] [--limit 20000]
wiki source-context --id 源文档ID --offset 字符位置 [--radius 1200]
wiki source-media --id 源文档ID
wiki source-image-reanalyze --id 源文档ID --media-id 图片ID --question 核验问题
wiki source-history --id 源文档ID
wiki source-changes --id 源文档ID [--query 逻辑关键词] [--limit 100]
wiki source-diff --from-id 起始源文档ID --to-id 目标源文档ID
wiki source-read-raw --id 源文档ID [--offset 0] [--limit 20000]
wiki source-tree [--batch-id 同步批次ID]
wiki source-list [--ids 1,2,3]
wiki source-read-deep --id 源文档ID [--max-images 20]
```

`source-search` 适合一个查询表达式；`ANY_TERMS` 的所有词共享总 `limit`。`source-search-batch` 在一次请求中按词分组返回结果，每个词独享 `limit-per-query`，适合对多个业务词分别保留证据集。

正文命中后使用 `source-context` 或 `source-read`。原始内容使用 `source-read-raw`，目录使用 `source-tree`，项目内文档清单使用 `source-list`。版本变化使用 `source-changes`、`source-history` 和 `source-diff`。只有结果中存在媒体时才使用媒体相关命令。

所有命令都由能力参数 `projectCode` 限定项目。最终引用只使用服务返回的 `renderUrl` 或 `matches[].renderUrl`，不要自行拼接内部资源地址。
