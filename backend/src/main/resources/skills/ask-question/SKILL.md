---
name: ask-question
description: "在分析过程中向用户发起必要的补充问题、确认、单选或多选，拿到反馈后继续执行。"
---

# 用户追问

当缺少必要上下文会明显影响分析方向、证据范围或最终结论时，可以向用户提出问题并等待反馈。这个能力支持文本、是否、日期、日期时间、确认、下拉选择、单选和多选，不只是文本输入框。

## 能力边界

- 只用于向当前任务用户请求补充信息，不做业务判断。
- 问题要具体、短，优先给出可选项；需要自由补充时再用文本输入。
- 不要把所有不确定都抛给用户；只有影响分析方向或准确性时才提问。
- 同一轮任务内避免连续频繁提问，能一次收集的信息尽量一次问清。
- 如果回答会影响后续步骤，使用语义稳定的 `--context-key` 写回任务上下文；优先复用定义参数，动态表单可以定义本次任务内的局部上下文键。

适合使用的情况：

- 当前任务缺少会影响执行方向、证据范围或输出口径的关键信息。
- 同一个问题存在多个合理候选，继续执行会走向不同路径。
- 需要用户在多个处理口径中选择一个。
- 需要用户补充一段文本、编号、范围、条件或说明。

不适合使用的情况：

- 通过已有工具和上下文可以自行确认。
- 只是轻微不确定，可以在结果中标注假设和未验证项。
- 提问会打断明显可以继续推进的分析。

命令会返回用户提交的结构化答案，拿到答案后继续执行当前任务。

需要用户先阅读计划、SQL、差异、风险或其他 Markdown 内容再操作时，使用 `--content` 或 `--content-file`。内容会显示在问题和表单之间；较长内容优先写入任务工作区文件后使用 `--content-file`，不要只给用户服务器本地路径。

返回结果里的 `status` 表示用户动作：

- `ANSWERED`：用户提交了明确答案，可以继续使用 `answerText` 或 `selectedValues`。
- `SKIPPED`：用户选择跳过，本次问题没有可用答案。
- `UNKNOWN`：用户表示不知道，不要把它当作否定答案。
- `CANCELED`：用户取消本次确认，应停止当前依赖该答案的后续步骤，或说明无法继续。
- `EXPIRED`：用户长时间未反馈，应按缺少信息处理。

## 命令选择

### 文本补充、确认、是否、日期

```bash
ask-question ask --question 问题 [--content Markdown内容 | --content-file /path/preview.md] [--input-type TEXT|CONFIRM|YES_NO|DATE|DATETIME] [--default-value 默认值] [--actions JSON数组] [--context-key 上下文键]
```

适用于没有候选列表，只能让用户补充描述、时间范围、业务线索、是否判断或确认是否继续。

更明确的命令也可以直接使用：

```bash
ask-question yes-no --question 问题 [--content Markdown内容 | --content-file /path/preview.md] [--default-value yes|no] [--actions JSON数组] [--context-key 上下文键]
ask-question date --question 问题 [--content Markdown内容 | --content-file /path/preview.md] [--default-value YYYY-MM-DD] [--actions JSON数组] [--context-key 上下文键]
ask-question datetime --question 问题 [--content Markdown内容 | --content-file /path/preview.md] [--default-value 'YYYY-MM-DD HH:mm:ss'] [--actions JSON数组] [--context-key 上下文键]
```

### 候选选择

```bash
ask-question choose --question 问题 --options '[{"label":"展示给用户的名称","value":"稳定机器值","description":"可选说明"}]' [--content Markdown内容 | --content-file /path/preview.md] [--default-value 推荐选项值] [--actions JSON数组] [--multiple true|false] [--context-key 上下文键]
ask-question select --question 问题 --options '[{"label":"展示给用户的名称","value":"稳定机器值","description":"可选说明"}]' [--content Markdown内容 | --content-file /path/preview.md] [--default-value 推荐选项值] [--actions JSON数组] [--multiple true|false] [--context-key 上下文键]
```

`select` 适用于单值候选，前端会渲染为可搜索下拉框。`choose` 适用于需要用户阅读说明后做口径选择的单选/多选卡片。只要已有候选，不要退化成文本输入。

如果一次需要确认多个结构化字段，使用 `form`，不要连续发多个单字段问题：

```bash
ask-question form --question 问题 --fields '[
  {"key":"primaryChoice","label":"主要选项","type":"SELECT","options":[...],"defaultValue":"option-a"},
  {"key":"effectiveDate","label":"生效日期","type":"DATE","defaultValue":"2026-07-04"},
  {"key":"note","label":"补充说明","type":"TEXT","required":false}
]' [--content Markdown内容 | --content-file /path/preview.md] [--actions JSON数组]
```

`form` 字段支持 `TEXT`、`TEXTAREA`、`SELECT`、`YES_NO`、`DATE`、`DATETIME`。多行编号、列表或较长条件使用 `TEXTAREA`。每个字段的 `key` 必须稳定，`contextKey` 默认等于 `key`；提交后平台会分别写回这些上下文键，后续步骤可以直接读取。

需要场景自定义操作按钮时，追加 `--actions`。`actions.key` 是返回给能力和 agent 的稳定机器值，平台不解释业务含义；`label` 是展示给用户的按钮文案；`style` 只影响展示，可选 `primary`、`danger`、`default`。需要校验当前输入或表单必填项的动作必须设置 `"validateInput":true`；取消、仅预览等不依赖输入的动作不设置。

```bash
ask-question ask --input-type CONFIRM --question 问题 --actions '[
  {"key":"execute","label":"确认执行","style":"danger","description":"按当前计划继续"},
  {"key":"revise","label":"需要调整"},
  {"key":"cancel","label":"取消"}
]'
```

没有 `--actions` 时，平台显示默认提交、取消、不知道、跳过按钮。

如果你能根据当前问题、上下文或候选说明判断一个更可能的选项，可以带 `--default-value` 给出推荐默认值。单选默认值必须等于某个选项的 `value`；多选默认值用英文逗号分隔多个 `value`。不要为了看起来聪明而瞎选，只有有明确依据时才给默认值。

如果用户回答需要被后续流程继续使用，可以带上 `--context-key`。平台会把答案写回当前任务上下文，后续步骤可以按这个键读取。

`--context-key` 应使用语义稳定、后续步骤能够理解的字段名。优先复用当前任务、场景或能力已经定义的参数；动态表单也可以为本次任务定义清晰的局部上下文键，但不要使用无语义的临时编号。选项的 `value` 应使用稳定、可机器读取的值；`label` 才是展示给用户看的文字。

## 候选确认规则

- 候选数量本身不要求提问。当前上下文足以明确选择时继续执行；只有多个候选都合理且选择会改变任务结果时才向用户确认。
- Agent 决定让用户选择且已有候选项时，使用 `ask-question choose`、`select` 或 `form`；选项 `label` 写用户能理解的名称，`value` 写稳定机器值。
- 不要向普通用户索要内部 ID、编码、配置键这类机器字段。
- 没有候选项时，使用文本追问让用户补充可理解的线索，不要要求用户直接提供内部字段。
- 后续步骤需要复用答案时通过 `--context-key` 写入上下文；一次性说明不要求落成上下文。
