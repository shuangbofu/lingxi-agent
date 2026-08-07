#!/usr/bin/env python3
import datetime
import decimal
import hashlib
import json
import re
import sqlite3
import sys
import time
import urllib.parse


DEFAULT_MAX_ROWS = 100
MAX_STATEMENT_COUNT = 5
MAX_DELETE_ROWS = 20
DENIED_TOKENS = {
    "insert",
    "update",
    "delete",
    "drop",
    "alter",
    "truncate",
    "create",
    "merge",
    "call",
    "grant",
    "revoke",
    "replace",
    "select",
}


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def int_option(args, name, default):
    value = option(args, name)
    if value is None or str(value).strip() == "":
        return default
    return int(value)


def read_text(path):
    with open(path, "r", encoding="utf-8") as file:
        return file.read()


def read_json(path):
    with open(path, "r", encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, dict):
        raise ValueError("JSON file must contain an object")
    return value


def write_json(path, value):
    with open(path, "w", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)


def split_sql(sql):
    statements = []
    current = []
    quote = None
    escaped = False
    for char in sql or "":
        if quote:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {"'", '"', "`"}:
            quote = char
            current.append(char)
            continue
        if char == ";":
            value = "".join(current).strip()
            if value:
                statements.append(value)
            current = []
            continue
        current.append(char)
    value = "".join(current).strip()
    if value:
        statements.append(value)
    return statements


def strip_comments(sql):
    value = (sql or "").strip().lstrip("\ufeff")
    while True:
        if value.startswith("--") or value.startswith("#"):
            value = "\n".join(value.splitlines()[1:]).strip()
            continue
        if value.startswith("/*") and "*/" in value:
            value = value[value.index("*/") + 2:].strip()
            continue
        return value


def json_value(value):
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return str(value)
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def jdbc_parts(url):
    if not url or not url.startswith("jdbc:"):
        raise ValueError("config.url must be a JDBC URL")
    body = url[len("jdbc:"):]
    if body.startswith("mysql:"):
        return "mysql", body[len("mysql:"):]
    if body.startswith("postgresql:"):
        return "postgresql", body[len("postgresql:"):]
    if body.startswith("sqlite:"):
        return "sqlite", body[len("sqlite:"):]
    raise ValueError("unsupported JDBC URL; supported: jdbc:mysql, jdbc:postgresql, jdbc:sqlite")


def connect_mysql(dsn, config):
    try:
        import pymysql
    except ImportError as exc:
        raise RuntimeError("JDBC 变更能力缺少 MySQL 运行依赖 pymysql，请确认能力 scripts/requirements.txt 已被运行时自动安装。") from exc
    parsed = urllib.parse.urlparse(dsn)
    params = urllib.parse.parse_qs(parsed.query)
    charset = (params.get("characterEncoding") or params.get("charset") or ["utf8mb4"])[0]
    return pymysql.connect(
        host=parsed.hostname,
        port=parsed.port or 3306,
        user=config.get("username") or None,
        password=config.get("password") or None,
        database=parsed.path.lstrip("/") or None,
        charset=charset,
        connect_timeout=10,
        read_timeout=60,
        autocommit=False,
        cursorclass=pymysql.cursors.Cursor,
    )


def connect_postgresql(dsn, config):
    parsed = urllib.parse.urlparse(dsn)
    kwargs = {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": parsed.path.lstrip("/") or None,
        "user": config.get("username") or None,
        "password": config.get("password") or None,
        "connect_timeout": 10,
    }
    try:
        import psycopg
        return psycopg.connect(**{key: value for key, value in kwargs.items() if value is not None})
    except ImportError:
        try:
            import psycopg2
            return psycopg2.connect(**{key: value for key, value in kwargs.items() if value is not None})
        except ImportError as exc:
            raise RuntimeError("JDBC 变更能力缺少 PostgreSQL 运行依赖 psycopg/psycopg2，请确认能力 scripts/requirements.txt 已被运行时自动安装。") from exc


def connect_sqlite(dsn):
    path = urllib.parse.unquote(dsn)
    if path.startswith("//"):
        path = path[2:]
    if path.startswith(":memory:"):
        raise ValueError("sqlite memory database is not allowed")
    return sqlite3.connect(path)


def open_connection(config):
    kind, dsn = jdbc_parts(config.get("url"))
    if kind == "mysql":
        return kind, connect_mysql(dsn, config)
    if kind == "postgresql":
        return kind, connect_postgresql(dsn, config)
    if kind == "sqlite":
        return kind, connect_sqlite(dsn)
    raise ValueError("unsupported database type: " + kind)


def placeholder(kind):
    return "?" if kind == "sqlite" else "%s"


def quote_identifier(kind, name):
    quote = "`" if kind == "mysql" else '"'
    return quote + str(name).replace(quote, quote + quote) + quote


def statement_preview(sql):
    return re.sub(r"\s+", " ", sql.strip())[:160]


def split_sql_list(value):
    items = []
    current = []
    quote = None
    escaped = False
    depth = 0
    for char in value or "":
        if quote:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {"'", '"', "`"}:
            quote = char
            current.append(char)
            continue
        if char == "(":
            depth += 1
            current.append(char)
            continue
        if char == ")":
            depth -= 1
            if depth < 0:
                raise ValueError("unbalanced parenthesis in SQL list")
            current.append(char)
            continue
        if char == "," and depth == 0:
            items.append("".join(current).strip())
            current = []
            continue
        current.append(char)
    if quote or depth != 0:
        raise ValueError("unclosed quote or parenthesis in SQL list")
    item = "".join(current).strip()
    if item:
        items.append(item)
    return items


def sql_structure(sql):
    result = []
    quote = None
    escaped = False
    for char in sql or "":
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            result.append(" ")
            continue
        if char in {"'", '"'}:
            quote = char
            result.append(" ")
            continue
        result.append(char)
    return "".join(result)


def validate_statement(sql, index, operation):
    structure = sql_structure(sql)
    if any(marker in structure for marker in (";", "--", "#", "/*", "*/")):
        raise ValueError("statement %s must not contain semicolons or inline comments" % index)
    tokens = set(re.findall(r"\b[a-z_]+\b", structure.lower()))
    denied = sorted((tokens & DENIED_TOKENS) - {operation.lower()})
    if denied:
        raise ValueError("statement %s contains denied keyword: %s" % (index, denied[0]))


def normalized_identifier(value):
    return str(value).strip().strip('`"').lower()


def safe_insert_id_literal(value):
    literal = str(value).strip()
    if re.fullmatch(r"[-+]?\d+(?:\.\d+)?", literal):
        return literal
    if re.fullmatch(r"'(?:[^']|'')+'", literal, flags=re.DOTALL):
        return literal
    raise ValueError("INSERT id must be an explicit non-null string or numeric literal")


def normalize_plan(plan):
    if "statements" in plan:
        raw_items = plan.get("statements")
        if not isinstance(raw_items, list):
            raise ValueError("plan.statements must be an array")
        items = raw_items
    else:
        items = [{"sql": sql} for sql in split_sql(plan.get("sql") or "")]
    if not items or len(items) > MAX_STATEMENT_COUNT:
        raise ValueError("change plan requires 1 to 5 INSERT, UPDATE or DELETE statements")
    normalized = []
    for index, item in enumerate(items, start=1):
        if isinstance(item, str):
            item = {"sql": item}
        if not isinstance(item, dict):
            raise ValueError("each statement must be an object")
        sql = strip_comments(item.get("sql") or "")
        operation_match = re.match(r"(?is)^\s*(insert|update|delete)\b", sql)
        if not operation_match:
            raise ValueError("only INSERT, UPDATE or DELETE is allowed at statement %s: %s" % (index, statement_preview(sql)))
        operation = operation_match.group(1).upper()
        if operation == "INSERT":
            parsed = parse_insert_statement(sql, index)
        elif operation == "UPDATE":
            parsed = parse_update_statement(sql, index)
        else:
            parsed = parse_delete_statement(sql, index, item)
        parsed["index"] = index
        parsed["reason"] = item.get("reason")
        parsed["expectedMaxRows"] = item.get("expectedMaxRows")
        normalized.append(parsed)
    return normalized


def parse_update_statement(sql, index):
    validate_statement(sql, index, "UPDATE")
    match = re.match(r"(?is)^update\s+([`\"A-Za-z0-9_.]+)\s+set\s+(.+?)\s+where\s+(.+)$", sql)
    if not match:
        raise ValueError("only UPDATE ... SET ... WHERE ... is allowed at statement %s: %s" % (index, statement_preview(sql)))
    table = match.group(1).strip()
    set_clause = match.group(2).strip()
    where_clause = match.group(3).strip()
    if re.search(r"(?is)\blimit\b", where_clause):
        raise ValueError("statement %s must not use LIMIT; restrict rows with a precise WHERE clause" % index)
    if not table or not set_clause or not where_clause:
        raise ValueError("statement %s missing table, SET or WHERE" % index)
    for assignment in split_sql_list(set_clause):
        assignment_match = re.match(r"(?is)^([`\"A-Za-z0-9_]+)\s*=", assignment)
        if not assignment_match:
            raise ValueError("statement %s contains an invalid SET assignment" % index)
        if normalized_identifier(assignment_match.group(1)) == "id":
            raise ValueError("statement %s must not update id; rollback depends on the original id" % index)
    return {
        "operation": "UPDATE",
        "sql": sql,
        "table": table,
        "where": where_clause,
        "setPreview": statement_preview(set_clause),
        "backupSql": "select * from %s where %s" % (table, where_clause),
        "countSql": "select count(*) as cnt from %s where %s" % (table, where_clause),
    }


def parse_insert_statement(sql, index):
    validate_statement(sql, index, "INSERT")
    match = re.match(r"(?is)^insert\s+into\s+([`\"A-Za-z0-9_.]+)\s*\((.+?)\)\s*values\s*\((.+)\)$", sql)
    if not match:
        raise ValueError("only single-row INSERT INTO ... (columns) VALUES (...) is allowed at statement %s: %s" % (index, statement_preview(sql)))
    table = match.group(1).strip()
    try:
        columns = split_sql_list(match.group(2))
        values = split_sql_list(match.group(3))
    except ValueError as exc:
        raise ValueError("only single-row INSERT INTO ... (columns) VALUES (...) is allowed at statement %s: %s" % (index, statement_preview(sql))) from exc
    if not columns or len(columns) != len(values):
        raise ValueError("statement %s INSERT columns and values must have the same non-zero length" % index)
    normalized_columns = [normalized_identifier(column) for column in columns]
    if any(not re.fullmatch(r"[a-z_][a-z0-9_]*", column) for column in normalized_columns):
        raise ValueError("statement %s INSERT contains an invalid column name" % index)
    if len(set(normalized_columns)) != len(normalized_columns):
        raise ValueError("statement %s INSERT contains duplicate columns" % index)
    if "id" not in normalized_columns:
        raise ValueError("statement %s INSERT must provide an explicit id for safe rollback" % index)
    id_index = normalized_columns.index("id")
    id_literal = safe_insert_id_literal(values[id_index])
    id_column = columns[id_index]
    where_clause = "%s = %s" % (id_column, id_literal)
    return {
        "operation": "INSERT",
        "sql": sql,
        "table": table,
        "where": where_clause,
        "setPreview": None,
        "backupSql": "select * from %s where %s" % (table, where_clause),
        "countSql": "select count(*) as cnt from %s where %s" % (table, where_clause),
    }


def parse_delete_statement(sql, index, item):
    validate_statement(sql, index, "DELETE")
    if item.get("allowPhysicalDelete") is not True:
        raise ValueError("statement %s DELETE requires allowPhysicalDelete=true" % index)
    expected_max_rows = item.get("expectedMaxRows")
    if expected_max_rows is None or int(expected_max_rows) < 1 or int(expected_max_rows) > MAX_DELETE_ROWS:
        raise ValueError("statement %s DELETE requires expectedMaxRows between 1 and %s" % (index, MAX_DELETE_ROWS))
    match = re.match(r"(?is)^delete\s+from\s+([`\"A-Za-z0-9_.]+)\s+where\s+(.+)$", sql)
    if not match:
        raise ValueError("only DELETE FROM ... WHERE ... is allowed at statement %s: %s" % (index, statement_preview(sql)))
    table = match.group(1).strip()
    where_clause = match.group(2).strip()
    if re.search(r"(?is)\blimit\b", where_clause):
        raise ValueError("statement %s must not use LIMIT; restrict rows with a precise WHERE clause" % index)
    if not where_clause:
        raise ValueError("statement %s DELETE is missing WHERE" % index)
    return {
        "operation": "DELETE",
        "sql": sql,
        "table": table,
        "where": where_clause,
        "setPreview": None,
        "backupSql": "select * from %s where %s" % (table, where_clause),
        "countSql": "select count(*) as cnt from %s where %s" % (table, where_clause),
    }


def plan_hash(statements):
    text = "\n".join(item["sql"] for item in statements)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def table_max_rows(plan, statement, default_max_rows):
    value = statement.get("expectedMaxRows")
    if value is None:
        value = plan.get("maxRows")
    if value is None:
        value = default_max_rows
    return int(value)


def fetch_rows(cursor, sql):
    cursor.execute(sql)
    columns = [item[0] for item in cursor.description or []]
    rows = [[json_value(value) for value in row] for row in cursor.fetchall()]
    return columns, rows


def fetch_count(cursor, sql):
    cursor.execute(sql)
    row = cursor.fetchone()
    return int(row[0] if row else 0)


def rows_by_id(columns, rows):
    lower_columns = [str(column).lower() for column in columns]
    if "id" not in lower_columns:
        raise ValueError("backup result has no id column")
    id_index = lower_columns.index("id")
    result = {
        json.dumps(row[id_index], ensure_ascii=False, sort_keys=True): row
        for row in rows
    }
    if len(result) != len(rows):
        raise ValueError("backup result contains duplicate id values")
    return result


def ensure_snapshot_unchanged(cursor, statement):
    columns, rows = fetch_rows(cursor, statement["backupSql"])
    if columns != statement.get("columns") or rows_by_id(columns, rows) != rows_by_id(statement.get("columns") or [], statement.get("rows") or []):
        raise ValueError("statement %s target rows changed after preflight; run preflight again" % statement["index"])


def preflight(config, plan, backup_file, max_rows):
    started = time.time()
    statements = normalize_plan(plan)
    kind, connection = open_connection(config)
    backup = {
        "version": 2,
        "status": "PREFLIGHT_OK",
        "kind": kind,
        "createdAt": datetime.datetime.now().isoformat(),
        "planHash": plan_hash(statements),
        "maxRows": max_rows,
        "statements": [],
    }
    try:
        cursor = connection.cursor()
        for statement in statements:
            operation = statement["operation"]
            allowed_rows = table_max_rows(plan, statement, max_rows)
            if allowed_rows < 1:
                raise ValueError("statement %s maxRows must be greater than 0" % statement["index"])
            count = fetch_count(cursor, statement["countSql"])
            if operation == "INSERT" and count != 0:
                raise ValueError("statement %s INSERT id already exists" % statement["index"])
            if operation == "DELETE" and count == 0:
                raise ValueError("statement %s DELETE matches no rows" % statement["index"])
            if operation != "INSERT" and count > allowed_rows:
                raise ValueError("statement %s affects %s rows, exceeds maxRows %s" % (statement["index"], count, allowed_rows))
            columns, rows = fetch_rows(cursor, statement["backupSql"])
            if "id" not in [str(column).lower() for column in columns]:
                raise ValueError("statement %s backup result has no id column; rollback cannot be generated safely" % statement["index"])
            affected_rows = 1 if operation == "INSERT" else len(rows)
            backup["statements"].append({
                "index": statement["index"],
                "operation": operation,
                "reason": statement.get("reason"),
                "sql": statement["sql"],
                "table": statement["table"],
                "where": statement["where"],
                "backupSql": statement["backupSql"],
                "rowCount": affected_rows,
                "beforeRowCount": len(rows),
                "columns": columns,
                "rows": rows,
                "maxRows": allowed_rows,
            })
        write_json(backup_file, backup)
        return {
            "status": "PREFLIGHT_OK",
            "statementCount": len(statements),
            "backupFile": backup_file,
            "planHash": backup["planHash"],
            "statements": [
                {
                    "index": item["index"],
                    "operation": item["operation"],
                    "table": item["table"],
                    "rowCount": item["rowCount"],
                    "maxRows": item["maxRows"],
                    "backupSql": item["backupSql"],
                    "sqlPreview": statement_preview(item["sql"]),
                }
                for item in backup["statements"]
            ],
            "elapsedMillis": int((time.time() - started) * 1000),
        }
    finally:
        try:
            connection.close()
        except Exception:
            pass


def execute(config, backup, max_rows):
    if int(backup.get("version") or 1) >= 2 and backup.get("status") != "PREFLIGHT_OK":
        raise ValueError("backup status must be PREFLIGHT_OK before execute")
    started = time.time()
    kind, connection = open_connection(config)
    results = []
    try:
        cursor = connection.cursor()
        for statement in backup.get("statements") or []:
            operation = statement.get("operation") or "UPDATE"
            allowed_rows = int(statement.get("maxRows") or max_rows)
            count = fetch_count(cursor, "select count(*) as cnt from %s where %s" % (statement["table"], statement["where"]))
            if operation == "INSERT" and count != 0:
                raise ValueError("statement %s INSERT id now exists; run preflight again" % statement["index"])
            if operation != "INSERT" and count > allowed_rows:
                raise ValueError("statement %s now affects %s rows, exceeds maxRows %s" % (statement["index"], count, allowed_rows))
            if operation in {"UPDATE", "DELETE"}:
                ensure_snapshot_unchanged(cursor, statement)
            cursor.execute(statement["sql"])
            affected_rows = cursor.rowcount
            if operation == "INSERT" and affected_rows != 1:
                raise ValueError("statement %s INSERT affected %s rows; expected exactly 1" % (statement["index"], affected_rows))
            if operation == "DELETE" and affected_rows != count:
                raise ValueError("statement %s DELETE affected %s rows; preflight matched %s" % (statement["index"], affected_rows, count))
            statement["executedRows"] = affected_rows
            results.append({
                "index": statement["index"],
                "operation": operation,
                "table": statement["table"],
                "affectedRows": affected_rows,
                "backupRows": statement.get("rowCount"),
            })
        connection.commit()
        backup["status"] = "EXECUTED"
        backup["executedAt"] = datetime.datetime.now().isoformat()
        return {
            "status": "EXECUTED",
            "planHash": backup.get("planHash"),
            "statements": results,
            "elapsedMillis": int((time.time() - started) * 1000),
        }
    except Exception:
        connection.rollback()
        raise
    finally:
        try:
            connection.close()
        except Exception:
            pass


def rollback(config, backup):
    if int(backup.get("version") or 1) >= 2 and backup.get("status") != "EXECUTED":
        raise ValueError("backup status must be EXECUTED before rollback")
    started = time.time()
    kind, connection = open_connection(config)
    results = []
    try:
        cursor = connection.cursor()
        mark = placeholder(kind)
        for statement in reversed(backup.get("statements") or []):
            operation = statement.get("operation") or "UPDATE"
            columns = statement.get("columns") or []
            lower_columns = [str(column).lower() for column in columns]
            if "id" not in lower_columns:
                raise ValueError("statement %s backup has no id column" % statement.get("index"))
            if operation == "INSERT":
                sql = "delete from %s where %s" % (statement["table"], statement["where"])
                cursor.execute(sql)
                restored = cursor.rowcount
                expected_rows = int(statement.get("executedRows") or 1)
                if restored != expected_rows:
                    raise ValueError("statement %s INSERT rollback removed %s rows; expected %s" % (statement.get("index"), restored, expected_rows))
            elif operation == "DELETE":
                column_sql = ", ".join(quote_identifier(kind, column) for column in columns)
                values_sql = ", ".join(mark for _ in columns)
                sql = "insert into %s (%s) values (%s)" % (statement["table"], column_sql, values_sql)
                restored = 0
                for row in statement.get("rows") or []:
                    cursor.execute(sql, row)
                    restored += cursor.rowcount
                if restored != len(statement.get("rows") or []):
                    raise ValueError("statement %s DELETE rollback restored %s rows; expected %s" % (statement.get("index"), restored, len(statement.get("rows") or [])))
                ensure_snapshot_unchanged(cursor, statement)
            else:
                id_index = lower_columns.index("id")
                restore_columns = [column for column in columns if str(column).lower() != "id"]
                set_sql = ", ".join("%s = %s" % (quote_identifier(kind, column), mark) for column in restore_columns)
                sql = "update %s set %s where %s = %s" % (statement["table"], set_sql, quote_identifier(kind, "id"), mark)
                restored = 0
                for row in statement.get("rows") or []:
                    row_map = dict(zip(columns, row))
                    params = [row_map.get(column) for column in restore_columns]
                    params.append(row[id_index])
                    cursor.execute(sql, params)
                    restored += cursor.rowcount
                ensure_snapshot_unchanged(cursor, statement)
            results.append({
                "index": statement.get("index"),
                "operation": operation,
                "table": statement.get("table"),
                "backupRows": len(statement.get("rows") or []),
                "restoredRows": restored,
            })
        connection.commit()
        backup["status"] = "ROLLED_BACK"
        backup["rolledBackAt"] = datetime.datetime.now().isoformat()
        return {
            "status": "ROLLED_BACK",
            "planHash": backup.get("planHash"),
            "statements": results,
            "elapsedMillis": int((time.time() - started) * 1000),
        }
    except Exception:
        connection.rollback()
        raise
    finally:
        try:
            connection.close()
        except Exception:
            pass


def call_platform(command, args):
    config_file = option(args, "--config-file")
    if not config_file:
        print("missing required argument: --config-file", file=sys.stderr)
        return 2
    try:
        config = read_json(config_file)
        max_rows = int_option(args, "--max-rows", DEFAULT_MAX_ROWS)
        if command == "jdbc-change.preflight":
            plan_file = option(args, "--plan-file")
            backup_file = option(args, "--backup-file")
            if not plan_file:
                print("missing required argument: --plan-file", file=sys.stderr)
                return 2
            if not backup_file:
                print("missing required argument: --backup-file", file=sys.stderr)
                return 2
            response = preflight(config, read_json(plan_file), backup_file, max_rows)
        elif command == "jdbc-change.execute":
            backup_file = option(args, "--backup-file")
            if not backup_file:
                print("missing required argument: --backup-file", file=sys.stderr)
                return 2
            backup = read_json(backup_file)
            response = execute(config, backup, max_rows)
            write_json(backup_file, backup)
        elif command == "jdbc-change.rollback":
            backup_file = option(args, "--backup-file")
            if not backup_file:
                print("missing required argument: --backup-file", file=sys.stderr)
                return 2
            backup = read_json(backup_file)
            response = rollback(config, backup)
            write_json(backup_file, backup)
        else:
            print("unsupported jdbc-change command: " + command, file=sys.stderr)
            return 2
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
