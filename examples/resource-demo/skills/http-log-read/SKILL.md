---
name: http-log-read
description: "通过只读 HTTP 日志接口按目录、文件、尾部片段和关键词读取远端日志。"
---

# HTTP 日志读取

这个能力用于通过只读 HTTP 接口读取远端日志，适合日志不在 Agent 本地文件系统、但能够通过受控网络接口访问的排查场景。

## 能力边界

- 只能通过配置声明的 HTTP 日志接口读取日志。
- 只允许执行健康检查、目录列表、尾部读取和关键词搜索。
- 不要调用写入、删除、移动、归档或任意命令执行接口。
- 默认只读取有限字节数；除非任务明确需要，不要扩大到大块读取。
- 涉及敏感信息时，只摘取判断问题所需的最小日志片段。

## 命令

```bash
http-log health --config-file /path/http-log.json
http-log list --config-file /path/http-log.json [--path 相对目录] [--pattern '*.log'] [--limit 条数]
http-log tail --config-file /path/http-log.json --file 文件路径 [--bytes 字节数]
http-log search --config-file /path/http-log.json [--file 文件路径] [--must 必含关键词] [--keyword 可选关键词] [--bytes 字节数] [--limit 条数]
```

- `health` 检查日志 HTTP 接口是否可访问。
- `list` 列出配置 `root` 下的日志文件和目录。
- `tail` 读取指定日志文件尾部片段，默认读取配置 `maxBytes` 或 512KB。
- `search` 在指定文件或匹配文件的尾部片段内搜索关键词，返回命中行和上下文。

## 配置

调用方必须显式传入 JSON 配置文件。配置至少包含 `baseUrl` 和 `root`，可包含 `path`、`filePattern`、`encoding`、`token`、`maxBytes`、`readHint`：

```json
{
  "baseUrl": "http://log-service.example/files/logs",
  "root": "/tmp/application/logs",
  "path": "",
  "filePattern": "*.log",
  "encoding": "UTF-8",
  "maxBytes": "524288",
  "readHint": "优先搜索 traceId、接口路径和异常类"
}
```

服务端启用访问令牌时，通过配置文件的 `token` 传入，不在命令或结果中暴露。

## 查询原则

查询时优先使用 traceId、请求路径、业务 ID、异常类和明确时间窗口缩小范围。不要下载完整大日志；默认只读取文件尾部片段。没有命中时先确认文件名、滚动规则和时间范围，再扩大读取字节数。

如果接口不可达、`root` 不存在、文件不存在或读取被限制，必须说明实际错误；不要把网络或配置失败解释成业务故障。
