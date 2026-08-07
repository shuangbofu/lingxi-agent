import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import code_repository


class CodeRepositoryCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source"
        self.origin = self.root / "origin.git"
        self.managed = self.root / "data" / "git" / "repositories"
        self.checkouts = self.root / "data" / "git" / "checkouts"
        self.worktrees = self.root / ".agent-worktrees" / "task-1"
        self.config_file = self.root / "repository.json"

        self._git("init", "-b", "main", str(self.source))
        self._git("-C", str(self.source), "config", "user.name", "Test User")
        self._git("-C", str(self.source), "config", "user.email", "test@example.com")
        (self.source / "README.md").write_text("initial\n", encoding="utf-8")
        self._git("-C", str(self.source), "add", "README.md")
        self._git("-C", str(self.source), "commit", "-m", "initial")
        self._git("clone", "--bare", str(self.source), str(self.origin))
        self._git("-C", str(self.source), "remote", "add", "origin", str(self.origin))

        self.config_file.write_text(json.dumps({
            "repositoryCode": "demo-repository",
            "name": "Demo Repository",
            "url": str(self.origin),
            "defaultBranch": "main",
        }), encoding="utf-8")
        self.environment = mock.patch.dict(os.environ, {
            "AGENT_RUNTIME_APPLICATION_ROOT": str(self.root),
            "AGENT_RUNTIME_WORKSPACE": str(self.root),
            "AGENT_RUNTIME_TASK_ID": "1",
        })
        self.environment.start()

    def tearDown(self):
        code_repository.configure_git_key_directory([])
        self.environment.stop()
        self.temp_dir.cleanup()

    def test_fresh_mirror_skips_repeated_fetch(self):
        item = code_repository.repo_item_from_config(code_repository.read_json(self.config_file))

        with mock.patch.object(code_repository.time, "time", return_value=1000):
            mirror = code_repository.ensure_mirror(item)
        first_state = code_repository.read_fetch_state(item)

        self._commit_and_push("second", "second\n", "main")
        with mock.patch.object(code_repository.time, "time", return_value=1100):
            code_repository.ensure_mirror(item)

        self.assertEqual(first_state, code_repository.read_fetch_state(item))
        cached_content = self._git(
            "--git-dir", str(mirror), "show", "main:README.md", capture_output=True
        ).stdout
        self.assertEqual("initial\n", cached_content)

        with mock.patch.object(code_repository.time, "time", return_value=1101):
            code_repository.ensure_mirror(item, force_fetch=True)
        refreshed_content = self._git(
            "--git-dir", str(mirror), "show", "main:README.md", capture_output=True
        ).stdout
        self.assertEqual("second\n", refreshed_content)

    def test_default_branch_and_cache_identity_use_the_public_repository_contract(self):
        item = code_repository.repo_item_from_config(code_repository.read_json(self.config_file))
        same_code_other_url = {**item, "url": str(self.root / "another-origin.git")}

        self.assertEqual("main", item["configuredBranch"])
        self.assertNotEqual(
            code_repository.repository_cache_key(item),
            code_repository.repository_cache_key(same_code_other_url),
        )

    def test_stale_mirror_checks_target_branch_before_fetching(self):
        item = code_repository.repo_item_from_config(code_repository.read_json(self.config_file))

        with mock.patch.object(code_repository.time, "time", return_value=1000):
            mirror = code_repository.ensure_mirror(item)
        with mock.patch.object(code_repository.time, "time", return_value=1301):
            code_repository.ensure_mirror(item, branch="main")
        unchanged_state = code_repository.read_fetch_state(item)

        self._commit_and_push("second", "second\n", "main")
        with mock.patch.object(code_repository.time, "time", return_value=1602):
            code_repository.ensure_mirror(item, branch="main")

        self.assertEqual(1301, unchanged_state["lastFetchEpoch"])
        self.assertEqual(
            "second\n",
            self._git("--git-dir", str(mirror), "show", "main:README.md", capture_output=True).stdout,
        )

    def test_tasks_can_prepare_different_branch_checkouts(self):
        self._git("-C", str(self.source), "checkout", "-b", "feature/demo")
        self._commit_and_push("feature", "feature\n", "feature/demo")

        main_result = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "main",
        ])
        os.environ["AGENT_RUNTIME_TASK_ID"] = "2"
        feature_result = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "feature/demo",
        ])

        self.assertNotEqual(main_result["worktreePath"], feature_result["worktreePath"])
        self.assertTrue(main_result["sharedCheckout"])
        self.assertTrue(feature_result["sharedCheckout"])
        self.assertEqual(
            "initial\n",
            (Path(main_result["worktreePath"]) / "README.md").read_text(encoding="utf-8"),
        )
        self.assertEqual(
            "feature\n",
            (Path(feature_result["worktreePath"]) / "README.md").read_text(encoding="utf-8"),
        )

    def test_tasks_reuse_checkout_for_same_commit(self):
        first = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "main",
        ])
        os.environ["AGENT_RUNTIME_TASK_ID"] = "2"
        second = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "main",
        ])

        self.assertEqual(first["worktreePath"], second["worktreePath"])
        self.assertEqual(first["revision"], second["revision"])
        self.assertFalse(first["cacheHit"])
        self.assertTrue(second["cacheHit"])
        self.assertFalse((self.worktrees / "task-2").exists())

    def test_change_worktrees_remain_task_isolated(self):
        first = code_repository.prepare_change_worktree([
            "--config-file", str(self.config_file),
            "--base", "main",
            "--branch-name", "example-change-one",
        ])
        os.environ["AGENT_RUNTIME_TASK_ID"] = "2"
        second = code_repository.prepare_change_worktree([
            "--config-file", str(self.config_file),
            "--base", "main",
            "--branch-name", "example-change-two",
        ])

        self.assertNotEqual(first["worktreePath"], second["worktreePath"])
        self.assertIn("task-1", first["worktreePath"])
        self.assertIn("task-2", second["worktreePath"])
        self.assertTrue(first["isolated"])
        self.assertTrue(second["isolated"])

    def test_missing_cached_branch_forces_one_refresh(self):
        item = code_repository.repo_item_from_config(code_repository.read_json(self.config_file))
        code_repository.ensure_mirror(item)

        self._git("-C", str(self.source), "checkout", "-b", "feature/new")
        self._commit_and_push("new branch", "new branch\n", "feature/new")
        result = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "feature/new",
        ])

        self.assertEqual("feature/new", result["branch"])
        self.assertEqual(
            "new branch\n",
            (Path(result["worktreePath"]) / "README.md").read_text(encoding="utf-8"),
        )

    def test_environment_deployment_branch_is_preferred_over_base_branch(self):
        self._git("-C", str(self.source), "checkout", "-b", "uat-deploy")
        self._commit_and_push("uat", "uat\n", "uat-deploy")
        self.config_file.write_text(json.dumps({
            "repositoryCode": "demo-repository",
            "name": "Demo Repository",
            "url": str(self.origin),
            "environmentCode": "uat",
            "deploymentBranch": "uat-deploy",
            "baseBranch": "main",
        }), encoding="utf-8")

        result = code_repository.prepare_worktree(["--config-file", str(self.config_file)])

        self.assertEqual("uat-deploy", result["branch"])
        self.assertEqual(
            "uat\n",
            (Path(result["worktreePath"]) / "README.md").read_text(encoding="utf-8"),
        )

    def test_environment_metadata_without_deployment_branch_uses_real_git_candidate(self):
        self.config_file.write_text(json.dumps({
            "repositoryCode": "demo-repository",
            "name": "Demo Repository",
            "url": str(self.origin),
            "environmentCode": "uat",
            "baseBranch": "main",
        }), encoding="utf-8")

        result = code_repository.prepare_worktree(["--config-file", str(self.config_file)])

        self.assertEqual("main", result["branch"])
        self.assertTrue(Path(result["worktreePath"]).is_dir())

    def test_default_repository_paths_separate_shared_and_task_scoped_data(self):
        application_root = self.root / "application"
        workspace_root = self.root / "workspace"
        with mock.patch.dict(os.environ, {
            "AGENT_RUNTIME_APPLICATION_ROOT": str(application_root),
            "AGENT_RUNTIME_WORKSPACE": str(workspace_root),
            "AGENT_RUNTIME_TASK_ID": "9",
        }, clear=True):
            self.assertEqual((application_root / "data/git/repositories").resolve(), code_repository.managed_root())
            self.assertEqual((application_root / "data/git/checkouts").resolve(), code_repository.shared_checkout_root())
            self.assertEqual((workspace_root / ".agent-worktrees" / "task-9").resolve(), code_repository.worktree_root())
            self.assertNotIn("GIT_SSH_COMMAND", code_repository.git_env())

    def test_git_key_directory_uses_standard_openssh_config(self):
        key_directory = self.root / "git-keys"
        key_directory.mkdir()
        (key_directory / "config").write_text("Host github.com\n", encoding="utf-8")

        code_repository.configure_git_key_directory(["--git-key-directory", str(key_directory)])

        ssh_command = code_repository.git_env().get("GIT_SSH_COMMAND", "")
        self.assertIn(f"ssh -F {(key_directory / 'config').resolve()}", ssh_command)
        self.assertIn("BatchMode=yes", ssh_command)

    def test_history_finds_introduction_and_line_blame_without_another_worktree(self):
        self._commit_and_push("add validation", "initial\nvalidate payee account\n", "main")
        prepared = code_repository.prepare_worktree([
            "--config-file", str(self.config_file), "--branch", "main",
        ])

        result = code_repository.repository_history([
            "--worktree-path", prepared["worktreePath"],
            "--path", "README.md",
            "--search", "validate payee account",
            "--line", "2",
        ])

        self.assertEqual(prepared["worktreePath"], result["worktreePath"])
        self.assertEqual("add validation", result["commits"][0]["subject"])
        self.assertEqual("add validation", result["blame"]["summary"])
        self.assertEqual("validate payee account", result["blame"]["content"])
        self.assertIn("+validate payee account", result["firstMatchPatch"])
        item = code_repository.repo_item_from_config(code_repository.read_json(self.config_file))
        shared_checkouts = list((self.checkouts / code_repository.repository_cache_key(item)).iterdir())
        self.assertEqual(1, len(shared_checkouts))

    def _commit_and_push(self, message, content, branch):
        (self.source / "README.md").write_text(content, encoding="utf-8")
        self._git("-C", str(self.source), "add", "README.md")
        self._git("-C", str(self.source), "commit", "-m", message)
        self._git("-C", str(self.source), "push", "origin", f"HEAD:{branch}")

    @staticmethod
    def _git(*args, capture_output=False):
        return subprocess.run(
            ["git", *args],
            check=True,
            text=True,
            stdout=subprocess.PIPE if capture_output else subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )


if __name__ == "__main__":
    unittest.main()
