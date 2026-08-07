## 步骤状态

最小合法示例：
```lingxi-view
{"type":"steps","title":"执行步骤","items":[{"title":"完成预检","status":"success"}]}
```

适合有状态的执行、验证或交付步骤。JSON 字段：`type=steps`；`title`；`items[]` 中 `title` 必填，`content` 可选，`status` 取 `pending|running|success|error|skipped`。纯说明性列表仍用 Markdown。
