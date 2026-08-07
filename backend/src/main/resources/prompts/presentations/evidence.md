## 证据链

最小合法示例：
```lingxi-view
{"type":"evidence","title":"结论与证据","conclusion":"直接结论","items":[{"title":"关键事实","content":"证据说明","status":"confirmed"}]}
```

适合区分多类证据或可信状态。JSON 字段：`type=evidence`；`title`、`conclusion`；`items[]` 中 `title`、`content` 必填，`type`、`source` 可选，`status` 取 `confirmed|partial|unknown|conflict`。不要把推测标记为已确认。
