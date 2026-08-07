#!/usr/bin/env python3
import json
import hashlib
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

CHANGE_MARKER_FILE = ".agent-change-branch.json"
CHANGE_BRANCH_PATTERN = re.compile(r"^lingxi-[a-z0-9-]+-\d{14}-[a-z0-9-]+$")
DEFAULT_FETCH_TTL_SECONDS = 300
GIT_KEY_DIRECTORY = None


def option(args, name):
    for index, item in enumerate(args):
        if item == name and index + 1 < len(args):
            return args[index + 1]
        if item.startswith(name + "="):
            return item[len(name) + 1:]
    return None


def has_flag(args, name):
    return name in args


def read_json(path):
    with open(path, "r", encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, dict):
        raise ValueError("config file must contain a JSON object")
    return value


def safe_name(value):
    text = str(value or "repository").strip()
    text = re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-._")
    return text[:120] or "repository"


def branch_slug(value, fallback="code-change"):
    text = str(value or "").lower().strip()
    text = re.sub(r"[^a-z0-9]+", "-", text).strip("-")
    return (text or fallback)[:48].strip("-") or fallback


def config_value(config, *keys):
    for key in keys:
        value = config.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def repository_url(config):
    return config_value(config, "url", "gitUrl", "repositoryUrl", "repository_url", "sshUrl", "httpUrl", "cloneUrl")


def repository_code(config):
    return config_value(config, "repositoryCode", "repoCode", "code", "name") or "repository"


def configured_branch(config):
    return config_value(config, "checkoutBranch", "deploymentBranch", "branch", "defaultBranch")


def append_branch_option(result, label, value, description=None):
    if not value:
        return
    result.append({
        "label": str(label or value),
        "value": str(value).strip(),
        "description": description,
    })


def dedupe_branch_options(options):
    seen = set()
    result = []
    for option in options:
        value = option.get("value")
        if not value or value in seen:
            continue
        seen.add(value)
        result.append(option)
    return result


def branch_option_value_set(options):
    return {item.get("value") for item in options if item.get("value")}


def prioritize_branch_options(options, *preferred):
    preferred_values = [value for value in preferred if value]
    if not preferred_values:
        return options
    priority = {}
    for index, value in enumerate(preferred_values):
        priority.setdefault(value, index)
    return sorted(options, key=lambda item: (priority.get(item.get("value"), len(priority)), item.get("value") or ""))


def repo_item_from_config(config):
    nested = config.get("repository") if isinstance(config.get("repository"), dict) else None
    if nested is None and isinstance(config.get("config"), dict):
        nested = config.get("config")
    data = {**config, **(nested or {})}
    url = repository_url(data)
    if not url:
        raise ValueError("repository config missing repositoryUrl/url")
    code = repository_code(data)
    return {
        "id": data.get("id"),
        "name": data.get("alias") or data.get("name") or code,
        "description": data.get("description"),
        "repositoryCode": code,
        "url": url,
        "configuredBranch": configured_branch(data),
        "baseBranch": config_value(data, "baseBranch"),
        "deploymentBranch": config_value(data, "deploymentBranch"),
        "environmentCode": config_value(data, "environmentCode"),
        "role": config_value(data, "role", "repositoryRole"),
        "config": data,
    }


def repository_cache_key(item):
    """Return a stable cache identity that separates equal codes backed by different Git URLs."""
    digest = hashlib.sha256(item["url"].encode("utf-8")).hexdigest()[:16]
    return f"{safe_name(item['repositoryCode'])}-{digest}"


def choose_repo(args):
    config_file = option(args, "--config-file")
    if not config_file:
        raise ValueError("missing required argument: --config-file")
    return repo_item_from_config(read_json(config_file))


def application_root():
    return Path(os.environ.get("AGENT_RUNTIME_APPLICATION_ROOT") or ".").expanduser().resolve()


def workspace_root():
    return Path(os.environ.get("AGENT_RUNTIME_WORKSPACE") or ".").expanduser().resolve()


def git_env():
    env = os.environ.copy()
    if GIT_KEY_DIRECTORY is not None:
        ssh_config = GIT_KEY_DIRECTORY / "config"
        command = ["ssh", "-F", str(ssh_config), "-o", "BatchMode=yes"]
        env["GIT_SSH_COMMAND"] = " ".join(shlex.quote(item) for item in command)
    return env


def configure_git_key_directory(args):
    """Validate the task parameter and configure Git to use its standard OpenSSH config."""
    global GIT_KEY_DIRECTORY
    value = option(args, "--git-key-directory")
    if not value:
        GIT_KEY_DIRECTORY = None
        return
    directory = Path(value).expanduser()
    if not directory.is_absolute():
        raise ValueError("--git-key-directory must be an absolute path on the Lingxi execution server")
    directory = directory.resolve()
    if not directory.is_dir():
        raise ValueError("Git Key directory does not exist or is not a directory")
    if not (directory / "config").is_file():
        raise ValueError("Git Key directory must contain an OpenSSH config file")
    GIT_KEY_DIRECTORY = directory


def run_git(args, cwd=None, check=True):
    process = subprocess.run(
        ["git", *args],
        cwd=cwd,
        env=git_env(),
        universal_newlines=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and process.returncode != 0:
        raise RuntimeError((process.stderr or process.stdout or "git command failed").strip())
    return process


@contextmanager
def repo_lock(name):
    import fcntl
    root = managed_root()
    root.mkdir(parents=True, exist_ok=True)
    lock_path = root / f"{safe_name(name)}.lock"
    with open(lock_path, "w", encoding="utf-8") as file:
        fcntl.flock(file.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(file.fileno(), fcntl.LOCK_UN)


def managed_root():
    return (application_root() / "data" / "git" / "repositories").resolve()


def shared_checkout_root():
    return (application_root() / "data" / "git" / "checkouts").resolve()


def worktree_root():
    task_id = os.environ.get("AGENT_RUNTIME_TASK_ID") or "unknown"
    return workspace_root() / ".agent-worktrees" / f"task-{task_id}"


def fetch_ttl_seconds():
    return DEFAULT_FETCH_TTL_SECONDS


def fetch_state_path(item):
    state_root = managed_root() / ".fetch-state"
    state_root.mkdir(parents=True, exist_ok=True)
    return state_root / f"{repository_cache_key(item)}.json"


def read_fetch_state(item):
    path = fetch_state_path(item)
    if not path.exists():
        return {}
    try:
        return read_json(path)
    except (OSError, ValueError, json.JSONDecodeError):
        return {}


def write_fetch_state(item, url):
    path = fetch_state_path(item)
    payload = {
        "repositoryCode": item["repositoryCode"],
        "url": url,
        "lastFetchEpoch": int(time.time()),
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def should_fetch(item, url, force_fetch):
    if force_fetch:
        return True
    state = read_fetch_state(item)
    if state.get("url") != url:
        return True
    last_fetch = state.get("lastFetchEpoch")
    if not isinstance(last_fetch, (int, float)):
        return True
    return time.time() - last_fetch >= fetch_ttl_seconds()


def branch_revision(mirror, branch):
    for candidate in (branch, f"origin/{branch}", f"refs/heads/{branch}", f"refs/remotes/origin/{branch}"):
        result = run_git([
            "--git-dir", str(mirror), "rev-parse", "--verify", f"{candidate}^{{commit}}",
        ], check=False)
        if result.returncode == 0:
            return result.stdout.strip()
    return ""


def remote_branch_revision(item, branch):
    result = run_git(["ls-remote", "--heads", item["url"], f"refs/heads/{branch}"])
    for line in result.stdout.splitlines():
        values = line.split()
        if len(values) >= 2 and values[1] == f"refs/heads/{branch}":
            return values[0]
    return ""


def fetch_mirror(item, mirror, branch=None):
    if branch:
        remote_revision = remote_branch_revision(item, branch)
        if remote_revision and remote_revision == branch_revision(mirror, branch):
            return
        if remote_revision:
            run_git([
                "--git-dir", str(mirror), "fetch", "origin",
                f"+refs/heads/{branch}:refs/heads/{branch}",
            ])
            return
    run_git(["--git-dir", str(mirror), "fetch", "--prune", "origin"])


def ensure_mirror(item, force_fetch=False, branch=None):
    root = managed_root()
    root.mkdir(parents=True, exist_ok=True)
    cache_key = repository_cache_key(item)
    mirror = root / f"{cache_key}.git"
    url = item["url"]
    with repo_lock(cache_key):
        if mirror.exists():
            run_git(["--git-dir", str(mirror), "remote", "set-url", "origin", url])
            if should_fetch(item, url, force_fetch):
                fetch_mirror(item, mirror, branch)
                write_fetch_state(item, url)
        else:
            run_git(["clone", "--mirror", url, str(mirror)])
            write_fetch_state(item, url)
    return mirror


def branch_candidates(mirror):
    result = run_git([
        "--git-dir", str(mirror),
        "for-each-ref",
        "--format=%(refname:short)",
        "refs/heads",
        "refs/remotes/origin",
    ])
    branches = []
    for line in result.stdout.splitlines():
        value = line.strip()
        if value and value != "origin/HEAD":
            branches.append(value)
    return sorted(set(branches))


def git_branch_options(item):
    try:
        mirror = ensure_mirror(item)
        options = []
        for branch in branch_candidates(mirror):
            value = branch.removeprefix("origin/")
            append_branch_option(options, value, value, "Git 远端分支")
        options = dedupe_branch_options(options)
        return prioritize_branch_options(
            options,
            item.get("deploymentBranch"),
            item.get("configuredBranch"),
            item.get("baseBranch"),
        )
    except Exception as exc:
        return {
            "status": "BRANCH_LOAD_FAILED",
            "message": str(exc),
            "branchOptions": [],
        }


def resolve_ref(mirror, branch):
    candidates = [branch, f"origin/{branch}", f"refs/heads/{branch}", f"refs/remotes/origin/{branch}"]
    for candidate in candidates:
        result = run_git(["--git-dir", str(mirror), "rev-parse", "--verify", candidate], check=False)
        if result.returncode == 0:
            return candidate
    available = ", ".join(branch_candidates(mirror)[:30])
    raise ValueError(f"branch not found: {branch}. available: {available}")


def resolve_ref_with_refresh(item, mirror, branch):
    try:
        return mirror, resolve_ref(mirror, branch)
    except ValueError:
        refreshed_mirror = ensure_mirror(item, force_fetch=True, branch=branch)
        return refreshed_mirror, resolve_ref(refreshed_mirror, branch)


def remove_worktree_path(path):
    root = worktree_root().resolve()
    target = path.resolve()
    if target.exists() and root in target.parents:
        shutil.rmtree(target)


def require_worktree_path(path_value):
    if not path_value:
        raise ValueError("missing required argument: --worktree-path")
    target = Path(path_value).expanduser().resolve()
    allowed_roots = (worktree_root().resolve(), shared_checkout_root().resolve())
    if not any(root == target or root in target.parents for root in allowed_roots):
        raise ValueError("worktree path is outside managed repository directories")
    if not (target / ".git").exists():
        raise ValueError("worktree path is not a git worktree")
    return target


def require_change_worktree(path_value):
    target = require_worktree_path(path_value)
    marker_path = target / CHANGE_MARKER_FILE
    if not marker_path.exists():
        raise ValueError("only worktrees created by code-repo prepare-change can be committed")
    if target.parent.name != "changes":
        raise ValueError("change commit is limited to prepare-change worktrees")
    marker = read_json(marker_path)
    branch = run_git(["-C", str(target), "rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip()
    expected_branch = str(marker.get("branch") or "").strip()
    if not expected_branch or branch != expected_branch:
        raise ValueError("current branch does not match the prepared temporary branch")
    if not CHANGE_BRANCH_PATTERN.match(branch):
        raise ValueError("temporary branch name must match lingxi-{user}-{yyyyMMddHHmmss}-{description}")
    return target, marker, branch


def prepare_worktree(args):
    item = choose_repo(args)
    explicit_branch = option(args, "--branch")
    if not explicit_branch and not item.get("configuredBranch"):
        branches = git_branch_options(item)
        if isinstance(branches, dict):
            return {
                **branches,
                "repositoryCode": item["repositoryCode"],
                "repositoryName": item.get("name"),
                "nextAction": "Git 分支加载失败，先处理仓库访问问题；不要使用配置别名代替真实 Git 分支。",
            }
        if not branches:
            return {
                "status": "BRANCH_LOAD_FAILED",
                "message": "未读取到 Git 远端分支候选。",
                "repositoryCode": item["repositoryCode"],
                "repositoryName": item.get("name"),
                "branchOptions": [],
                "nextAction": "先检查仓库地址、权限或同步状态；不要使用配置别名代替真实 Git 分支。",
            }
        if len(branches) == 1:
            explicit_branch = branches[0]["value"]
        else:
            branch_count = len(branches)
            return {
                "status": "NEED_BRANCH",
                "message": "当前仓库需要先确认本次分析分支。",
                "repositoryCode": item["repositoryCode"],
                "repositoryName": item.get("name"),
                "branchOptions": branches[:50],
                "branchOptionCount": branch_count,
                "branchOptionsTruncated": branch_count > 50,
                "nextAction": "结合任务目标选择真实 Git 分支；候选未显示完整时先用 code-repo list --branch-query 名称片段过滤，确定后带 --branch 重试。",
            }
    branch = explicit_branch or item.get("configuredBranch")
    mirror = ensure_mirror(item, branch=branch)
    mirror, ref = resolve_ref_with_refresh(item, mirror, branch)
    revision = run_git(["--git-dir", str(mirror), "rev-parse", f"{ref}^{{commit}}"]).stdout.strip()
    cache_key = repository_cache_key(item)
    target = shared_checkout_root() / cache_key / revision
    target.parent.mkdir(parents=True, exist_ok=True)
    cache_hit = False
    with repo_lock(cache_key):
        run_git(["--git-dir", str(mirror), "worktree", "prune"], check=False)
        if target.exists():
            current = run_git(["-C", str(target), "rev-parse", "HEAD"], check=False)
            cache_hit = current.returncode == 0 and current.stdout.strip() == revision and (target / ".git").exists()
            if not cache_hit:
                shutil.rmtree(target)
        if not cache_hit:
            run_git(["--git-dir", str(mirror), "worktree", "add", "--force", "--detach", str(target), revision])
    return {
        "repositoryCode": item["repositoryCode"],
        "branch": branch,
        "ref": ref,
        "revision": revision,
        "worktreePath": str(target),
        "cacheHit": cache_hit,
        "isolated": False,
        "sharedCheckout": True,
    }


def prepare_change_worktree(args):
    item = choose_repo(args)
    base = option(args, "--base")
    if not base:
        raise ValueError("missing required argument: --base")
    branch_name = option(args, "--branch-name")
    if not branch_name:
        owner = branch_slug(os.environ.get("AGENT_RUNTIME_OWNER_USERNAME"), "unknown")
        timestamp = time.strftime("%Y%m%d%H%M%S")
        description = branch_slug(option(args, "--description"), "code-change")
        branch_name = f"lingxi-{owner}-{timestamp}-{description}"
    mirror = ensure_mirror(item)
    mirror, base_ref = resolve_ref_with_refresh(item, mirror, base)
    cache_key = repository_cache_key(item)
    target = worktree_root() / cache_key / "changes" / safe_name(branch_name)
    target.parent.mkdir(parents=True, exist_ok=True)
    with repo_lock(cache_key):
        remove_worktree_path(target)
        run_git(["--git-dir", str(mirror), "worktree", "prune"], check=False)
        run_git(["--git-dir", str(mirror), "worktree", "add", "--force", "--detach", str(target), base_ref])
        run_git(["-C", str(target), "checkout", "-B", branch_name])
    marker = {
        "taskId": os.environ.get("AGENT_RUNTIME_TASK_ID") or "",
        "repositoryCode": item["repositoryCode"],
        "base": base,
        "baseRef": base_ref,
        "branch": branch_name,
        "createdBy": "code-repo.prepare-change",
    }
    (target / CHANGE_MARKER_FILE).write_text(json.dumps(marker, ensure_ascii=False, indent=2), encoding="utf-8")
    return {
        "repositoryCode": item["repositoryCode"],
        "base": base,
        "baseRef": base_ref,
        "branch": branch_name,
        "worktreePath": str(target),
        "isolated": True,
        "sharedCheckout": False,
        "nextAction": "在 worktreePath 内完成小范围代码修改，验证后使用 code-repo commit 提交。",
    }


def commit_worktree(args):
    worktree, marker, branch = require_change_worktree(option(args, "--worktree-path"))
    message = option(args, "--message")
    message_file = option(args, "--message-file")
    push = has_flag(args, "--push")
    if not message and message_file:
        message = Path(message_file).read_text(encoding="utf-8")
    if not message or not message.strip():
        raise ValueError("missing required argument: --message or --message-file")
    status = run_git(["-C", str(worktree), "status", "--porcelain"])
    changed_lines = [line for line in status.stdout.splitlines() if line.strip() and not line.endswith(CHANGE_MARKER_FILE)]
    if not changed_lines:
        return {
            "status": "NO_CHANGES",
            "message": "没有检测到可提交的代码变更。",
            "worktreePath": str(worktree),
        }
    run_git(["-C", str(worktree), "add", "-A"])
    run_git(["-C", str(worktree), "reset", "--", CHANGE_MARKER_FILE], check=False)
    run_git(["-C", str(worktree), "commit", "-m", message.strip()])
    commit_hash = run_git(["-C", str(worktree), "rev-parse", "HEAD"]).stdout.strip()
    stat = run_git(["-C", str(worktree), "show", "--stat", "--oneline", "--format=", commit_hash]).stdout.strip()
    names = run_git(["-C", str(worktree), "show", "--name-status", "--format=", commit_hash]).stdout.strip()
    result = {
        "status": "COMMITTED",
        "branch": branch,
        "base": marker.get("base"),
        "commitHash": commit_hash,
        "worktreePath": str(worktree),
        "diffStat": stat,
        "changedFiles": [line.split("\t") for line in names.splitlines() if line.strip()],
    }
    if push:
        run_git(["-C", str(worktree), "push", "-u", "origin", branch])
        result["pushed"] = True
        result["remote"] = "origin"
    else:
        result["pushed"] = False
    return result


def diff_summary(args):
    base = option(args, "--base")
    target_branch = option(args, "--target")
    if not base or not target_branch:
        raise ValueError("missing required arguments: --base and --target")
    prepared = prepare_worktree([*args, "--branch", target_branch])
    item = choose_repo(args)
    mirror = ensure_mirror(item)
    mirror, base_ref = resolve_ref_with_refresh(item, mirror, base)
    target_path = prepared["worktreePath"]
    stat = run_git(["-C", target_path, "diff", "--stat", f"{base_ref}...HEAD"])
    names = run_git(["-C", target_path, "diff", "--name-status", f"{base_ref}...HEAD"])
    prepared["base"] = base
    prepared["target"] = target_branch
    prepared["baseRef"] = base_ref
    diff_stat = stat.stdout.strip()
    changed_files = [
        line.split("\t", 1) for line in names.stdout.splitlines() if line.strip()
    ]
    prepared["diffStat"] = diff_stat[:12_000]
    prepared["diffStatTruncated"] = len(diff_stat) > 12_000
    prepared["changedFileCount"] = len(changed_files)
    prepared["changedFiles"] = changed_files[:100]
    prepared["changedFilesTruncated"] = len(changed_files) > 100
    return prepared


def repository_history(args):
    """Return bounded Git history or blame evidence from an existing managed checkout."""
    worktree = require_worktree_path(option(args, "--worktree-path"))
    path_value = option(args, "--path")
    if not path_value:
        raise ValueError("missing required argument: --path")
    target = (worktree / path_value).resolve()
    if worktree not in target.parents or not target.is_file():
        raise ValueError("history path must be an existing file inside the worktree")
    relative_path = target.relative_to(worktree).as_posix()

    limit_text = option(args, "--limit") or "20"
    try:
        limit = int(limit_text)
    except ValueError as exc:
        raise ValueError("--limit must be an integer") from exc
    if limit < 1 or limit > 50:
        raise ValueError("--limit must be between 1 and 50")

    search = option(args, "--search")
    log_args = [
        "-C", str(worktree), "log", "--follow",
        "--format=%H%x1f%h%x1f%an%x1f%aI%x1f%s%x1e",
    ]
    if has_flag(args, "--all-refs"):
        log_args.append("--all")
    if search:
        log_args.extend(["-S", search])
    else:
        log_args.extend(["--max-count", str(limit)])
    log_args.extend(["--", relative_path])
    raw_history = run_git(log_args).stdout
    commits = []
    for record in raw_history.split("\x1e"):
        fields = record.strip().split("\x1f")
        if len(fields) != 5:
            continue
        commits.append({
            "hash": fields[0],
            "shortHash": fields[1],
            "author": fields[2],
            "authoredAt": fields[3],
            "subject": fields[4],
        })
    if search:
        # `git log` is newest-first; introduction questions need the oldest matching change first.
        commits = list(reversed(commits))[:limit]

    result = {
        "worktreePath": str(worktree),
        "path": relative_path,
        "revision": run_git(["-C", str(worktree), "rev-parse", "HEAD"]).stdout.strip(),
        "branch": run_git(["-C", str(worktree), "rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip(),
        "search": search,
        "allRefs": has_flag(args, "--all-refs"),
        "commits": commits,
    }

    line_text = option(args, "--line")
    if line_text:
        try:
            line = int(line_text)
        except ValueError as exc:
            raise ValueError("--line must be an integer") from exc
        if line < 1:
            raise ValueError("--line must be greater than zero")
        blame = run_git([
            "-C", str(worktree), "blame", "--line-porcelain", "-L", f"{line},{line}",
            "--", relative_path,
        ]).stdout.splitlines()
        header = blame[0].split() if blame else []
        values = {}
        for item in blame[1:]:
            if item.startswith("\t") or " " not in item:
                continue
            key, value = item.split(" ", 1)
            values[key] = value
        result["blame"] = {
            "line": line,
            "commit": header[0] if header else "",
            "author": values.get("author"),
            "authoredAtEpoch": values.get("author-time"),
            "summary": values.get("summary"),
            "sourcePath": values.get("filename"),
            "content": next((item[1:] for item in blame if item.startswith("\t")), ""),
        }

    if search and commits:
        first_hash = commits[0]["hash"]
        patch = run_git([
            "-C", str(worktree), "show", "--format=", "--find-renames", "--unified=3",
            first_hash, "--", relative_path,
        ]).stdout
        max_patch_chars = 12_000
        result["firstMatchPatch"] = patch[:max_patch_chars]
        result["firstMatchPatchTruncated"] = len(patch) > max_patch_chars
    return result


def call_platform(command, args):
    try:
        configure_git_key_directory(args)
        if command == "code-repo.list":
            item = choose_repo(args)
            payload = {key: value for key, value in item.items() if key != "config" and key != "url"}
            branches = git_branch_options(item)
            if isinstance(branches, dict):
                payload["branchLoadStatus"] = branches.get("status")
                payload["branchLoadMessage"] = branches.get("message")
                payload["gitBranchOptions"] = []
            else:
                values = branch_option_value_set(branches)
                if item.get("baseBranch") in values:
                    payload["recommendedBaseBranch"] = item.get("baseBranch")
                if item.get("configuredBranch") in values:
                    payload["recommendedBranch"] = item.get("configuredBranch")
                branch_query = (option(args, "--branch-query") or "").strip().casefold()
                filtered = branches if not branch_query else [
                    branch for branch in branches
                    if branch_query in str(branch.get("value") or "").casefold()
                ]
                payload["gitBranchOptionCount"] = len(filtered)
                payload["gitBranchOptions"] = filtered[:50]
                payload["gitBranchOptionsTruncated"] = len(filtered) > 50
            sys.stdout.write(json.dumps({
                "repositories": [payload]
            }, ensure_ascii=False))
            return 0
        if command == "code-repo.prepare":
            sys.stdout.write(json.dumps(prepare_worktree(args), ensure_ascii=False))
            return 0
        if command == "code-repo.diff":
            sys.stdout.write(json.dumps(diff_summary(args), ensure_ascii=False))
            return 0
        if command == "code-repo.history":
            sys.stdout.write(json.dumps(repository_history(args), ensure_ascii=False))
            return 0
        if command == "code-repo.prepare-change":
            sys.stdout.write(json.dumps(prepare_change_worktree(args), ensure_ascii=False))
            return 0
        if command == "code-repo.commit":
            sys.stdout.write(json.dumps(commit_worktree(args), ensure_ascii=False))
            return 0
        print(f"unsupported code-repository command: {command}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(call_platform(sys.argv[1], sys.argv[2:]))
