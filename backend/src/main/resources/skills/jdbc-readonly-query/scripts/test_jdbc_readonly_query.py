import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("jdbc_readonly_query.py")
SPEC = importlib.util.spec_from_file_location("jdbc_readonly_query", MODULE_PATH)
JDBC_QUERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JDBC_QUERY)


class JdbcReadonlyQueryTest(unittest.TestCase):

    def test_mysql_connection_applies_url_charset_and_collation(self):
        pymysql = self.fake_pymysql()
        dsn = "//db.example:3307/demo?characterEncoding=utf8mb4&connectionCollation=utf8mb4_general_ci"

        with mock.patch.dict(sys.modules, {"pymysql": pymysql}):
            JDBC_QUERY.connect_mysql(dsn, {"username": "reader", "password": "secret"})

        options = pymysql.connect.call_args[1]
        self.assertEqual("utf8mb4", options["charset"])
        self.assertEqual("SET NAMES utf8mb4 COLLATE utf8mb4_general_ci", options["init_command"])
        self.assertEqual("db.example", options["host"])
        self.assertEqual(3307, options["port"])

    def test_mysql_connection_accepts_resource_fields(self):
        pymysql = self.fake_pymysql()

        with mock.patch.dict(sys.modules, {"pymysql": pymysql}):
            JDBC_QUERY.connect_mysql("//db.example/demo", {
                "characterEncoding": "utf8mb4",
                "connectionCollation": "utf8mb4_unicode_ci",
            })

        options = pymysql.connect.call_args[1]
        self.assertEqual("utf8mb4", options["charset"])
        self.assertEqual("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci", options["init_command"])

    def test_mysql_connection_rejects_unsafe_collation(self):
        pymysql = self.fake_pymysql()

        with mock.patch.dict(sys.modules, {"pymysql": pymysql}), self.assertRaisesRegex(ValueError, "invalid characters"):
            JDBC_QUERY.connect_mysql("//db.example/demo?connectionCollation=utf8mb4_general_ci%3BDROP", {})

        pymysql.connect.assert_not_called()

    @staticmethod
    def fake_pymysql():
        module = types.SimpleNamespace()
        module.cursors = types.SimpleNamespace(Cursor=object())
        module.connect = mock.Mock(return_value=object())
        return module


if __name__ == "__main__":
    unittest.main()
