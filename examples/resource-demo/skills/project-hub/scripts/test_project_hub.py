import io
import json
import os
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch


runtime = types.SimpleNamespace(
    runtime_context_value=lambda *keys: "",
    runtime_context_put=lambda **kwargs: None,
    capability_config_data=lambda config_id: {},
    service_config_data=lambda code, required=True: {},
)
sys.modules.setdefault("capability_runtime", runtime)

import project_hub

PROVIDER_URL = "https://project-service.example.test"


class ProjectHubTest(unittest.TestCase):

    def test_project_list_passes_query_to_provider_without_full_list_fallback(self):
        with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "", "", "token")), \
                patch.object(project_hub, "request_data", return_value=[]) as request_data:
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = project_hub.call_platform("project-hub.project-list", ["--query", "示例服务"])

        self.assertEqual(0, exit_code)
        self.assertEqual([], json.loads(output.getvalue()))
        request_data.assert_called_once_with(
            PROVIDER_URL, "token", "GET", "/api/project-context/projects",
            {"query": "示例服务", "keywords": []}
        )

    def test_project_list_queries_multiple_keywords_once_and_returns_summaries(self):
        projects = [{
            "id": 1,
            "name": "示例服务",
            "code": "sample-service",
            "description": "示例后端服务",
            "repositoryUrl": "ssh://git.example.test/demo/sample-service.git",
            "properties": {"applicationKind": "BACKEND", "internal": "hidden"},
            "createdAt": "2026-07-24T12:00:00",
        }]
        with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "", "", "token")), \
                patch.object(project_hub, "request_data", return_value=projects) as request_data:
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = project_hub.call_platform(
                    "project-hub.project-list",
                    ["--keyword", "示例", "--keyword", "用户", "--keywords", "报表,统计"],
                )

        self.assertEqual(0, exit_code)
        self.assertEqual([{
            "id": 1,
            "name": "示例服务",
            "code": "sample-service",
            "description": "示例后端服务",
            "applicationKind": "BACKEND",
        }], json.loads(output.getvalue()))
        request_data.assert_called_once_with(
            PROVIDER_URL, "token", "GET", "/api/project-context/projects",
            {"query": None, "keywords": ["示例", "用户", "报表", "统计"]}
        )

    def test_project_list_uses_legacy_query_for_one_new_keyword(self):
        with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "", "", "token")), \
                patch.object(project_hub, "request_data", return_value=[]) as request_data:
            with redirect_stdout(io.StringIO()):
                project_hub.call_platform("project-hub.project-list", ["--keyword", "示例服务"])

        request_data.assert_called_once_with(
            PROVIDER_URL, "token", "GET", "/api/project-context/projects",
            {"query": "示例服务", "keywords": []}
        )

    def test_resolve_project_id_uses_provider_query(self):
        projects = [{"id": 5, "code": "sample-center", "name": "示例中心"}]
        with patch.object(project_hub, "request_data", return_value=projects) as request_data:
            project_id = project_hub.resolve_project_id(PROVIDER_URL, "token", "示例中心")

        self.assertEqual("5", project_id)
        request_data.assert_called_once_with(
            PROVIDER_URL, "token", "GET", "/api/project-context/projects", {"query": "示例中心"}
        )

    def test_resolve_project_id_accepts_resource_memory_application_ref(self):
        with patch.object(project_hub, "request_data") as request_data:
            project_id = project_hub.resolve_project_id(PROVIDER_URL, "token", "app:1")

        self.assertEqual("1", project_id)
        request_data.assert_not_called()

    def test_missing_project_is_recoverable_result(self):
        with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "", "", "token")):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = project_hub.call_platform("project-hub.project-context", [])

        result = json.loads(output.getvalue())
        self.assertEqual(0, exit_code)
        self.assertEqual("NEED_PROJECT", result["status"])
        self.assertIn("project-list", result["availableAction"])

    def test_ambiguous_project_is_recoverable_result(self):
        projects = [
            {"id": 5, "code": "sample-center", "name": "示例中心"},
            {"id": 6, "code": "sample-template-center", "name": "示例模板中心"},
        ]
        with patch.object(project_hub, "request_data", return_value=projects):
            output = io.StringIO()
            with redirect_stdout(output), self.assertRaises(SystemExit) as exit_result:
                project_hub.resolve_project_id(PROVIDER_URL, "token", "示例")

        result = json.loads(output.getvalue())
        self.assertEqual(0, exit_result.exception.code)
        self.assertEqual("NEED_PROJECT", result["status"])
        self.assertEqual(2, len(result["candidates"]))

    def test_project_context_exposes_selected_environment(self):
        data = {
            "project": {"id": 1, "name": "Demo", "code": "demo"},
            "environments": [
                {"id": 1, "code": "prod", "name": "PROD", "deploymentBranch": "main"},
                {"id": 2, "code": "uat", "name": "UAT", "deploymentBranch": "uat-deploy"},
            ],
            "resourceConfigs": [{
                "id": 8,
                "configType": "other",
                "name": "UAT环境资料",
                "config": {"entryUrl": "http://uat.example.com", "owner": "tester"},
            }],
        }

        result = project_hub.ingest_project_context(data, "1", "UAT")

        self.assertEqual("uat", result["selectedEnvironment"]["code"])
        self.assertEqual("uat-deploy", result["selectedEnvironment"]["deploymentBranch"])
        self.assertNotIn("config", result["environments"][0])
        self.assertNotIn("config", result["resourceConfigs"][0])
        self.assertEqual(["entryUrl", "owner"], result["resourceConfigs"][0]["configKeys"])

    def test_config_list_returns_summary_without_secret_values(self):
        configs = [{
            "id": 8,
            "projectId": 1,
            "environmentCode": "uat",
            "configType": "other",
            "name": "UAT环境资料",
            "config": {"entryUrl": "http://uat.example.com", "owner": "tester"},
        }]
        with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "1", "uat", "token")), \
                patch.object(project_hub, "request_data", return_value=configs):
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = project_hub.call_platform("project-hub.config-list", ["--type", "other"])

        result = json.loads(output.getvalue())
        self.assertEqual(0, exit_code)
        self.assertNotIn("config", result[0])
        self.assertEqual(["entryUrl", "owner"], result[0]["configKeys"])

    def test_config_info_exports_prepared_resource(self):
        config = {
            "id": 15,
            "projectId": 1,
            "environmentCode": "uat",
            "configType": "jdbc",
            "name": "订单库",
            "config": {"jdbcUrl": "jdbc:mysql://example/orders", "password": "secret"},
        }
        with tempfile.TemporaryDirectory() as directory:
            output_file = os.path.join(directory, "order-jdbc.json")
            with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "1", "uat", "token")), \
                    patch.object(project_hub, "request_data", return_value=config):
                output = io.StringIO()
                with redirect_stdout(output):
                    exit_code = project_hub.call_platform(
                        "project-hub.config-info", ["--id", "15", "--output-file", output_file]
                    )

            result = json.loads(output.getvalue())
            self.assertEqual(0, exit_code)
            self.assertEqual({
                "type": "jdbc-config",
                "ref": "config:15",
                "file": output_file,
                "source": "project-hub config-info --id 15",
            }, result["preparedResource"])
            with open(output_file, "r", encoding="utf-8") as file:
                self.assertEqual(config["config"], json.load(file))

    def test_repository_config_uses_selected_environment_deployment_branch(self):
        repository = {
            "id": 7,
            "projectId": 1,
            "name": "Demo",
            "code": "demo",
            "repositoryUrl": "ssh://example/demo.git",
            "baseBranch": "main",
        }
        environment = {
            "id": 2,
            "code": "uat",
            "name": "UAT",
            "deploymentBranch": "uat-deploy",
        }

        result = project_hub.repository_config_payload(repository, environment)

        self.assertEqual("main", result["baseBranch"])
        self.assertEqual("uat", result["environmentCode"])
        self.assertEqual("uat-deploy", result["deploymentBranch"])

    def test_repository_info_exports_prepared_resource(self):
        repository = {
            "id": 7,
            "projectId": 1,
            "name": "Demo",
            "code": "demo",
            "repositoryUrl": "ssh://example/demo.git",
            "baseBranch": "main",
        }
        environment = {
            "id": 2,
            "code": "uat",
            "name": "UAT",
            "deploymentBranch": "uat-deploy",
        }
        with tempfile.TemporaryDirectory() as directory:
            output_file = os.path.join(directory, "repository.json")
            with patch.object(project_hub, "provider_settings", return_value=(PROVIDER_URL, "1", "uat", "token")), \
                    patch.object(project_hub, "request_data", side_effect=[[repository], [environment]]):
                output = io.StringIO()
                with redirect_stdout(output):
                    exit_code = project_hub.call_platform(
                        "project-hub.repository-info", ["--id", "7", "--output-file", output_file]
                    )

            result = json.loads(output.getvalue())
            self.assertEqual(0, exit_code)
            self.assertEqual({
                "type": "git-repository-config",
                "ref": "repository:7",
                "file": output_file,
                "source": "project-hub repository-info --id 7",
            }, result["preparedResource"])
            with open(output_file, "r", encoding="utf-8") as file:
                exported = json.load(file)
            self.assertEqual("uat", exported["environmentCode"])
            self.assertEqual("uat-deploy", exported["deploymentBranch"])

    def test_repository_output_uses_current_task_workspace_for_runtime_input_path(self):
        repository = {
            "id": 7,
            "projectId": 1,
            "name": "Demo",
            "code": "demo",
            "repositoryUrl": "ssh://example/demo.git",
        }
        with tempfile.TemporaryDirectory() as directory, patch.dict(
                os.environ, {"AGENT_RUNTIME_WORKSPACE": directory}):
            result = project_hub.write_repository_file_if_requested(
                ["--output-file", "/root/.agent-task/runtime-inputs/repository.json"], repository
            )

            expected_file = os.path.join(directory, ".agent-task", "runtime-inputs", "repository.json")
            self.assertEqual(expected_file, result["configFile"])
            self.assertEqual(
                ".agent-task/runtime-inputs/repository.json",
                result["preparedResource"]["file"],
            )
            with open(expected_file, "r", encoding="utf-8") as file:
                self.assertEqual("demo", json.load(file)["code"])

    def test_output_file_rejects_path_outside_runtime_inputs(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
                os.environ, {"AGENT_RUNTIME_WORKSPACE": directory}):
            with self.assertRaisesRegex(ValueError, "runtime-inputs"):
                project_hub.resolve_output_file("/root/repository.json")


if __name__ == "__main__":
    unittest.main()
