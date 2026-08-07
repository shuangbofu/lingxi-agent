#!/usr/bin/env python3
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import capability_runtime as runtime


RUNTIME_INPUTS_DIRECTORY = ".agent-task/runtime-inputs"


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def options(args, name):
    values = []
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            values.append(args[index + 1])
        elif item.startswith(name + "="):
            values.append(item[len(name) + 1:])
    return values


def read_text(path):
    with open(path, "r", encoding="utf-8") as file:
        return file.read()


def text_option(args, name):
    file_value = option(args, name + "-file")
    if file_value:
        return read_text(file_value)
    return option(args, name)


def resolve_output_file(output_path):
    requested = str(output_path or "").strip()
    if not requested:
        raise ValueError("output file path is required")
    workspace = str(os.environ.get("AGENT_RUNTIME_WORKSPACE") or "").strip()
    if not workspace:
        return os.path.abspath(os.path.expanduser(requested)), requested

    normalized = requested.replace("\\", "/")
    marker = RUNTIME_INPUTS_DIRECTORY + "/"
    marker_index = normalized.find(marker)
    if marker_index < 0 or not normalized[marker_index + len(marker):]:
        raise ValueError("output file must be inside {}".format(RUNTIME_INPUTS_DIRECTORY))
    relative_path = normalized[marker_index + len(marker):]
    allowed_root = os.path.abspath(os.path.join(workspace, RUNTIME_INPUTS_DIRECTORY))
    path = os.path.abspath(os.path.join(allowed_root, relative_path))
    try:
        inside_runtime_inputs = os.path.commonpath([allowed_root, path]) == allowed_root
    except ValueError:
        inside_runtime_inputs = False
    if not inside_runtime_inputs:
        raise ValueError("output file must be inside {}".format(RUNTIME_INPUTS_DIRECTORY))
    return path, marker + relative_path


def task_input_value(*keys):
    try:
        return runtime.runtime_context_value(*keys)
    except Exception:
        return ""


def context_value(key, value):
    if value is None or str(value).strip() == "":
        return None
    return {"key": key, "value": str(value).strip()}


def provider_config(args):
    config_id = option(args, "--config-id")
    if config_id:
        return runtime.capability_config_data(config_id)
    return runtime.service_config_data("project-hub", required=True)


def project_ref_option(args):
    return option(args, "--project-id") or option(args, "--project-name")


def provider_settings(args, require_project=True, allow_context_project=True):
    config_info = provider_config(args)
    config = config_info.get("config", {}) if isinstance(config_info, dict) else {}
    base_url = str(config.get("baseUrl") or "").rstrip("/")
    token = str(config.get("token") or "")
    project_ref = project_ref_option(args)
    project_id = project_ref or (task_input_value("projectId") if allow_context_project else "")
    environment = option(args, "--environment") or task_input_value("environmentCode")
    if not base_url:
        print("Project Hub provider config missing baseUrl", file=sys.stderr)
        raise SystemExit(2)
    if project_id:
        project_id = resolve_project_id(base_url, token, project_id)
    if require_project and not project_id:
        print("Project Hub provider config missing projectId", file=sys.stderr)
        raise SystemExit(2)
    return base_url, project_id, environment, token


def resolve_project_id(base_url, token, project_ref):
    value = str(project_ref or "").strip()
    if not value:
        return ""
    if value.isdigit():
        return value
    resource_type, separator, resource_id = value.partition(":")
    if separator and resource_type.lower() == "app" and resource_id.isdigit():
        return resource_id
    projects = request_data(base_url, token, "GET", "/api/project-context/projects", {"query": value}) or []
    matches = match_projects(projects, value)
    if len(matches) == 1:
        project_id = matches[0].get("id")
        return "" if project_id is None else str(project_id)
    if len(matches) > 1:
        sys.stdout.write(json.dumps({
            "status": "NEED_PROJECT",
            "message": "项目名称匹配到多个候选，当前命令尚未执行。请提供一个明确的项目 ID、编码或名称后重试原命令。",
            "query": value,
            "candidates": [project_candidate_summary(item) for item in matches],
        }, ensure_ascii=False))
        raise SystemExit(0)
    return ""


def match_projects(projects, query):
    keyword = str(query or "").strip().lower()
    if not keyword:
        return []
    candidates = [project for project in projects if isinstance(project, dict)]
    exact_matches = [
        project for project in candidates
        if keyword in (
            str(project.get("id") or "").strip().lower(),
            str(project.get("code") or "").strip().lower(),
            str(project.get("name") or "").strip().lower(),
        )
    ]
    if exact_matches:
        return exact_matches
    return filter_projects(candidates, keyword)


def missing_project_response(command):
    sys.stdout.write(json.dumps({
        "status": "NEED_PROJECT",
        "message": "缺少项目参数，当前命令尚未执行。",
        "command": command,
        "availableAction": "有明确应用识别线索时用 project-hub project-list 批量查询这些线索；没有可靠线索时只读取一次精简候选概览。确定项目后补充 --project-id 重试原命令，仍有实质歧义时再交互。"
    }, ensure_ascii=False))
    return 0


def request_data(base_url, token, method, path, params=None, data=None):
    query = urllib.parse.urlencode(
        {key: value for key, value in (params or {}).items() if value not in (None, "", [])},
        doseq=True,
    )
    url = f"{base_url}{path}" + (f"?{query}" if query else "")
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            text = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        sys.stdout.write(exc.read().decode("utf-8", errors="replace"))
        raise SystemExit(1)
    return unwrap_response(json.loads(text)) if text else None


def unwrap_response(value):
    if isinstance(value, dict) and "code" in value and "data" in value:
        if value.get("code") != "SUCCESS":
            sys.stdout.write(json.dumps(value, ensure_ascii=False))
            raise SystemExit(1)
        return value.get("data")
    return value


def request_json(base_url, token, method, path, params=None, data=None):
    sys.stdout.write(json.dumps(request_data(base_url, token, method, path, params, data), ensure_ascii=False))
    return 0


def context_values_from_project(project, project_id, environment):
    values = [
        context_value("projectId", project_id),
        context_value("environmentCode", environment),
    ]
    if isinstance(project, dict):
        values.extend([
            context_value("projectName", project.get("name")),
            context_value("projectCode", project.get("code")),
        ])
    return [item for item in values if item]


def find_environment(environments, environment):
    target = str(environment or "").strip().lower()
    if not target:
        return None
    for item in environments or []:
        if not isinstance(item, dict):
            continue
        candidates = (item.get("id"), item.get("code"), item.get("name"))
        if any(str(value or "").strip().lower() == target for value in candidates):
            return item
    return None


def ingest_project_context(data, project_id, environment):
    if not isinstance(data, dict):
        return data
    project = data.get("project") if isinstance(data.get("project"), dict) else data
    values = context_values_from_project(project, project_id, environment)
    selected_environment = find_environment(data.get("environments"), environment)
    if selected_environment:
        values.append(context_value("deploymentBranch", selected_environment.get("deploymentBranch")))
    if values:
        runtime.runtime_context_put(values=[item for item in values if item])
    result = dict(data)
    result["relatedProjects"] = [
        project_candidate_summary(item)
        for item in data.get("relatedProjects", [])
        if isinstance(item, dict)
    ]
    result["repositories"] = [
        repository_summary(item)
        for item in data.get("repositories", [])
        if isinstance(item, dict)
    ]
    result["environments"] = [
        environment_summary(item)
        for item in data.get("environments", [])
        if isinstance(item, dict)
    ]
    result["relations"] = [
        relation_summary(item)
        for item in data.get("relations", [])
        if isinstance(item, dict)
    ]
    result["resourceConfigs"] = [
        resource_config_summary(item)
        for item in data.get("resourceConfigs", [])
        if isinstance(item, dict)
    ]
    result["selectedEnvironment"] = environment_summary(selected_environment) if selected_environment else None
    if environment and not selected_environment:
        result["environmentSelectionStatus"] = "NOT_FOUND"
    return result


def project_candidate_summary(data):
    return {
        key: value
        for key, value in {
            "id": data.get("id"),
            "name": data.get("name"),
            "code": data.get("code"),
            "description": data.get("description"),
            "businessDomain": data.get("businessDomain"),
            "applicationKind": data.get("applicationKind")
                or (data.get("properties") or {}).get("applicationKind"),
        }.items()
        if value not in (None, "", [], {})
    }


def environment_summary(data):
    config = data.get("config") if isinstance(data.get("config"), dict) else {}
    properties = data.get("properties") if isinstance(data.get("properties"), dict) else {}
    return {
        key: value
        for key, value in {
            "id": data.get("id"),
            "projectId": data.get("projectId") or data.get("appId"),
            "code": data.get("code"),
            "name": data.get("name"),
            "description": data.get("description"),
            "environmentType": data.get("environmentType"),
            "deploymentBranch": data.get("deploymentBranch"),
            "configKeys": sorted(config.keys()),
            "propertyKeys": sorted(properties.keys()),
        }.items()
        if value not in (None, "", [], {})
    }


def repository_summary(data):
    return {
        key: value
        for key, value in {
            "id": data.get("id"),
            "projectId": data.get("projectId"),
            "environmentId": data.get("environmentId"),
            "environmentCode": data.get("environmentCode"),
            "environmentName": data.get("environmentName"),
            "name": data.get("name"),
            "code": data.get("code"),
            "description": data.get("description"),
            "repositoryUrl": data.get("repositoryUrl"),
            "baseBranch": data.get("baseBranch"),
            "branchAliases": data.get("branchAliases") or [],
            "primaryRepo": data.get("primaryRepo"),
            "enabled": data.get("enabled"),
        }.items()
        if value not in (None, "", [], {})
    }


def relation_summary(data):
    return {
        key: value
        for key, value in {
            "id": data.get("id"),
            "relatedProjectId": data.get("relatedProjectId") or data.get("relatedAppId"),
            "relatedProjectName": data.get("relatedProjectName") or data.get("relatedAppName"),
            "relatedProjectCode": data.get("relatedProjectCode") or data.get("relatedAppCode"),
            "direction": data.get("direction"),
            "relationType": data.get("relationType"),
            "name": data.get("name"),
            "description": data.get("description"),
            "scenarios": data.get("scenarios") or [],
            "enabled": data.get("enabled"),
        }.items()
        if value not in (None, "", [], {})
    }


def resource_config_summary(data):
    config = data.get("config") if isinstance(data.get("config"), dict) else {}
    return {
        key: value
        for key, value in {
            "id": data.get("id"),
            "projectId": data.get("projectId"),
            "environmentId": data.get("environmentId"),
            "environmentCode": data.get("environmentCode"),
            "environmentName": data.get("environmentName"),
            "configType": data.get("configType"),
            "configCode": data.get("configCode"),
            "name": data.get("name"),
            "description": data.get("description"),
            "labels": data.get("labels") or [],
            "properties": data.get("properties") or {},
            "enabled": data.get("enabled"),
            "configKeys": sorted(config.keys()),
        }.items()
        if value not in (None, "", [], {})
    }


def write_config_file_if_requested(args, data):
    output_path = option(args, "--output-file")
    if not output_path:
        return data
    config = data.get("config") if isinstance(data, dict) and isinstance(data.get("config"), dict) else {}
    path, logical_path = resolve_output_file(output_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as file:
        json.dump(config, file, ensure_ascii=False, indent=2)
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass
    return {
        "id": data.get("id"),
        "projectId": data.get("projectId"),
        "environmentCode": data.get("environmentCode"),
        "configType": data.get("configType"),
        "configCode": data.get("configCode"),
        "name": data.get("name"),
        "description": data.get("description"),
        "labels": data.get("labels") or [],
        "properties": data.get("properties") or {},
        "configFile": path,
        "configKeys": sorted(config.keys()),
        "preparedResource": {
            "type": "{}-config".format(data.get("configType") or "resource"),
            "ref": "config:{}".format(data.get("id")),
            "file": logical_path,
            "source": "project-hub config-info --id {}".format(data.get("id")),
        },
    }


def repository_config_payload(data, selected_environment=None):
    if not isinstance(data, dict):
        return {}
    config = {
        "id": data.get("id"),
        "projectId": data.get("projectId"),
        "name": data.get("name"),
        "code": data.get("code"),
        "repositoryUrl": data.get("repositoryUrl"),
        "baseBranch": data.get("baseBranch"),
        "description": data.get("description"),
        "primaryRepo": data.get("primaryRepo"),
    }
    if isinstance(selected_environment, dict):
        config["environmentCode"] = selected_environment.get("code")
        config["environmentName"] = selected_environment.get("name")
        config["deploymentBranch"] = selected_environment.get("deploymentBranch")
    properties = data.get("properties") if isinstance(data.get("properties"), dict) else {}
    config.update(properties)
    return {key: value for key, value in config.items() if value not in (None, "", [])}


def write_repository_file_if_requested(args, data, selected_environment=None):
    output_path = option(args, "--output-file")
    if not output_path:
        result = dict(data)
        if selected_environment:
            result["selectedEnvironment"] = selected_environment
        return result
    config = repository_config_payload(data, selected_environment)
    path, logical_path = resolve_output_file(output_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as file:
        json.dump(config, file, ensure_ascii=False, indent=2)
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass
    return {
        "id": data.get("id"),
        "projectId": data.get("projectId"),
        "name": data.get("name"),
        "code": data.get("code"),
        "description": data.get("description"),
        "environmentCode": config.get("environmentCode"),
        "deploymentBranch": config.get("deploymentBranch"),
        "baseBranch": config.get("baseBranch"),
        "configFile": path,
        "configKeys": sorted(config.keys()),
        "preparedResource": {
            "type": "git-repository-config",
            "ref": "repository:{}".format(data.get("id")),
            "file": logical_path,
            "source": "project-hub repository-info --id {}".format(data.get("id")),
        },
    }


def find_repository(repositories, repository_id):
    target = str(repository_id or "").strip()
    for item in repositories or []:
        if not isinstance(item, dict):
            continue
        if str(item.get("id") or "").strip() == target:
            return item
    return None


def filter_projects(projects, query):
    if not query:
        return projects
    keyword = query.lower()
    return [
        item for item in projects
        if keyword in str(item.get("name") or "").lower()
        or keyword in str(item.get("code") or "").lower()
        or keyword in str(item.get("description") or "").lower()
    ]


def knowledge_payload(args):
    return {
        "type": option(args, "--type") or "NOTE",
        "title": option(args, "--title"),
        "keywords": option(args, "--keywords"),
        "content": text_option(args, "--content"),
        "evidence": text_option(args, "--evidence"),
        "status": option(args, "--status") or "PENDING_REVIEW",
    }


def call_platform(command, args):
    if command == "project-hub.project-list":
        base_url, _project_id, _environment, token = provider_settings(args, require_project=False)
        query = option(args, "--query")
        keywords = options(args, "--keyword")
        comma_keywords = option(args, "--keywords")
        if comma_keywords:
            keywords.extend(item.strip() for item in comma_keywords.replace("，", ",").split(",") if item.strip())
        filter_terms = list(dict.fromkeys(item for item in [query, *keywords] if item))
        legacy_query = filter_terms[0] if len(filter_terms) == 1 else None
        multi_keywords = filter_terms if len(filter_terms) > 1 else []
        projects = request_data(base_url, token, "GET", "/api/project-context/projects", {
            "query": legacy_query,
            "keywords": multi_keywords,
        }) or []
        if filter_terms:
            lowered = [item.lower() for item in filter_terms]
            projects = [
                item for item in projects
                if any(filter_projects([item], keyword) for keyword in lowered)
            ]
        sys.stdout.write(json.dumps([
            project_candidate_summary(item) for item in projects if isinstance(item, dict)
        ], ensure_ascii=False))
        return 0
    if command == "project-hub.environment-list":
        base_url, project_id, _environment, token = provider_settings(args)
        return request_json(base_url, token, "GET", "/api/project-context/environments", {"projectId": project_id})
    if command == "project-hub.config-info":
        base_url, project_id, _environment, token = provider_settings(args, require_project=False, allow_context_project=False)
        config_id = option(args, "--id")
        if not config_id:
            print("missing required argument: --id", file=sys.stderr)
            return 2
        params = {"projectId": project_id} if project_id else None
        data = request_data(base_url, token, "GET", f"/api/project-context/resource-configs/{config_id}", params)
        sys.stdout.write(json.dumps(write_config_file_if_requested(args, data), ensure_ascii=False))
        return 0
    base_url, project_id, environment, token = provider_settings(args, require_project=False)
    if not project_id:
        return missing_project_response(command)
    if command == "project-hub.project-info":
        data = request_data(base_url, token, "GET", "/api/project-context/project-info", {"projectId": project_id, "environmentCode": environment})
        sys.stdout.write(json.dumps(ingest_project_context(data, project_id, environment), ensure_ascii=False))
        return 0
    if command == "project-hub.project-context":
        data = request_data(base_url, token, "GET", "/api/project-context/project-context", {"projectId": project_id, "environmentCode": environment})
        sys.stdout.write(json.dumps(ingest_project_context(data, project_id, environment), ensure_ascii=False))
        return 0
    if command == "project-hub.repository-list":
        return request_json(base_url, token, "GET", "/api/project-context/repositories", {
            "projectId": project_id,
            "environmentCode": environment,
        })
    if command == "project-hub.repository-info":
        repository_id = option(args, "--id")
        if not repository_id:
            print("missing required argument: --id", file=sys.stderr)
            return 2
        repositories = request_data(base_url, token, "GET", "/api/project-context/repositories", {
            "projectId": project_id,
            "environmentCode": environment,
        })
        repository = find_repository(repositories, repository_id)
        if not repository:
            print(f"repository not found: {repository_id}", file=sys.stderr)
            return 1
        selected_environment = None
        if environment:
            environments = request_data(base_url, token, "GET", "/api/project-context/environments", {"projectId": project_id})
            selected_environment = find_environment(environments, environment)
            if not selected_environment:
                sys.stdout.write(json.dumps({
                    "status": "NEED_ENVIRONMENT",
                    "message": "当前环境无法匹配项目中的真实环境，仓库配置尚未生成。",
                    "environment": environment,
                    "candidates": environments or [],
                }, ensure_ascii=False))
                return 0
        sys.stdout.write(json.dumps(write_repository_file_if_requested(args, repository, selected_environment), ensure_ascii=False))
        return 0
    if command == "project-hub.relation-list":
        return request_json(base_url, token, "GET", "/api/project-context/relations", {"projectId": project_id})
    if command == "project-hub.config-list":
        data = request_data(base_url, token, "GET", "/api/project-context/resource-configs", {
            "projectId": project_id,
            "environmentCode": environment,
            "configType": option(args, "--type"),
            "configCode": option(args, "--code"),
            "label": option(args, "--label")
        })
        sys.stdout.write(json.dumps([
            resource_config_summary(item) for item in (data or []) if isinstance(item, dict)
        ], ensure_ascii=False))
        return 0
    if command in ("project-hub.knowledge-save", "knowledge.save"):
        if not project_id:
            return missing_project_response(command)
        return request_json(base_url, token, "POST", "/api/project-context/knowledge", {"projectId": project_id}, knowledge_payload(args))
    if command in ("project-hub.knowledge-update", "knowledge.update"):
        if not project_id:
            return missing_project_response(command)
        knowledge_id = option(args, "--id")
        if not knowledge_id:
            print("missing required argument: --id", file=sys.stderr)
            return 2
        return request_json(base_url, token, "PUT", f"/api/project-context/knowledge/{knowledge_id}", {"projectId": project_id}, knowledge_payload(args))
    print(f"unsupported project-hub command: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
