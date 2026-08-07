# 同一次执行恢复

这是同一次执行在中断前的持久化检查点，工作区文件和产物仍然保留。
`SUCCESS` 表示该动作已完成，引用的输出仍存在时不得重复执行。
`RUNNING` 表示该动作在中断时尚未确认完成，除非后面存在对应的 `SUCCESS` 记录。

## 稳定任务输入

- `.agent-task/task.md`：原始任务目标和用户输入。
- `.agent-task/context.md`：平台已经准备的结构化上下文。
- `.agent-task/scenario.md`：当前场景流程和边界。
- `.agent-task/capabilities.md`：当前允许使用的能力。

## 检查点事件

${checkpointEvents}
