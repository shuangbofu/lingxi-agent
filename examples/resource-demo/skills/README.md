# 公开示例 Skill

这里保存与 `resource-demo` 配套、可以公开发布的外置 Skill 源码：

- `project-hub`：读取项目、环境、代码仓库、关系和资源配置。
- `wiki-ingest`：读取兼容文档服务中的 Markdown、目录和版本证据。
- `http-log-read`：读取兼容 HTTP 日志服务提供的虚拟或真实日志文件。

Demo 的 Maven 和 Docker 构建会自动将这三个目录打成能力安装包。用户在 Demo 的“接入 Lingxi”页面选择能力和场景后，Lingxi 会在场景管理页弹出确认框，不需要手动复制目录或上传 ZIP。

这些 Skill 是可被场景使用的外置能力，不属于 Lingxi 平台内置定义。配套示例场景维护在 `examples/resource-demo/scenarios`，只有用户确认导入后才会安装到 Lingxi。

开发能力包时仍可运行以下命令生成 ZIP，用于单包校验：

```bash
./examples/resource-demo/skills/package.sh
```

生成目录 `examples/resource-demo/skills/dist/` 只包含构建产物，不应提交到 Git。完整体验流程见上一级 `README.md`。
