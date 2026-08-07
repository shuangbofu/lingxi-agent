import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("jdbc_change_execute.py")
SPEC = importlib.util.spec_from_file_location("jdbc_change_execute", MODULE_PATH)
JDBC_CHANGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JDBC_CHANGE)


class JdbcChangeExecuteTest(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database_path = Path(self.temp_dir.name) / "change-test.db"
        self.backup_path = Path(self.temp_dir.name) / "backup.json"
        self.config = {"url": "jdbc:sqlite:" + str(self.database_path)}
        with sqlite3.connect(self.database_path) as connection:
            connection.execute("create table sample (id text primary key, name text not null, status integer not null)")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_insert_preflight_execute_and_rollback(self):
        plan = {
            "maxRows": 1,
            "statements": [{
                "sql": "insert into sample (id, name, status) values ('row-1', '新增记录', 1)",
                "expectedMaxRows": 1,
            }],
        }

        result = JDBC_CHANGE.preflight(self.config, plan, str(self.backup_path), 1)
        backup = JDBC_CHANGE.read_json(self.backup_path)
        execute_result = JDBC_CHANGE.execute(self.config, backup, 1)

        self.assertEqual("INSERT", result["statements"][0]["operation"])
        self.assertEqual(1, execute_result["statements"][0]["affectedRows"])
        self.assertEqual([("row-1", "新增记录", 1)], self.rows())

        rollback_result = JDBC_CHANGE.rollback(self.config, backup)

        self.assertEqual(1, rollback_result["statements"][0]["restoredRows"])
        self.assertEqual([], self.rows())

    def test_update_preflight_execute_and_rollback(self):
        self.insert_row("row-1", "原记录", 1)
        plan = {
            "statements": [{
                "sql": "update sample set name = '已修改' where id = 'row-1'",
                "expectedMaxRows": 1,
            }],
        }

        JDBC_CHANGE.preflight(self.config, plan, str(self.backup_path), 1)
        backup = JDBC_CHANGE.read_json(self.backup_path)
        JDBC_CHANGE.execute(self.config, backup, 1)

        self.assertEqual([("row-1", "已修改", 1)], self.rows())

        JDBC_CHANGE.rollback(self.config, backup)

        self.assertEqual([("row-1", "原记录", 1)], self.rows())

    def test_delete_preflight_execute_and_rollback(self):
        self.insert_row("row-1", "待删除", 1)
        plan = {
            "statements": [{
                "sql": "delete from sample where id = 'row-1'",
                "expectedMaxRows": 1,
                "allowPhysicalDelete": True,
            }],
        }

        result = JDBC_CHANGE.preflight(self.config, plan, str(self.backup_path), 1)
        backup = JDBC_CHANGE.read_json(self.backup_path)
        JDBC_CHANGE.execute(self.config, backup, 1)

        self.assertEqual("DELETE", result["statements"][0]["operation"])
        self.assertEqual([], self.rows())

        JDBC_CHANGE.rollback(self.config, backup)

        self.assertEqual([("row-1", "待删除", 1)], self.rows())

    def test_execute_rejects_rows_changed_after_preflight(self):
        self.insert_row("row-1", "原记录", 1)
        plan = {
            "statements": [{
                "sql": "update sample set status = 2 where id = 'row-1'",
                "expectedMaxRows": 1,
            }],
        }
        JDBC_CHANGE.preflight(self.config, plan, str(self.backup_path), 1)
        backup = JDBC_CHANGE.read_json(self.backup_path)
        with sqlite3.connect(self.database_path) as connection:
            connection.execute("update sample set name = '并发修改' where id = 'row-1'")

        with self.assertRaisesRegex(ValueError, "changed after preflight"):
            JDBC_CHANGE.execute(self.config, backup, 1)

    def test_rejects_insert_without_id_and_unconfirmed_delete(self):
        with self.assertRaisesRegex(ValueError, "explicit id"):
            JDBC_CHANGE.normalize_plan({"statements": [{"sql": "insert into sample (name, status) values ('记录', 1)"}]})

        with self.assertRaisesRegex(ValueError, "allowPhysicalDelete"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "delete from sample where id = 'row-1'",
                "expectedMaxRows": 1,
            }]})

    def test_rejects_primary_key_update_and_unsafe_insert_shapes(self):
        with self.assertRaisesRegex(ValueError, "must not update id"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "update sample set id = 'row-2' where id = 'row-1'",
                "expectedMaxRows": 1,
            }]})

        with self.assertRaisesRegex(ValueError, "single-row INSERT"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "insert into sample (id, name, status) values ('row-1', '记录一', 1), ('row-2', '记录二', 1)",
            }]})

        with self.assertRaisesRegex(ValueError, "explicit non-null"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "insert into sample (id, name, status) values ('row-1' OR 1=1, '记录', 1)",
            }]})

        with self.assertRaisesRegex(ValueError, "denied keyword: select"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "update sample set status = 2 where id in (select id from sample)",
                "expectedMaxRows": 1,
            }]})

        with self.assertRaisesRegex(ValueError, "semicolons"):
            JDBC_CHANGE.normalize_plan({"statements": [{
                "sql": "update sample set status = 2 where id = 'row-1'; delete from sample where id = 'row-2'",
                "expectedMaxRows": 1,
            }]})

    def insert_row(self, row_id, name, status):
        with sqlite3.connect(self.database_path) as connection:
            connection.execute("insert into sample (id, name, status) values (?, ?, ?)", (row_id, name, status))

    def rows(self):
        with sqlite3.connect(self.database_path) as connection:
            return connection.execute("select id, name, status from sample order by id").fetchall()


if __name__ == "__main__":
    unittest.main()
