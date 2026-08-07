#!/usr/bin/env python3
import datetime
import decimal
import json
import re
import sqlite3
import sys
import time
import urllib.parse


DEFAULT_LIMIT = 100
MAX_STATEMENT_COUNT = 5
DENIED_KEYWORDS = {
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
}


def read_text(path):
    with open(path, "r", encoding="utf-8") as file:
        return file.read()


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def read_json(path):
    with open(path, "r", encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, dict):
        raise ValueError("config file must contain a JSON object")
    return value


def expand_file_options(args):
    expanded = []
    index = 0
    while index < len(args):
        item = args[index]
        if item == "--sql-file" and index + 1 < len(args):
            expanded.extend(["--sql", read_text(args[index + 1])])
            index += 2
            continue
        if item == "--reason-file" and index + 1 < len(args):
            expanded.extend(["--reason", read_text(args[index + 1]).strip()])
            index += 2
            continue
        expanded.append(item)
        index += 1
    return expanded


def split_sql(sql):
    statements = []
    current = []
    quote = None
    escaped = False
    for char in sql:
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


def strip_leading_comments(sql):
    value = sql.strip().lstrip("\ufeff")
    while True:
        if value.startswith("--"):
            parts = value.splitlines()
            value = "\n".join(parts[1:]).strip()
            continue
        if value.startswith("#"):
            parts = value.splitlines()
            value = "\n".join(parts[1:]).strip()
            continue
        if value.startswith("/*") and "*/" in value:
            value = value[value.index("*/") + 2:].strip()
            continue
        return value


def readonly_statement_kind(sql):
    match = re.match(r"^(select|with)\b", sql.strip().lower(), re.IGNORECASE)
    return match.group(1) if match else None


def statement_preview(sql):
    return re.sub(r"\s+", " ", sql.strip())[:120]


def normalize_readonly_sql(sql):
    statements = split_sql(sql or "")
    if not statements or len(statements) > MAX_STATEMENT_COUNT:
        raise ValueError("only 1 to 5 SELECT/WITH statements are allowed")
    normalized = []
    for index, statement in enumerate(statements, start=1):
        value = strip_leading_comments(statement)
        lowered = value.lower()
        if not readonly_statement_kind(value):
            raise ValueError(f"only SELECT/WITH statements are allowed at statement {index}: {statement_preview(value)}")
        tokens = set(re.findall(r"\b[a-z_]+\b", lowered))
        denied = sorted(tokens & DENIED_KEYWORDS)
        if denied:
            raise ValueError(f"readonly SQL contains denied keyword: {denied[0]}")
        if not re.search(r"\blimit\s+\d+\b", lowered):
            value = value.rstrip() + f" limit {DEFAULT_LIMIT}"
        normalized.append(value)
    return normalized


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
        raise RuntimeError("JDBC 查询能力缺少 MySQL 运行依赖 pymysql，请确认能力 scripts/requirements.txt 已被运行时自动安装。") from exc
    parsed = urllib.parse.urlparse(dsn)
    database = parsed.path.lstrip("/")
    params = urllib.parse.parse_qs(parsed.query)
    charset = (params.get("characterEncoding") or params.get("charset")
               or [config.get("characterEncoding") or config.get("charset") or "utf8mb4"])[0]
    collation = (params.get("connectionCollation") or params.get("collation")
                 or [config.get("connectionCollation") or config.get("collation") or ""])[0]
    if not re.fullmatch(r"[A-Za-z0-9_]+", charset):
        raise ValueError("MySQL characterEncoding/charset contains invalid characters")
    if collation and not re.fullmatch(r"[A-Za-z0-9_]+", collation):
        raise ValueError("MySQL connectionCollation/collation contains invalid characters")
    connection_options = {
        "host": parsed.hostname,
        "port": parsed.port or 3306,
        "user": config.get("username") or None,
        "password": config.get("password") or None,
        "database": database or None,
        "charset": charset,
        "connect_timeout": 10,
        "read_timeout": 60,
        "cursorclass": pymysql.cursors.Cursor,
    }
    if collation:
        connection_options["init_command"] = f"SET NAMES {charset} COLLATE {collation}"
    return pymysql.connect(**connection_options)


def connect_postgresql(dsn, config):
    parsed = urllib.parse.urlparse(dsn)
    database = parsed.path.lstrip("/")
    kwargs = {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "dbname": database or None,
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
            raise RuntimeError("JDBC 查询能力缺少 PostgreSQL 运行依赖 psycopg/psycopg2，请确认能力 scripts/requirements.txt 已被运行时自动安装。") from exc


def connect_sqlite(dsn):
    path = urllib.parse.unquote(dsn)
    if path.startswith("//"):
        path = path[2:]
    if path.startswith(":memory:"):
        raise ValueError("sqlite memory database is not allowed for readonly query")
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def open_connection(config):
    kind, dsn = jdbc_parts(config.get("url"))
    if kind == "mysql":
        return connect_mysql(dsn, config)
    if kind == "postgresql":
        return connect_postgresql(dsn, config)
    if kind == "sqlite":
        return connect_sqlite(dsn)
    raise ValueError(f"unsupported database type: {kind}")


def json_value(value):
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return str(value)
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def execute_queries(config, sqls):
    started = time.time()
    results = []
    with open_connection(config) as connection:
        if hasattr(connection, "readonly"):
            try:
                connection.readonly = True
            except Exception:
                pass
        cursor = connection.cursor()
        for index, sql in enumerate(sqls, start=1):
            query_started = time.time()
            try:
                cursor.execute(sql)
            except Exception as exc:
                error_code = exc.args[0] if exc.args and isinstance(exc.args[0], int) else None
                error_message = exc.args[1] if len(exc.args) > 1 else str(exc)
                raise RuntimeError(json.dumps({
                    "category": "DATABASE_QUERY_FAILED",
                    "statementIndex": index,
                    "databaseErrorCode": error_code,
                    "databaseErrorType": type(exc).__name__,
                    "message": str(error_message),
                    "sqlPreview": statement_preview(sql),
                }, ensure_ascii=False)) from exc
            columns = [item[0] for item in cursor.description or []]
            rows = [[json_value(value) for value in row] for row in cursor.fetchall()]
            results.append(
                {
                    "index": index,
                    "sql": sql,
                    "columns": columns,
                    "rows": rows,
                    "rowCount": len(rows),
                    "elapsedMillis": int((time.time() - query_started) * 1000),
                }
            )
    response = {
        "sql": ";\n".join(sqls),
        "statementCount": len(sqls),
        "results": results,
        "elapsedMillis": int((time.time() - started) * 1000),
    }
    if results:
        response["columns"] = results[0]["columns"]
        response["rows"] = results[0]["rows"]
        response["rowCount"] = results[0]["rowCount"]
    return response


def call_platform(command, args):
    if command != "jdbc.query":
        print(f"unsupported jdbc-readonly-query command: {command}", file=sys.stderr)
        return 2
    expanded = expand_file_options(args)
    sql = option(expanded, "--sql")
    config_file = option(expanded, "--config-file")
    if not sql:
        print("missing required argument: --sql or --sql-file", file=sys.stderr)
        return 2
    if not config_file:
        print("missing required argument: --config-file", file=sys.stderr)
        return 2
    try:
        config_data = read_json(config_file)
        sqls = normalize_readonly_sql(sql)
        response = execute_queries(config_data, sqls)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
