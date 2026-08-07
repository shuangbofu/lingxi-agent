import sys
import types
import unittest
from unittest.mock import patch


runtime = types.SimpleNamespace(
    runtime_context_value=lambda key: "",
    service_config_data=lambda code, required=True: {},
)
sys.modules.setdefault("capability_runtime", runtime)

import wiki_tools


class WikiToolsTest(unittest.TestCase):

    @patch.object(wiki_tools, "output_request", return_value=0)
    @patch.object(wiki_tools, "project_code", return_value="SYNERGY")
    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_source_search_does_not_require_document_category(self, _settings, _project, output_request):
        result = wiki_tools.call_platform(
            "wiki.source-search",
            ["--query", "页面设计", "--mode", "ALL_TERMS", "--include-history", "true", "--limit", "30"],
        )

        self.assertEqual(0, result)
        output_request.assert_called_once_with(
            "http://localhost:8081",
            "token",
            "GET",
            "/api/project-context/wiki/source-search",
            {
                "projectCode": "SYNERGY",
                "query": "页面设计",
                "mode": "ALL_TERMS",
                "includeHistory": "true",
                "limit": "30",
            },
        )

    @patch.object(wiki_tools, "output_request", return_value=0)
    @patch.object(wiki_tools, "project_code", return_value="SYNERGY")
    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_source_search_batch_posts_all_queries_once(self, _settings, _project, output_request):
        result = wiki_tools.call_platform(
            "wiki.source-search-batch",
            [
                "--queries", "标准,协议，生成,场景,模板,签署",
                "--mode", "LITERAL",
                "--include-history", "true",
                "--limit-per-query", "20",
            ],
        )

        self.assertEqual(0, result)
        output_request.assert_called_once_with(
            "http://localhost:8081",
            "token",
            "POST",
            "/api/project-context/wiki/source-search-batch",
            {"projectCode": "SYNERGY"},
            {
                "queries": ["标准", "协议", "生成", "场景", "模板", "签署"],
                "mode": "LITERAL",
                "includeHistory": True,
                "limitPerQuery": 20,
            },
        )

    @patch.object(wiki_tools, "output_request", return_value=0)
    @patch.object(wiki_tools, "project_code", return_value="SYNERGY")
    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_source_raw_read_is_chunked(self, _settings, _project, output_request):
        result = wiki_tools.call_platform(
            "wiki.source-read-raw", ["--id", "162", "--offset", "20000", "--limit", "10000"]
        )

        self.assertEqual(0, result)
        output_request.assert_called_once_with(
            "http://localhost:8081",
            "token",
            "GET",
            "/api/project-context/wiki/sources/162/raw",
            {"projectCode": "SYNERGY", "offset": "20000", "limit": "10000"},
        )

    @patch.object(wiki_tools, "output_request", return_value=0)
    @patch.object(wiki_tools, "project_code", return_value="SYNERGY")
    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_source_changes_supports_query(self, _settings, _project, output_request):
        result = wiki_tools.call_platform(
            "wiki.source-changes", ["--id", "162", "--query", "审批逻辑", "--limit", "50"]
        )

        self.assertEqual(0, result)
        output_request.assert_called_once_with(
            "http://localhost:8081",
            "token",
            "GET",
            "/api/project-context/wiki/sources/162/changes",
            {"projectCode": "SYNERGY", "query": "审批逻辑", "limit": "50"},
        )

    @patch.object(wiki_tools, "output_request", return_value=0)
    @patch.object(wiki_tools, "project_code", return_value="SYNERGY")
    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_source_image_reanalysis_posts_question(self, _settings, _project, output_request):
        result = wiki_tools.call_platform(
            "wiki.source-image-reanalyze",
            ["--id", "162", "--media-id", "31", "--question", "是否新增审批按钮？"],
        )

        self.assertEqual(0, result)
        output_request.assert_called_once_with(
            "http://localhost:8081",
            "token",
            "POST",
            "/api/project-context/wiki/sources/162/media/31/reanalyze",
            {"projectCode": "SYNERGY"},
            {"question": "是否新增审批按钮？"},
        )

    @patch.object(wiki_tools, "provider_settings", return_value=("http://localhost:8081", "token"))
    def test_missing_project_code_stops_before_request(self, _settings):
        with patch.object(wiki_tools.runtime, "runtime_context_value", return_value=""):
            result = wiki_tools.call_platform("wiki.source-list", [])

        self.assertEqual(2, result)


if __name__ == "__main__":
    unittest.main()
