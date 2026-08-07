# Agent Runtime Modules

This directory contains runtime-neutral contracts and external agent runtime implementations.

```text
runtime
|- runtime-base
|- codex-runtime
`- langchain-runtime
```

Each module is also split by responsibility instead of keeping implementation classes in one root package:

```text
runtime-base/api
|- capability
|- event
|- execution
|- maintenance
`- model

codex-runtime/codex
|- cli
|- config
|- core
`- maintenance

langchain-runtime/langchain
|- agent
|- capability
|- config
|- core
|- mcp
`- process
```

## Dependency Rules

- `runtime-base` is pure Java. It must not depend on Spring, JPA, Workbench entities, or a concrete agent.
- `codex-runtime` depends on `runtime-base` and owns Codex event models, CLI configuration, version detection,
  installation and maintenance.
- `langchain-runtime` depends on `runtime-base`. Its auto-configuration remains inactive until a
  `LangChainRuntimeDelegate` implementation exists.
- Runtime registration, task persistence, credentials, capability preparation, and event persistence belong to `backend`.
- Runtime implementations must emit normalized events and usage through the contracts in `runtime-base`.
- Message streaming is optional. Streaming runtimes emit `RuntimeMessageDelta` values with a stable message ID and
  still emit one complete `AGENT_MESSAGE` when the message finishes. Non-streaming runtimes only emit the complete
  message. The backend forwards deltas as transient SSE data and persists only the complete message.
- Each implementation owns its Spring Boot auto-configuration. Adding a runtime must not add a runtime-specific bean to
  `backend`.
- Runtime maintenance is optional and self-described through `RuntimeDescriptor.maintenanceSupported`. The backend only
  routes generic maintenance requests to the selected runtime; runtimes without maintenance support expose no UI entry.

The Codex bridge preserves the existing executor behavior while the platform-facing task flow depends only on
`AgentRuntime`. Codex execution emits `RuntimeEvent`, `RuntimeUsage`, and `RuntimeExecutionResult` directly; only the
task boundary maps those contracts to persisted task data.

The LangChain runtime is a native LangChain4j agent loop. It consumes the same compiled scenario prompt and installed
capability CLI registry as Codex. Capability commands declare typed outputs in their manifest. When an installed command
can produce a `code-worktree`, LangChain exposes read-only Codebase Memory tools and automatically adapts the returned
worktree into a task-scoped index. The capability does not know about MCP, while LangChain does not know which capability
or command produced the worktree. Index projects are keyed by repository identity and Git commit, so tasks and branches
that resolve to the same commit share one immutable index. Concurrent builds for the same commit are coalesced, and only
the configured number of recent revisions is retained per repository. MCP is therefore a runtime-specific code retrieval
dependency, not the platform capability protocol and not a Codex dependency.

## Adding A Runtime

1. Add a child module under this directory and depend on `runtime-base`.
2. Implement `AgentRuntime`, or provide the module's runtime delegate, including execution, cancellation, and any
   supported session, usage or maintenance operations.
3. Publish the implementation through Spring Boot auto-configuration in that module. Conditional registration must not
   expose an unfinished runtime.
4. Add the module dependency to the application packaging. The runtime registry and settings API discover it
   automatically.
