---
name: code-repository
description: "读取仓库和分支信息，准备隔离 worktree，比较分支、查询提交历史，并在明确的代码变更任务中准备和提交临时分支。"
---

# 代码仓库

代码仓库能力用于把显式传入的标准仓库配置解析成可读取的本地代码路径。它只关心仓库 URL、仓库标识和 Git 分支，不关心这些配置来自哪个系统，也不解释项目或环境的业务语义。

## 能力边界

- 默认只做仓库读取、同步、分支解析、任务级 worktree 准备、提交历史查询和只读 diff。
- 默认检出分支按 `checkoutBranch`、`deploymentBranch`、`branch`、`defaultBranch` 的顺序读取；`baseBranch` 只表示代码比较或变更的基准。
- 仓库对象使用固定共享 mirror 缓存；只读分析按仓库 Commit 共享稳定 checkout，不随任务重复检出，也不切换共享目录的分支状态。
- 只有显式调用变更命令时，才允许在任务隔离 worktree 内提交并推送当前任务临时分支。
- `prepare-change` 创建的可写 worktree 只属于当前任务；只读共享 checkout 不用于修改、提交或推送。
- 如果仓库配置或分支无法唯一确定，返回候选信息，由场景或用户交互决定下一步。

## 何时使用

当任务需要访问代码仓库或比较分支时，使用这个能力取得当前任务可访问的代码目录或差异信息。

## 命令

### 列出可用仓库

```bash
code-repo list --config-file /path/repository.json [--branch-query 分支名称片段] [--git-key-directory Git-Key目录]
```

用于查看指定仓库配置和真实 Git 分支候选。多个仓库同时存在时，调用方根据任务对象、仓库说明和项目关系选择需要的仓库，并把对应配置写入本次任务临时 JSON 文件后传给 `--config-file`。多个仓库都与任务相关时可以分别使用，不要求压缩成单个仓库。

返回里的 `gitBranchOptions` 来自 Git 仓库真实分支，适合用于目标分支或分析分支选择。`branchOptions` 是项目资料里维护的默认/生产/测试/开发分支口径，只能作为上下文说明或基准分支优先级，不要把它当作目标分支候选让用户选择。

分支较多时返回结果会截断，并同时返回候选总数。已知分支名称线索时使用 `--branch-query` 过滤，不要把完整分支列表反复带入分析上下文。

### 准备仓库 worktree

```bash
code-repo prepare --config-file /path/repository.json [--branch 分支名] [--git-key-directory Git-Key目录]
```

返回当前任务可读取的 `worktreePath`。调用方可以在这个目录内自主选择合适的分析方式。

配置提供默认检出分支时，`prepare` 直接使用；没有提供时读取 Git 远端真实候选。调用方必须在生成配置文件前完成项目和环境选择，不能要求本能力根据 `environmentCode` 猜测分支。`baseBranch` 只用于 Review、diff 或创建变更的基准，不得代替检出分支。

仓库 Git 对象和只读 checkout 由能力复用。同一仓库的同一 Commit 已经准备完成时，`prepare` 直接返回原路径并将 `cacheHit` 标记为 `true`，不会为新任务重复检出。调用方只需要使用命令返回的 `worktreePath`，不需要理解内部缓存实现。

没有配置默认分支或显式分支时，能力直接读取 Git 远端真实候选。只有一个候选时直接使用；存在多个候选时返回 `NEED_BRANCH`，调用方结合任务目标判断，多个合理选择会改变结果时再让用户选择。不解释默认、测试、生产、UAT 等环境名称。

### 查看分支差异

```bash
code-repo diff --config-file /path/repository.json --base 基准分支 --target 目标分支 [--git-key-directory Git-Key目录]
```

用于分支 Review 或测试报告场景。命令会准备目标分支的隔离 worktree，并返回文件级 diff 摘要。

### 查询提交历史

```bash
code-repo history --worktree-path /path/worktree --path relative/file [--search 代码片段] [--line 行号] [--all-refs] [--limit 20]
```

用于回答代码何时引入、由谁修改或某一行来自哪个提交。先准备任务实际需要核查的分支一次，再对返回的 `worktreePath` 调用本命令；不要为了追溯历史逐个准备其他分支。

`--search` 按代码片段追溯增删记录，并优先返回最早匹配的提交及对应补丁；`--line` 返回指定行的 blame 信息。只有需要检查尚未合入当前分支的历史时才使用 `--all-refs`。

### 准备变更 worktree

```bash
code-repo prepare-change --config-file /path/repository.json --base 基准分支 [--description 英文需求描述] [--git-key-directory Git-Key目录]
```

仅在任务明确要求修改代码时使用。命令基于指定分支创建当前任务独占的可写 worktree，并切换到 `lingxi-登录用户-时间-英文需求描述` 格式的临时分支。不要在 `prepare` 返回的共享只读 checkout 中修改文件。

### 提交并可选推送变更

```bash
code-repo commit --worktree-path /path/worktree --message 提交说明 [--push] [--git-key-directory Git-Key目录]
```

仅允许提交 `prepare-change` 为当前任务创建的临时分支 worktree。只有任务明确要求推送时才传 `--push`；未获得该授权时只保留本地提交。

## 调用说明

仓库配置可以来自任意可信来源。推荐使用顶层对象或 `repository` 对象，至少提供 `url` 和稳定的 `repositoryCode`；可选提供 `name`、`description`、`checkoutBranch`、`baseBranch`、`role`。兼容的 URL 字段包括 `gitUrl`、`repositoryUrl`、`sshUrl`、`httpUrl` 和 `cloneUrl`。提供方内部数据结构可以不同，但交给本能力前必须转换成这份公开配置契约。

`gitKeyDirectory` 是分析情境为当前 Lingxi 执行环境提供的 Git Key 目录。参数有值时，访问远端仓库的命令必须原样传入 `--git-key-directory`；不要猜测、拼接或改写目录。目录必须包含标准 OpenSSH `config`，各平台私钥由 `config` 的 `Host` 和 `IdentityFile` 选择。私钥文件只由 Git/SSH 进程读取，不得读取、复制或输出其内容。参数为空时使用 Lingxi 服务进程原有的 Git/SSH 环境。

`gitBranchOptions` 是可用事实，不是强制交互信号。候选数量本身不要求追问；任务意图能明确分支时直接执行，存在多个合理分支且选择会改变分析或 diff 时再使用结构化交互。

`code-repo prepare` 返回 `NEED_BRANCH` 时，应补充分支后重试；如果分支不存在，说明当前仓库没有该分支。
