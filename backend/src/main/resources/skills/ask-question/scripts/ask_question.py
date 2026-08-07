#!/usr/bin/env python3
import json
import sys
from pathlib import Path
import capability_runtime as runtime


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def bool_option(args, name, default=True):
    value = option(args, name)
    if value is None:
        return default
    return str(value).strip().lower() not in ("false", "0", "no", "n")


def int_option(args, name):
    value = option(args, name)
    return int(value) if value else None


def content_option(args):
    content_file = option(args, "--content-file")
    if content_file:
        return Path(content_file).read_text(encoding="utf-8")
    return option(args, "--content")


def parse_options(args):
    value = option(args, "--options")
    if not value:
        return []
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError("--options must be a JSON array")
    result = []
    for item in parsed:
        if isinstance(item, str):
            result.append({"label": item, "value": item})
        elif isinstance(item, dict):
            result.append({
                "label": str(item.get("label") or item.get("value") or ""),
                "value": str(item.get("value") or item.get("label") or ""),
                "description": item.get("description"),
            })
    return [item for item in result if item["label"]]


def parse_fields(args):
    value = option(args, "--fields")
    if not value:
        return []
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError("--fields must be a JSON array")
    result = []
    for item in parsed:
        if not isinstance(item, dict):
            continue
        key = str(item.get("key") or "").strip()
        label = str(item.get("label") or item.get("name") or key).strip()
        if not key or not label:
            continue
        result.append({
            "key": key,
            "label": label,
            "type": item.get("type") or "TEXT",
            "options": item.get("options") or [],
            "required": item.get("required", True),
            "placeholder": item.get("placeholder"),
            "defaultValue": item.get("defaultValue"),
            "description": item.get("description"),
            "contextKey": item.get("contextKey") or key,
        })
    return result


def parse_actions(args):
    value = option(args, "--actions")
    if not value:
        return []
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError("--actions must be a JSON array")
    result = []
    for item in parsed:
        if not isinstance(item, dict):
            continue
        key = str(item.get("key") or "").strip()
        label = str(item.get("label") or key).strip()
        if not key or not label:
            continue
        result.append({
            "key": key,
            "label": label,
            "description": item.get("description"),
            "style": item.get("style"),
            "validateInput": bool(item.get("validateInput", False)),
        })
    return result


def call_platform(command, args):
    supported_commands = ("ask-question.ask", "ask-question.choose", "ask-question.select", "ask-question.yes-no", "ask-question.date", "ask-question.datetime", "ask-question.form")
    if command not in supported_commands:
        print(f"unsupported ask-question command: {command}", file=sys.stderr)
        return 2
    question = option(args, "--question")
    if not question:
        print("missing required argument: --question", file=sys.stderr)
        return 2
    options = parse_options(args)
    fields = parse_fields(args)
    actions = parse_actions(args)
    input_type = option(args, "--input-type") or "TEXT"
    if command == "ask-question.yes-no":
        input_type = "YES_NO"
    elif command == "ask-question.date":
        input_type = "DATE"
    elif command == "ask-question.datetime":
        input_type = "DATETIME"
    elif command == "ask-question.choose":
        if not options:
            print("missing required argument: --options", file=sys.stderr)
            return 2
        input_type = "MULTI_CHOICE" if bool_option(args, "--multiple", False) else "SINGLE_CHOICE"
    elif command == "ask-question.select":
        if not options:
            print("missing required argument: --options", file=sys.stderr)
            return 2
        input_type = "SELECT"
    elif command == "ask-question.form":
        if not fields:
            print("missing required argument: --fields", file=sys.stderr)
            return 2
        input_type = "FORM"
    elif input_type.strip().upper().replace("-", "_") in ("SELECT", "SINGLE_CHOICE", "MULTI_CHOICE") and not options:
        print("choice input requires --options; use ask-question choose for candidate lists", file=sys.stderr)
        return 2
    response = runtime.interaction_ask(
        question=question,
        input_type=input_type,
        options=options,
        fields=fields,
        actions=actions,
        content=content_option(args),
        required=bool_option(args, "--required", True),
        placeholder=option(args, "--placeholder"),
        answer_hint=option(args, "--answer-hint"),
        default_value=option(args, "--default-value"),
        context_key=option(args, "--context-key"),
        timeout_seconds=int_option(args, "--timeout-seconds"),
    )
    sys.stdout.write(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
