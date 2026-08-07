#!/usr/bin/env python3
import argparse
import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import types


ROOT = pathlib.Path(__file__).resolve().parents[3]
SKILLS = ROOT / "examples" / "resource-demo" / "skills"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def capture(call):
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        exit_code = call()
    if exit_code != 0:
        raise RuntimeError(f"Skill exited with code {exit_code}: {output.getvalue()}")
    return json.loads(output.getvalue())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8091")
    parser.add_argument("--token", default="lingxi-demo-token")
    args = parser.parse_args()

    runtime = types.SimpleNamespace(
        runtime_context_value=lambda *keys: "openai-codex" if "projectCode" in keys else "",
        runtime_context_put=lambda **kwargs: None,
        capability_config_data=lambda config_id: {},
        service_config_data=lambda code, required=True: {
            "config": {"baseUrl": args.base_url, "token": args.token}
        },
    )
    sys.modules["capability_runtime"] = runtime

    project_hub = load_module(
        "demo_project_hub", SKILLS / "project-hub" / "scripts" / "project_hub.py"
    )
    wiki_tools = load_module(
        "demo_wiki_tools", SKILLS / "wiki-ingest" / "scripts" / "wiki_tools.py"
    )
    http_log = load_module(
        "demo_http_log", SKILLS / "http-log-read" / "scripts" / "http_log_read.py"
    )

    projects = capture(lambda: project_hub.call_platform("project-hub.project-list", ["--query", "Codex"]))
    if not projects or projects[0].get("code") != "openai-codex":
        raise RuntimeError("project-hub did not return the seeded Codex project")
    project_id = str(projects[0]["id"])

    context = capture(lambda: project_hub.call_platform(
        "project-hub.project-context", ["--project-id", project_id, "--environment", "main"]
    ))
    if context.get("selectedEnvironment", {}).get("code") != "main":
        raise RuntimeError("project-hub did not select the main environment")
    repositories = context.get("repositories") or []
    if len(repositories) != 1 or repositories[0].get("code") != "codex":
        raise RuntimeError("project-hub did not return the Codex repository")

    configs = capture(lambda: project_hub.call_platform(
        "project-hub.config-list", ["--project-id", project_id, "--environment", "main", "--type", "http-log"]
    ))
    if len(configs) != 1:
        raise RuntimeError("project-hub did not expose one HTTP log config")

    jdbc_configs = capture(lambda: project_hub.call_platform(
        "project-hub.config-list", ["--project-id", project_id, "--environment", "main", "--type", "jdbc"]
    ))
    if jdbc_configs:
        raise RuntimeError("project-hub exposed an unexpected default JDBC config")

    with tempfile.TemporaryDirectory() as directory:
        config_file = pathlib.Path(directory) / "http-log.json"
        capture(lambda: project_hub.call_platform(
            "project-hub.config-info", ["--id", configs[0]["id"], "--output-file", str(config_file)]
        ))

        search = capture(lambda: wiki_tools.call_platform(
            "wiki.source-search", ["--query", "Agent 执行循环", "--mode", "LITERAL"]
        ))
        if not search or search[0].get("title") != "Codex 项目阅读线索":
            raise RuntimeError("wiki-ingest did not find the seeded Markdown document")

        original_argv = sys.argv
        try:
            sys.argv = ["http-log", "search", "--config-file", str(config_file),
                        "--must", "demo-trace-1001", "--keyword", "模型请求超时"]
            log_result = capture(http_log.main)
        finally:
            sys.argv = original_argv
        if not log_result.get("results"):
            raise RuntimeError("http-log-read did not find the seeded trace")

    print(json.dumps({
        "status": "SUCCESS",
        "projectCode": "openai-codex",
        "environment": "main",
        "repository": "codex",
        "database": None,
        "document": "Codex 项目阅读线索",
        "traceId": "demo-trace-1001",
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
