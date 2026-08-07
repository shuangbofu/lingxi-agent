#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.parse
import urllib.request

DEFAULT_BYTES = 512 * 1024
DEFAULT_TIMEOUT = 20
MAX_SEARCH_FILES = 20


def main():
    parser = argparse.ArgumentParser(prog="http-log")
    subparsers = parser.add_subparsers(dest="command", required=True)
    add_common(subparsers.add_parser("health"))
    list_parser = add_common(subparsers.add_parser("list"))
    list_parser.add_argument("--path")
    list_parser.add_argument("--pattern")
    list_parser.add_argument("--limit", type=int, default=200)
    tail_parser = add_common(subparsers.add_parser("tail"))
    tail_parser.add_argument("--file", required=True)
    tail_parser.add_argument("--bytes", type=int)
    search_parser = add_common(subparsers.add_parser("search"))
    search_parser.add_argument("--file")
    search_parser.add_argument("--must", action="append", default=[])
    search_parser.add_argument("--keyword", action="append", default=[])
    search_parser.add_argument("--bytes", type=int)
    search_parser.add_argument("--limit", type=int, default=100)
    runtime_args = sys.argv[1:]
    if runtime_args and runtime_args[0].startswith("http-log."):
        runtime_args[0] = runtime_args[0].split(".", 1)[1]
    args = parser.parse_args(runtime_args)

    config = load_config(args.config_file)
    if args.command == "health":
        print_json(request_json(config, "health", {}))
    elif args.command == "list":
        print_json(log_list(config, args.path, args.pattern, args.limit))
    elif args.command == "tail":
        print_json(log_tail(config, args.file, args.bytes))
    elif args.command == "search":
        print_json(log_search(config, args.file, args.must, args.keyword, args.bytes, args.limit))
    else:
        print(f"unsupported http-log command: {args.command}", file=sys.stderr)
        return 2
    return 0


def add_common(parser):
    parser.add_argument("--config-file", required=True)
    return parser


def load_config(path):
    with open(path, "r", encoding="utf-8") as handle:
        config = json.load(handle)
    if not config.get("baseUrl"):
        raise ValueError("http-log config missing baseUrl")
    if not config.get("root"):
        raise ValueError("http-log config missing root")
    return config


def request_json(config, endpoint, params):
    base_url = str(config["baseUrl"]).rstrip("/")
    query = {"root": config.get("root")}
    query.update({key: value for key, value in params.items() if value is not None and value != ""})
    if config.get("token"):
        query["token"] = config["token"]
    url = f"{base_url}/{endpoint}?{urllib.parse.urlencode(query)}"
    timeout = int(config.get("timeoutSeconds") or DEFAULT_TIMEOUT)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"HTTP 连接失败：{exc.reason}") from exc


def log_list(config, path, pattern, limit):
    return request_json(config, "list", {
        "path": path or config.get("path"),
        "pattern": pattern or config.get("filePattern") or "*.log",
        "limit": limit,
    })


def log_tail(config, file_path, byte_count):
    return request_json(config, "tail", {
        "file": file_path,
        "bytes": safe_bytes(config, byte_count),
        "encoding": config.get("encoding") or "UTF-8",
    })


def log_search(config, file_path, must_terms, keyword_terms, byte_count, limit):
    files = [file_path] if file_path else candidate_files(config)
    results = []
    for current_file in files[:MAX_SEARCH_FILES]:
        tail = log_tail(config, current_file, byte_count)
        for line_number, line in enumerate(tail.get("content", "").splitlines(), start=1):
            if not line_matches(line, must_terms, keyword_terms):
                continue
            results.append({
                "file": current_file,
                "lineInChunk": line_number,
                "text": line,
            })
            if len(results) >= limit:
                return {
                    "searchedFiles": files[:MAX_SEARCH_FILES],
                    "truncatedFiles": len(files) > MAX_SEARCH_FILES,
                    "results": results,
                }
    return {
        "searchedFiles": files[:MAX_SEARCH_FILES],
        "truncatedFiles": len(files) > MAX_SEARCH_FILES,
        "results": results,
    }


def candidate_files(config):
    data = log_list(config, config.get("path"), config.get("filePattern") or "*.log", MAX_SEARCH_FILES)
    items = data.get("items") or []
    files = [item["path"] for item in items if not item.get("directory")]
    if not files:
        raise RuntimeError("没有找到可搜索的日志文件")
    return files


def line_matches(line, must_terms, keyword_terms):
    for term in must_terms:
        if term and term not in line:
            return False
    return not keyword_terms or any(term and term in line for term in keyword_terms)


def safe_bytes(config, byte_count):
    value = byte_count or int(config.get("maxBytes") or DEFAULT_BYTES)
    if value <= 0:
        return DEFAULT_BYTES
    return value


def print_json(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
