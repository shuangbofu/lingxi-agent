#!/usr/bin/env python3
import json
import queue
import subprocess
import sys
import threading


if len(sys.argv) < 2:
    raise SystemExit("missing MCP server command")

child = subprocess.Popen(
    sys.argv[1:],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    text=True,
    bufsize=1,
)
write_lock = threading.Lock()
pending_lock = threading.Lock()
pending = {}
request_sequence = 0


def message_key(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def write_message(target, message):
    target.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    target.flush()


def read_child():
    for line in child.stdout:
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            sys.stderr.write(line)
            sys.stderr.flush()
            continue
        response_queue = None
        if "id" in message:
            with pending_lock:
                response_queue = pending.get(message_key(message["id"]))
        if response_queue is not None:
            response_queue.put(message)
        else:
            write_message(sys.stdout, message)


reader = threading.Thread(target=read_child, daemon=True)
reader.start()


def child_request(method, params):
    global request_sequence
    request_sequence += 1
    request_id = "lingxi-pagination-" + str(request_sequence)
    response_queue = queue.Queue(maxsize=1)
    key = message_key(request_id)
    with pending_lock:
        pending[key] = response_queue
    try:
        with write_lock:
            write_message(child.stdin, {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": method,
                "params": params,
            })
        return response_queue.get(timeout=60)
    finally:
        with pending_lock:
            pending.pop(key, None)


def list_all_tools(request):
    tools = []
    params = dict(request.get("params") or {})
    seen_cursors = set()
    for _ in range(100):
        response = child_request("tools/list", params)
        if "error" in response:
            response["id"] = request["id"]
            return response
        result = response.get("result") or {}
        tools.extend(result.get("tools") or [])
        cursor = result.get("nextCursor")
        if not cursor:
            return {"jsonrpc": "2.0", "id": request["id"], "result": {"tools": tools}}
        if cursor in seen_cursors:
            return {
                "jsonrpc": "2.0",
                "id": request["id"],
                "error": {"code": -32603, "message": "tools/list returned a duplicate cursor"},
            }
        seen_cursors.add(cursor)
        params = dict(params)
        params["cursor"] = cursor
    return {
        "jsonrpc": "2.0",
        "id": request["id"],
        "error": {"code": -32603, "message": "tools/list exceeded the pagination limit"},
    }


try:
    for input_line in sys.stdin:
        try:
            request = json.loads(input_line)
        except json.JSONDecodeError:
            continue
        if request.get("method") == "tools/list" and "id" in request:
            write_message(sys.stdout, list_all_tools(request))
        else:
            with write_lock:
                write_message(child.stdin, request)
finally:
    if child.stdin:
        child.stdin.close()
    child.wait(timeout=5)
