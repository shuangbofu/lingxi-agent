## 关键指标

最小合法示例：
```lingxi-view
{"type":"metrics","title":"关键指标","items":[{"label":"影响记录","value":"128 条"}]}
```

适合少量需要突出的核心数值。JSON 字段：`type=metrics`；`title`；`items[]` 中 `label`、`value` 必填，`detail` 可选；有比较基准时才填 `trend=up|down|flat`。
