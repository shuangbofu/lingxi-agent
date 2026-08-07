## 时间线

最小合法示例：
```lingxi-view
{"type":"timeline","title":"关键时间线","items":[{"time":"10:02","title":"任务开始","status":"success"}]}
```

适合有明确时间顺序的关键事件。JSON 字段：`type=timeline`；`title`；按时间排列的 `items[]` 中 `time`、`title` 必填，`content` 可选，`status` 取 `info|success|warning|error`。
