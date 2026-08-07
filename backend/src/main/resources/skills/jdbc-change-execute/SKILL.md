---
name: jdbc-change-execute
description: "通过显式传入的数据源配置文件和变更计划执行受控 INSERT、UPDATE、DELETE，先预检备份，再执行或按备份回滚。"
---

# JDBC 数据变更

这个能力用于执行受控数据新增、修改和删除。它只做三件事：按变更计划预检并备份、按备份执行、按备份回滚。

## 能力边界

- 只允许受控单行 `INSERT`、带明确 `WHERE` 的 `UPDATE` 和显式确认的 `DELETE`，并且必须先预检、备份。
- `INSERT` 必须显式提供 `id`；`DELETE` 必须设置 `allowPhysicalDelete=true`、显式限制影响行数，并优先确认业务是否应使用逻辑删除。
- `UPDATE` 不允许修改 `id`；预检后目标行发生变化时必须重新预检。
- 禁止多行 `INSERT`、`INSERT ... SELECT`、无主键变更、建表改表、批量导入、调用存储过程、授权、跨库迁移和不带明确 `WHERE` 的变更。
- 单次计划最多 5 条语句，默认最多影响 100 行；场景可按风险传入更小的 `--max-rows`。
- 回滚只基于本能力生成的备份文件：新增按主键删除，修改按主键恢复，物理删除按完整快照回插；不根据口头描述生成回滚 SQL。
- 查询失败、预检失败、备份失败或影响行数不符合预期时必须停止，不得继续执行。

能力不会自动寻找项目、环境或数据源，也不会理解业务场景。调用者必须显式传入数据源配置文件、变更计划文件和备份文件：

```bash
jdbc-change preflight --config-file /path/datasource.json --plan-file /tmp/change-plan.json --backup-file /tmp/change-backup.json [--max-rows 100]
jdbc-change execute --config-file /path/datasource.json --backup-file /tmp/change-backup.json [--max-rows 100]
jdbc-change rollback --config-file /path/datasource.json --backup-file /tmp/change-backup.json
```

`preflight` 会解析计划、核验新增主键或统计现有影响行、读取变更前快照并写入备份文件；`execute` 只读取同一个备份文件，在事务中再次校验快照后执行；`rollback` 按相反顺序恢复数据。

变更计划是 JSON 对象，支持以下格式：

```json
{
  "maxRows": 10,
  "statements": [
    {
      "sql": "insert into table_name (id, name, status) values (123, '示例', 1)",
      "reason": "补录缺失记录",
      "expectedMaxRows": 1
    },
    {
      "sql": "update table_name set status = 2 where id = 456",
      "reason": "修正状态",
      "expectedMaxRows": 1
    },
    {
      "sql": "delete from table_name where id = 789",
      "reason": "删除确认无业务引用的错误记录",
      "expectedMaxRows": 1,
      "allowPhysicalDelete": true
    }
  ]
}
```

支持的 SQL 范围：

- `INSERT`：只允许显式列、单行 `VALUES`，并且必须提供非空显式 `id`。不支持多行新增、`INSERT ... SELECT`、自动生成主键或冲突覆盖语法。
- `UPDATE`：必须带明确 `WHERE`，目标查询结果必须包含 `id`，并且不允许修改 `id`。
- `DELETE`：必须带明确 `WHERE`，计划项必须设置 `allowPhysicalDelete=true` 和 1 到 20 的 `expectedMaxRows`。业务存在逻辑删除字段时应改用 `UPDATE`。

不接受 `DROP`、`ALTER`、`TRUNCATE`、`CREATE`、`MERGE`、`CALL`、`REPLACE`、授权语句、存储过程、子查询、分号多语句、内嵌其他 DML 或带行内注释的 SQL。需要结构变更、批量导入、跨库操作或复杂 SQL 时停止并说明需要人工 DBA 流程。

使用要求：

- 调用 `execute` 前必须已经成功调用 `preflight`，并由场景通过用户交互获得明确确认。
- 不要跳过备份文件，也不要手写“已备份”结论。
- 如果 `preflight` 返回影响行数超过预期，必须停止，不能继续执行。
- `INSERT` 回滚会按显式 `id` 删除本次新增行；`UPDATE` 回滚恢复原字段；`DELETE` 回滚按完整行快照重新插入。混合计划按相反顺序回滚。
- 如果执行后用户要求回滚，必须使用同一个备份文件调用 `rollback`，不要重新拼接回滚 SQL。
- 这个能力只负责按入参执行，不关心配置来自哪里，也不依赖其他能力的内部状态。
