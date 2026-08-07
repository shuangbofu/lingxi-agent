# Lingxi 示例资源服务

这是一个独立运行、只用于本地体验和协议参考的资源服务。它用页面维护项目、环境、仓库、数据库、Markdown 文档和示例日志，并提供 `project-hub`、`wiki-ingest`、`http-log-read` 所需的兼容接口。

资源按实际归属组织：

```text
项目
  -> 环境
      -> 仓库
      -> 数据库
      -> 日志资源
```

同一项目的测试、生产等环境可以分别填写不同的仓库地址、分支、数据库连接和日志数据。

## 启动方式

### 使用 JAR

适合已经安装 JDK 17、不想使用 Docker 的环境。将发布包中的 Demo JAR 放到当前目录并命名为 `resource-demo.jar`：

```bash
cd examples/resource-demo
./start.sh
```

源码开发时，脚本也会识别 `backend/target/lingxi-resource-demo-*.jar`。脚本只负责启动已有 JAR，不会安装 Node.js、Maven 或自动构建源码。

### 使用 Docker

适合已经安装 Docker、不想在宿主机准备 JDK、Node.js 和 Maven 的环境：

```bash
cd examples/resource-demo
docker compose up --build -d
```

打开 `http://localhost:8091`。首次启动会自动创建 OpenAI Codex、OpenHands、Cline 三个真实的开源 Agent 项目，为每个项目创建一篇阅读线索文档，并提供一组 `demo-trace-1001` 示例日志。

如需改变访问令牌或 Lingxi 实际访问地址，在启动前设置：

```bash
export DEMO_ACCESS_TOKEN='replace-with-your-demo-token'
export DEMO_PUBLIC_BASE_URL='http://localhost:8091'
docker compose up --build -d
```

`DEMO_PUBLIC_BASE_URL` 必须同时能被 Lingxi Runtime 和浏览器访问。Lingxi 和 Demo 都使用 Docker 时，建议直接在仓库根目录执行 `docker compose up --build -d`，根目录 Compose 已处理两个服务之间的网络。

## 接入 Lingxi

1. 打开 Demo 的“接入 Lingxi”页面。
2. 勾选要安装的能力和场景，填写 Lingxi 页面地址，然后点击“导入已选”。通过仓库根目录 `./start.sh` 启动时填写 `http://localhost:8080`；前后端分开开发时通常是 `http://localhost:5173`；生产部署填写实际页面地址。
3. 登录 Lingxi 后，在场景管理页确认来源和安装清单。

套件包含三部分：

- `project-hub`、`wiki-ingest`、`http-log-read` 三个外置 Skill。
- `project-hub`、`wiki-ingest` 的示例服务接入配置。
- 10 个可由当前 Lingxi 与 Demo 能力完整执行的外置场景。默认勾选“业务问答”“文档分析”“故障分析”；不发布依赖缺失能力的场景。

Demo 负责展示和选择演示内容，通过浏览器消息把清单交给已经打开的 Lingxi 场景管理页。Lingxi 使用自己的登录态下载所选 ZIP，并调用正式能力、场景安装接口；平台没有 Demo 专用菜单或路由。Demo 不会拿到 Lingxi 的管理员账号、密码、Cookie 或 Token。

示例场景的 manifest 与 prompt 均维护在 `examples/resource-demo/scenarios` 并随 Demo JAR 发布。Lingxi 默认没有场景，这些文件只会在用户确认导入后安装到 Lingxi。重复导入会更新同编码外置场景和同名接入配置；手工创建的同编码场景不会被安装包覆盖。

场景执行时会先通过 `project-hub` 取得对应项目和环境下的仓库、数据库或 HTTP 日志资源，再交给相应能力使用。数据库地址、仓库地址和日志资源都属于环境，不作为项目级固定配置。

管理页右上角的“接入信息”会显示当前服务地址和令牌。完成一次套件安装后，后续只需要在 Demo 页面维护数据。

可以直接验证：

- 业务问答：询问“Demo 中有哪些开源 Agent 项目？OpenAI Codex 的仓库地址和默认分支是什么？”
- 文档分析：选择“OpenAI Codex”，询问“Codex 项目建议从哪些方向分析？”
- 故障分析：选择“OpenAI Codex”的“开源主线”环境，输入 TraceId `demo-trace-1001`，询问代码分析为什么中断。

## 本地开发

后端使用 JDK 17、Spring Boot、JPA、H2 和 Lucene，监听 `8091`：

```bash
cd examples/resource-demo/backend
mvn spring-boot:run
```

Maven 构建会直接从 `examples/resource-demo/skills` 生成 Demo 对外提供的三个白名单能力包，并将同级 `scenarios` 中的场景定义一起打入 JAR；`skills/dist` 不是运行依赖。

前端使用 React、TypeScript、Tailwind CSS 和 Ant Design，监听 `4180`：

```bash
cd examples/resource-demo/frontend
yarn install --frozen-lockfile
yarn dev
```

服务启动后，可以从仓库根目录运行三种 Skill 的端到端契约检查：

```bash
python3 examples/resource-demo/scripts/skill_contract_smoke.py
```

## 协议范围

- 项目：项目列表、项目上下文、环境、关系，以及按环境管理的仓库、数据库和 HTTP 日志资源配置。
- 文档：列表、目录、Lucene 单词/多词检索、分段读取、上下文、Markdown 原文、版本、变化摘要和简单差异。
- 日志：健康检查、虚拟日志文件列表和尾部读取；关键词搜索由 `http-log-read` 在读取结果上完成。
- 媒体：Markdown Demo 不保存图片，媒体列表为空，重新分析会明确返回不支持。

Markdown 原文和版本保存在 H2 中，H2 是权威数据源。Lucene 使用 `SmartChineseAnalyzer` 索引标题、路径和正文：服务启动时从 H2 全量重建内存索引，文档新增、修改和删除时增量更新。`LITERAL`、`ALL_TERMS`、`ANY_TERMS` 分别对应短语、全部词项和任一词项检索，所有检索都会按项目编码隔离。

## 安全边界

这个服务是公开示例和开发沙箱，不是生产项目管理、Wiki 或日志平台。管理接口没有登录保护，页面会展示 Skill 接入令牌；不要将它暴露到不可信网络，也不要录入私有仓库凭证、生产地址、真实用户数据或生产日志。
