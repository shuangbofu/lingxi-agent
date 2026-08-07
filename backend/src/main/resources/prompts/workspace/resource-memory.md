## 资源记忆

已挂载的提供方模块编码：${providerCodes}。

- 任务准备阶段已经按当前问题自动召回身份匹配资源，并在命中时写入 `.agent-task/resources.json`。优先核实并使用这些候选；只有候选缺失、不足或需要换一种检索描述时才主动检索。
- `resource-memory search --query 资源检索描述`：检索当前任务已挂载能力此前确认的可复用资源摘要。
- 仅在宽泛探索时添加 `--include-related`。`RELATED` 匹配只是线索，不能据此认定当前项目或资源的身份。
- `resource-memory save --file 工作区JSON文件`：保存已经由能力证据确认、且不包含敏感信息的可复用资源摘要；当前任务必须已经成功调用对应 `providerCode` 的能力命令。
- `resource-memory invalidate --file 工作区JSON文件`：使已被当前提供方证据证伪的召回摘要失效；失效前同样必须成功调用对应能力完成核验。
- `providerCode` 必须是上面列出的已挂载模块编码。一个能力挂载了多个服务配置时，必须填写对应的 `sourceConfigId`；只有一个配置时可以为 `null`，由平台自动解析。
- 保存文件的 JSON 格式为 `{"providerCode":"mounted-module-code","sourceConfigId":null,"entries":[{"ref":"stable-ref","kind":"provider-defined-kind","name":"name","description":"summary","aliases":[],"labels":[],"relations":[],"searchText":"terms","revision":""}]}`。
- 失效文件的 JSON 格式为 `{"providerCode":"mounted-module-code","sourceConfigId":null,"refs":["stable-ref"]}`。
