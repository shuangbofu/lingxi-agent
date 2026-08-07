## 链接集合

最小合法示例：
```lingxi-view
{"type":"links","title":"相关资料","items":[{"title":"资料标题","url":"https://example.com/resource"}]}
```

适合可直接访问的页面或资料。JSON 字段：`type=links`；`title` 必填、`description` 可选；`items[]` 中 `title`、`url` 必填，`description`、`source` 可选，`target=blank|self`、默认 `blank`。链接必须来自能力结果、用户输入或已确认地址，不得猜测。显示文本不得暴露数据库 ID、资源 ID、修订号或节点 token；已确认 URL 可含内部值。不要在正文重复同一组链接。
