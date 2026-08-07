---
name: project-hub
description: "通过 Project Hub 兼容协议读取外部项目管理服务中的项目、环境、Git 仓库、项目关系和资源配置。"
---

# 项目管理上下文

本能力通过 Project Hub 兼容协议读取外部项目管理服务中的项目资料，提供项目、环境、Git 仓库、分支口径、项目关系和资源配置。它只负责项目上下文与资源查询，不替调用方决定完整分析流程，也不执行其他能力的动作。

## 能力边界

- 只通过本能力的服务接入配置访问外部项目管理服务，不直接读取外部服务或智能体平台数据库。
- `projectId` 是项目管理服务中的项目 ID；命令名称保留 project-hub 以表达能力用途。
- 环境口径来自项目管理服务；如果命令未指定环境，使用当前任务上下文里的 `environmentCode`。

## 运行上下文
- `projectId` 和 `environmentCode` 是本次任务上下文，不是能力服务配置。
- 命令显式参数优先于任务上下文，但不修改任务上下文本身。
- 命令需要显式指定环境时统一使用 `--environment`；`environmentCode` 是上下文字段名，不是 `--environment-code` 命令参数。
- 项目参数对应项目管理服务中的项目，支持项目 ID、编码或名称。名称能够唯一解析时可以直接读取上下文，不需要先形式化调用项目列表。
- 资源记忆返回的当前 provider 项目引用 `app:<id>` 可以直接作为 `--project-id` 使用；命令会提取项目 ID 并向项目管理服务验证，不需要再按名称查询项目。
- `config-info --project-id` 是可选归属校验；未传时按配置 ID 读取，不使用当前项目覆盖资源真实归属。

## 命令

项目和环境：

```bash
project-hub project-list [--query 关键词] [--keyword 关键词]... [--keywords 逗号分隔关键词]
project-hub environment-list --project-id 项目ID或项目名称
project-hub project-info [--project-id 项目ID或项目名称] [--project-name 项目名称] [--environment 环境编码]
project-hub project-context [--project-id 项目ID或项目名称] [--project-name 项目名称] [--environment 环境编码]
```

- `project-list` 查询精简项目候选；多个关键词按任一词命中。
- `environment-list` 列出指定项目维护的真实环境口径。
- `project-info` 读取项目、关联项目、环境和资源摘要。
- `project-context` 读取包括仓库、环境、关系和资源摘要在内的完整项目资料。

仓库和关系：

```bash
project-hub repository-list [--project-id 项目ID或项目名称]
project-hub repository-info --id 仓库ID [--project-id 项目ID或项目名称] [--environment 环境编码] [--output-file .agent-task/runtime-inputs/repository.json]
project-hub relation-list [--project-id 项目ID或项目名称]
```

- `repository-list` 返回当前环境的仓库候选及分支口径。调用 `repository-info` 前，必须从已有项目上下文或本命令结果取得仓库自身的 `id`。
- `repository-info` 的 `--id` 是仓库 ID，`--project-id` 是项目 ID、编码或名称，两者含义不同；传入 `--environment` 时，导出的仓库配置会包含该环境的部署分支。
- `relation-list` 列出指定项目的上下游、前后端、共享资源及其他业务关系。

资源配置：

```bash
project-hub config-list [--project-id 项目ID] [--environment 环境编码] [--type 配置类型] [--code 配置编码] [--label 标签]
project-hub config-info --id 配置ID [--project-id 项目ID] [--output-file .agent-task/runtime-inputs/config.json]
```

- `config-list` 返回当前项目和环境下的资源配置摘要。
- `config-info` 按配置 ID 读取详情；`--project-id` 只做可选归属校验，`--output-file` 只写出可供后续能力使用的 `config`。

## 选择原则
- 候选数量大于一个不等于必须询问。调用方可以结合用户输入、名称、描述、环境、关系、用途、标签和资源键选择清晰匹配的候选，并保留判断依据。
- 只有多个候选都合理且选择会改变任务对象、资源或结论时，才需要用户确认。展示业务名称和说明，稳定 ID 只作为机器值。
- 不从“测试环境”等上下文名称猜测具体环境编码；应使用明确输入、结构化上下文或环境候选的真实名称和编码判断。
- 已取得的项目信息、上下文和列表结果应复用，不为了流程完整重复调用同义命令。

## 项目与环境
- `project-list` 用于没有明确项目时获取候选。只使用能够识别应用的系统名、项目名、页面、菜单、模块、接口、仓库或业务线线索；用户询问的业务行为、状态、时间、金额和规则词不等于项目线索，不用它们试探项目列表。
- 同时存在多个可靠项目线索时，通过多个 `--keyword` 或一个 `--keywords` 在一次调用中查询，关键词按任一词命中；一次查询无结果后不要继续替换同义词重复调用。
- 没有可靠项目线索时跳过关键词试探，只读取一次精简候选概览；仍无法确定且不同选择会改变结论时再请求用户补充。
- `environment-list` 用于环境会影响资源、分支或结论且当前环境仍不明确时。环境已经明确时直接继续。
- `project-context` 会按传入的 `environmentCode` 返回 `selectedEnvironment`。环境相关任务只能使用 `selectedEnvironment.deploymentBranch` 作为该环境的代码分支；没有匹配环境或部署分支时先补齐环境信息，不从环境名称、仓库字段或分支名称猜测。
- `project-info` 适合基础资料；`project-context` 适合需要仓库、关系或资源摘要的任务。根据所需信息选择，不要求两者依次调用。

## 仓库与关系
- 仓库归属于具体环境。`repository-list` 返回当前环境的仓库摘要和分支口径；`repository-info` 返回仓库详情，并可通过 `--output-file .agent-task/runtime-inputs/文件名.json` 写为结构化文件。不要拼接 `/root`、`/workspace` 等宿主机路径。
- `repository-info --output-file` 成功后返回 `preparedResource`；后续步骤直接复用该文件，不要重新获取同一仓库详情。
- 仓库的 `baseBranch` 是 Review、差异比较或创建变更时使用的基准分支，不代表任何运行环境。传入当前环境后，`repository-info` 生成的仓库配置会携带该环境的 `deploymentBranch`。
- `relation-list` 和应用上下文中的关系用于理解前后端、上下游、共享资源和调用边界。关系只是线索；进入关联应用需要与当前问题相关的入口、接口、字段、状态、配置、数据或运行证据。

## 资源配置
- `config-list` 返回可筛选的资源摘要；`config-info` 按 ID 返回完整配置，并可只把 `config` 写到本地 JSON 文件。
- `config-info --output-file .agent-task/runtime-inputs/文件名.json` 成功后返回 `preparedResource`；确认需要该资源时立即导出并在后续步骤直接复用，不要只保留配置名称或 ID。
- 列表用于发现和筛选，详情用于真正使用。不要把大量列表详情全部复制到对话，也不要把列表摘要当成完整配置。
- 同一项目可以有多个同类型资源。调用方根据任务证据选择一个或多个详情，不因候选数量大于一报错或停止。
- 配置是普通结构化数据。项目管理服务不解释后续由哪个能力消费，也不依赖其他能力内部状态。
- 不在可见回答中泄露账号、令牌、密码或连接密钥。

项目管理上下文输出用于建立上下文和资源入口。调用方应依据任务目标自主决定下一步，不因为存在某类资料就强制使用对应资源。
