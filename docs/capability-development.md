# 能力开发与 Skill 接入范式

本文定义 Lingxi 能力包的当前开发范式，适用于两类工作：

1. 从零开发一个可被 Agent 使用的新 Skill。
2. 将已有的标准 Agent Skill 接入 Lingxi。

这套范式本身可以整理成一个开发辅助 Skill，交给 AI 后由 AI 完成目录生成、`SKILL.md` 编写、`lingxi.json` 适配、脚本实现和校验。本文先固定输入、产物和边界，避免每个能力重复发明接入协议。

## 1. 核心模型

Lingxi 中的一个能力包由两层组成：

```text
标准 Agent Skill                 Lingxi 平台适配
SKILL.md                         lingxi.json
references/                      presentation
scripts/                         taskParameters
assets/                          configurationParameters
                                 entrypoint / commands / outputs
```

- `SKILL.md` 是模型可读的标准 Skill，描述它何时适用、如何工作、有哪些边界以及如何调用公开业务命令。
- `lingxi.json` 是 Lingxi 的平台扩展，描述中文展示、版本、表单参数、私有执行入口、可授权命令和结构化输出绑定。
- `scripts/` 是可选的私有实现目录。Lingxi 当前只支持 Python `entrypoint`；模型只能看到公开业务命令，不能直接读取或执行该目录中的文件。
- `references/` 保存只有在特定步骤需要时才读取的长说明，不能用于拆散每次执行都必须遵守的核心规则。

因此，“Lingxi 能力”和“标准 Skill”不是两套平行协议。Lingxi 能力是标准 Skill 加一个薄的平台适配层。当前安装流程要求能力包同时包含 `SKILL.md` 和 `lingxi.json`；纯说明型 Skill 也需要最小 `lingxi.json`，但可以不声明 `entrypoint` 和 `commands`。

## 2. 默认与外置

默认和外置只表示来源，不改变能力协议；两者最终都进入统一安装目录。

| 类型 | 源码位置 | 安装后的来源 | 适用范围 |
| --- | --- | --- | --- |
| 默认 Skill | `backend/src/main/resources/skills/<name>/` | 启动时同步到 `data/installed-skills/<name>/` | 随项目提供、部署方可以修改或移除的默认能力 |
| 外置 Skill | 独立仓库或本地 `external-skills/<name>/` | 上传后进入 `data/installed-skills/<name>/` | 业务系统、组织内部集成和独立发布的能力 |

`external-skills/` 仅供本地开发且已被 Git 忽略，不能直接复制到 resources。外置能力以 ZIP 安装包发布：首次安装默认关闭，管理员完成接入配置和场景绑定后再启用；同编码更新会替换包文件并保留启用状态、场景绑定和已有接入配置。

默认与外置能力使用同一套运行时加载、命令授权和输出协议。同一编码不能同时被默认和外置能力占用，也不能用外置安装包覆盖手工创建的自定义能力。

## 3. 标准目录

```text
my-skill/
  SKILL.md                 # 必需：标准 Skill 身份、工作流和边界
  lingxi.json              # 必需：Lingxi 平台适配
  assets/
    icon.svg               # 可选：平台展示图标
  references/
    protocol.md            # 可选：按需加载的长资料
  scripts/
    my_skill.py            # 可选：唯一私有执行入口
    requirements.txt       # 可选：Python 依赖
    test_my_skill.py       # 推荐：有业务逻辑或副作用时提供
```

目录名必须与 `SKILL.md` frontmatter 的 `name` 完全一致，使用最多 64 位的小写连字符格式，例如 `project-hub`。

ZIP 可以直接以能力目录为根，也可以只包含一个一级能力目录。安装包不得包含符号链接；上传大小上限为 20 MB，解压后大小上限为 50 MB。

## 4. `SKILL.md` 范式

### 4.1 Frontmatter

```markdown
---
name: my-skill
description: "读取某类外部资料，并将经过校验的结果提供给上层 Agent。"
---
```

- `name` 是稳定身份，也是目录名和场景引用编码。
- `description` 用于触发和能力目录选择，应说明“何时使用”和“能得到什么”，不要写宣传文案。
- 可使用的标准扩展字段只有 `license`、`allowed-tools` 和 `metadata`。Lingxi 字段不能塞进 frontmatter。

### 4.2 正文结构

推荐按以下顺序编写：

````markdown
# 能力名称

一句话说明能力提供什么，不负责什么。

## 能力边界
- 数据来源与权限边界
- 明确不负责的业务编排
- 是否只读、是否存在副作用

## 命令
```bash
my-skill list [--query 关键词]
my-skill info --id ID
```

说明每个命令的输入、输出和使用条件。

## 使用原则
- 候选发现与详情读取如何分层
- 哪些步骤可以并行，哪些必须等待前一步结果
- 何时询问用户，何时可以依据证据自行选择
- 如何处理大结果、文件结果和敏感值

## 安全边界
- 参数、路径和外部返回值校验
- 写操作的确认要求
- 凭证和日志脱敏要求
````

Skill 正文必须描述公开业务语义，不能出现以下内容：

- `scripts/my_skill.py` 等私有脚本路径。
- `data/installed-skills/`、宿主机绝对路径或某个 Runtime 的内部目录。
- “先调用另一个能力”的硬编码编排。多个能力如何组合由场景和 Agent 决定。
- 与 `lingxi.json` 重复的平台展示、版本、表单定义。
- 为了兼容错误输入而无限兜底。输入契约不成立时应返回清晰错误。

公开命令统一使用 `group action`，例如 `project-hub project-context`。命令组和动作都使用小写连字符；一个能力包只有一个 `entrypoint`，由入口根据动作分派，不能为每个动作暴露一个私有脚本。

## 5. `lingxi.json` 范式

完整结构以 [`lingxi-capability.schema.json`](./schemas/lingxi-capability.schema.json) 为准。

| 字段 | 必需 | 用途 |
| --- | --- | --- |
| `schemaVersion` | 是 | 当前固定为 `1` |
| `version` | 是 | 能力包版本，建议使用语义化版本 |
| `presentation.displayName` | 是 | 平台中文展示名 |
| `presentation.icon` | 否 | 包内图标相对路径 |
| `enabledByDefault` | 是 | 内置定义的默认启用状态；外置首次安装仍默认关闭 |
| `taskParameters` | 是 | 每次任务的输入或上下文参数 |
| `configurationParameters` | 是 | 管理员维护的服务接入配置 |
| `guides` | 否 | 平台可管理的 `references/` 补充说明 |
| `entrypoint` | 可执行能力必需 | `scripts/` 下唯一的 Python `.py` 私有入口 |
| `commands` | 否 | 可授权的公开 `group action` 命令 |

`lingxi.json` 不得重复声明 Skill 的 `name`、`description` 或工作流。能力身份始终以 `SKILL.md` 为准。

标准 Agent Skill 规范本身不限定 `scripts/` 内的语言，但这不代表 Lingxi 可以直接运行任意脚本。当前 Codex Runtime 不投影 `scripts/`，LangChain Runtime 也只通过公开命令调用平台 launcher；launcher 固定使用 Python 解释器执行 `entrypoint`。Shell、Node 和 Java 文件不能直接声明为 Lingxi 入口。

### 5.1 纯说明型 Skill

```json
{
  "$schema": "https://raw.githubusercontent.com/shuangbofu/lingxi-agent/main/docs/schemas/lingxi-capability.schema.json",
  "schemaVersion": 1,
  "version": "1.0.0",
  "presentation": {
    "displayName": "示例说明能力",
    "icon": "assets/icon.svg"
  },
  "enabledByDefault": true,
  "taskParameters": [],
  "configurationParameters": []
}
```

外置包中的 `$schema` 路径不一定能指向平台仓库，可以省略；安装端仍会按当前协议校验。

### 5.2 可执行 Skill

```json
{
  "schemaVersion": 1,
  "version": "1.0.0",
  "presentation": {
    "displayName": "示例查询",
    "icon": "assets/icon.svg"
  },
  "enabledByDefault": true,
  "entrypoint": "scripts/example_query.py",
  "taskParameters": [
    {
      "key": "projectId",
      "name": "项目",
      "type": "text",
      "required": false,
      "visible": false,
      "description": "本次任务已经确认的项目标识。"
    }
  ],
  "configurationParameters": [
    {
      "key": "baseUrl",
      "name": "服务地址",
      "type": "text",
      "required": true,
      "description": "外部服务根地址。"
    },
    {
      "key": "token",
      "name": "访问令牌",
      "type": "password",
      "required": true,
      "description": "外部服务访问令牌。"
    }
  ],
  "commands": [
    {
      "command": "example list",
      "displayName": "查询候选",
      "description": "按关键词返回轻量候选。"
    },
    {
      "command": "example info",
      "displayName": "读取详情",
      "description": "按稳定 ID 返回完整详情。",
      "outputs": [
        {
          "type": "example-resource",
          "pathField": "outputPath",
          "features": ["file-access"]
        }
      ]
    }
  ]
}
```

参数的常用 `type` 包括 `text`、`textarea`、`password`、`number`、`switch`、`date`、`time` 和 `datetime`。固定候选使用 `options`；`visible: false` 适合由分析情境或前序交互写入、但不直接展示在任务表单中的参数。

`taskParameters` 和 `configurationParameters` 不能混用：

- “本次查哪个项目、哪个环境”属于任务上下文。
- “用哪个服务地址、令牌和租户接入”属于服务配置。
- 后端凭证不能放进任务参数，更不能由 Agent 在命令参数中回显。

`commands[].displayName` 必须是可以直接与“正在”“已”“失败”组合的动作短语，例如“读取项目上下文”，不能只写“项目上下文”。

### 5.3 结构化输出

当命令会产出可被 Runtime 继续消费的资源时，使用 `commands[].outputs`：

- `type` 是跨能力、跨 Runtime 的资源类型。
- `pathField` 指向命令 stdout JSON 对象中的路径字段。
- `features` 声明资源特征。`file-access` 表示 LangChain Runtime 可以把该目录注册给通用文件工具；`code-index` 等特征还可以触发匹配的 MCP 挂载。

声明了 `outputs` 的命令必须在 stdout 返回 JSON 对象，且 `pathField` 对应值不能为空。普通日志写 stderr，不要混入 stdout 破坏 JSON。

## 6. 私有入口协议

平台为每个任务生成受控命令入口。Agent 执行：

```bash
example info --id 42
```

私有入口实际接收：

```text
python scripts/example_query.py example.info --id 42
```

第一个参数是 `group.action` 选择器，后面才是公开命令参数。入口应显式分派已声明动作，对未知动作返回非零退出码和清晰 stderr。

Python 能力可以直接：

```python
import capability_runtime as runtime

service = runtime.service_config_data("example", required=True)
project_id = runtime.runtime_context_value("projectId")
```

`capability_runtime` 由平台在任务运行时注入，不应复制进能力包。它用于读取当前能力的服务配置、任务上下文，以及确有需要时发起平台交互。能力脚本仍需自行校验外部响应、命令参数和文件路径。

平台还会提供 `AGENT_RUNTIME_WORKSPACE` 等受控环境信息，但业务代码优先使用 `capability_runtime` 访问平台数据。不要读取数据库、猜测平台安装目录或自行持久化访问令牌。

如果存在 `scripts/requirements.txt`，平台按 Python、操作系统、架构和文件哈希安装到共享缓存。依赖必须固定到合理版本并控制规模；不要把大型运行环境隐式塞进首次调用。

## 7. 将标准 Skill 接入 Lingxi

已有标准 Skill 的适配流程如下：

1. 保留原 `SKILL.md` 的标准身份和可移植工作流，确认目录名与 `name` 一致。
2. 删除正文中对特定宿主机路径、私有脚本路径和未授权工具的依赖。
3. 新增最小 `lingxi.json`，补充展示名、版本、启用状态和空参数数组。
4. 如果 Skill 只指导模型使用已有 Runtime 工具，到此即可，不要为了“可执行”额外造脚本。
5. 如果 Skill 自带可执行命令，收敛为一个 Python `scripts/` 入口，把公开动作整理成不冲突的 `group action`。
6. 将长期接入信息拆到 `configurationParameters`，将单次任务信息拆到 `taskParameters`。
7. 对文件或目录结果补充 `outputs`，并让 stdout 满足 JSON 契约。
8. 在 Codex 和 LangChain 两种 Runtime 语义下检查：模型只依据 `SKILL.md` 选择能力，只通过公开命令执行，不依赖某个 Runtime 的私有工具名。

不要把“适配标准 Skill”理解为把整个 Skill 正文复制进 `lingxi.json`，也不要把 Lingxi 的安装与鉴权细节反向污染标准 Skill。

## 8. 从零开发 Skill

开发前先回答五个问题：

1. 这个能力提供哪一种独立能力，而不是哪一个完整业务场景？
2. Agent 用什么最小线索发现候选，用什么稳定标识读取详情？
3. 哪些输入是任务上下文，哪些是管理员接入配置？
4. 哪些动作有副作用，需要预览、确认、审计或回滚？
5. 大结果如何落文件并以结构化摘要返回？

随后按以下顺序实现：

1. 先写 `SKILL.md` 的边界、命令和证据使用原则。
2. 再写 `lingxi.json`，只补平台需要的元数据。
3. 最后实现单入口脚本和必要测试，使代码服从公开命令契约。
4. 使用实际失败输入检查参数、路径、超时、外部错误和敏感值处理。

能力不应承担场景编排。比如“定位项目 -> 拉取仓库 -> 查数据库 -> 生成报告”属于场景与 Agent 的工作；项目查询、仓库准备、数据库查询分别是独立能力。

## 9. 交给 AI 的任务模板

将下面的约束与目标系统资料一并提供给 AI；缺少业务协议时应让 AI 先指出缺口，不要让它猜接口。

```text
请按照 docs/capability-development.md 开发或适配一个 Lingxi 能力包。

目标能力：<一句话说明独立能力>
数据/工具来源：<API、CLI、文件或已有 Skill>
允许动作：<只读动作和写动作>
任务上下文：<每次任务变化的值>
服务配置：<管理员维护的地址、凭证、租户等>
期望资源输出：<类型、路径字段、features>
发布方式：<内置或外置 ZIP>

要求：
- 先检查现有 Skill 和协议，不重复造实现。
- SKILL.md 保持标准和可移植；Lingxi 字段只写入 lingxi.json。
- 可执行能力只有一个 entrypoint，公开命令使用 group action。
- 不在日志、stdout、Prompt 或命令参数中泄露凭证。
- 不在能力里硬编码其他能力或完整场景流程。
- 对不成立的输入契约直接报错，不堆兼容补丁。
- 完成目录、实现、必要测试、校验和 ZIP 打包说明。
```

## 10. 校验与发布

内置能力提交前执行：

```bash
python3 scripts/validate_definitions.py
python3 -m compileall -q backend/src/main/resources/skills
```

有独立测试脚本的能力还应直接运行对应测试。外置能力在独立目录完成测试后打 ZIP，并通过管理端“能力 -> 安装能力”先预检再确认安装。安装端会检查 `SKILL.md`、`lingxi.json`、图标、references、入口、命令格式、结构化输出声明、路径越界、包大小和符号链接。

最小验收清单：

- 目录名、Skill `name`、场景引用编码一致。
- `SKILL.md` 不包含 Lingxi 私有实现知识。
- `lingxi.json` 不重复 Skill 工作流。
- 命令只暴露已实现的 `group action`，入口能拒绝未知动作。
- 配置、任务上下文和命令参数职责清楚。
- stdout 可被机器解析，stderr 可供诊断，退出码可靠。
- 敏感信息不会进入事件、日志、产物和模型上下文。
- 外置能力能以 ZIP 预检、安装、配置、绑定场景并在两种 Runtime 下执行。
