#!/usr/bin/env python3
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

import capability_runtime as runtime


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def provider_settings():
    config_info = runtime.service_config_data("wiki-ingest", required=True)
    config = config_info.get("config", {}) if isinstance(config_info, dict) else {}
    base_url = str(config.get("baseUrl") or "").rstrip("/")
    token = str(config.get("token") or "")
    if not base_url:
        print("Wiki provider config missing baseUrl", file=sys.stderr)
        raise SystemExit(2)
    return base_url, token


def project_code():
    value = runtime.runtime_context_value("projectCode")
    if not value:
        print("missing capability parameter: projectCode", file=sys.stderr)
    return value


def request_data(base_url, token, method, path, params=None, data=None):
    query = urllib.parse.urlencode({key: value for key, value in (params or {}).items() if value not in (None, "")})
    url = f"{base_url}{path}" + (f"?{query}" if query else "")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            text = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        sys.stdout.write(exc.read().decode("utf-8", errors="replace"))
        raise SystemExit(1)
    value = json.loads(text) if text else None
    if isinstance(value, dict) and "code" in value and "data" in value:
        if value.get("code") != "SUCCESS":
            sys.stdout.write(json.dumps(value, ensure_ascii=False))
            raise SystemExit(1)
        return value.get("data")
    return value


def output_request(base_url, token, method, path, params=None, data=None):
    sys.stdout.write(json.dumps(request_data(base_url, token, method, path, params, data), ensure_ascii=False))
    return 0


def call_platform(command, args):
    base_url, token = provider_settings()
    current_project_code = project_code()
    if not current_project_code:
        return 2
    params = {"projectCode": current_project_code}

    if command == "wiki.source-tree":
        params["batchId"] = option(args, "--batch-id")
        return output_request(base_url, token, "GET", "/api/project-context/wiki/source-tree", params)
    if command == "wiki.source-list":
        params["ids"] = option(args, "--ids")
        return output_request(base_url, token, "GET", "/api/project-context/wiki/sources", params)
    if command == "wiki.source-search":
        query = required_option(args, "--query")
        if not query:
            return 2
        params.update({
            "query": query,
            "mode": option(args, "--mode") or "ALL_TERMS",
            "includeHistory": option(args, "--include-history") or "false",
            "limit": option(args, "--limit") or "20",
        })
        return output_request(base_url, token, "GET", "/api/project-context/wiki/source-search", params)
    if command == "wiki.source-search-batch":
        query_text = required_option(args, "--queries")
        if not query_text:
            return 2
        queries = [item.strip() for item in query_text.replace("，", ",").split(",") if item.strip()]
        if not queries:
            print("missing required argument: --queries", file=sys.stderr)
            return 2
        try:
            limit_per_query = int(option(args, "--limit-per-query") or "20")
        except ValueError:
            print("invalid integer argument: --limit-per-query", file=sys.stderr)
            return 2
        include_history_text = (option(args, "--include-history") or "false").lower()
        if include_history_text not in ("true", "false"):
            print("invalid boolean argument: --include-history", file=sys.stderr)
            return 2
        data = {
            "queries": queries,
            "mode": option(args, "--mode") or "LITERAL",
            "includeHistory": include_history_text == "true",
            "limitPerQuery": limit_per_query,
        }
        return output_request(
            base_url,
            token,
            "POST",
            "/api/project-context/wiki/source-search-batch",
            params,
            data,
        )
    if command == "wiki.source-diff":
        from_source_id = required_option(args, "--from-id")
        to_source_id = required_option(args, "--to-id")
        if not from_source_id or not to_source_id:
            return 2
        params.update({"fromSourceId": from_source_id, "toSourceId": to_source_id})
        return output_request(base_url, token, "GET", "/api/project-context/wiki/source-diff", params)

    source_id = required_option(args, "--id")
    if not source_id:
        return 2
    if command == "wiki.source-read":
        params.update({"offset": option(args, "--offset") or "0", "limit": option(args, "--limit") or "20000"})
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}", params)
    if command == "wiki.source-context":
        offset = required_option(args, "--offset")
        if offset is None:
            return 2
        params.update({"offset": offset, "radius": option(args, "--radius") or "1200"})
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/context", params)
    if command == "wiki.source-read-raw":
        params.update({"offset": option(args, "--offset") or "0", "limit": option(args, "--limit") or "20000"})
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/raw", params)
    if command == "wiki.source-history":
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/history", params)
    if command == "wiki.source-changes":
        params.update({
            "query": option(args, "--query"),
            "limit": option(args, "--limit") or "100",
        })
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/changes", params)
    if command == "wiki.source-media":
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/media", params)
    if command == "wiki.source-read-deep":
        params["maxImages"] = option(args, "--max-images") or "20"
        return output_request(base_url, token, "GET", f"/api/project-context/wiki/sources/{source_id}/deep", params)
    if command == "wiki.source-image-reanalyze":
        media_id = required_option(args, "--media-id")
        question = required_option(args, "--question")
        if not media_id or not question:
            return 2
        return output_request(
            base_url,
            token,
            "POST",
            f"/api/project-context/wiki/sources/{source_id}/media/{media_id}/reanalyze",
            params,
            {"question": question},
        )
    print(f"unsupported wiki command: {command}", file=sys.stderr)
    return 2


def required_option(args, name):
    value = option(args, name)
    if value in (None, ""):
        print(f"missing required argument: {name}", file=sys.stderr)
        return None
    return value


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
