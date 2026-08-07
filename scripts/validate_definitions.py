#!/usr/bin/env python3
"""Validate scenario manifests, standard Agent Skills, and Lingxi capability extensions."""

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ROOT / "examples" / "resource-demo" / "scenarios"
SKILLS = ROOT / "backend" / "src" / "main" / "resources" / "skills"
DEMO_SKILLS = ROOT / "examples" / "resource-demo" / "skills"
CODE_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
FRONTMATTER_PATTERN = re.compile(r"\A---\r?\n(.*?)\r?\n---(?:\r?\n|\Z)", re.DOTALL)
FRONTMATTER_FIELD_PATTERN = re.compile(r"^([A-Za-z0-9-]+):\s*(.*)$")
SKILL_FRONTMATTER_FIELDS = {"name", "description", "license", "allowed-tools", "metadata"}
COLOR_PATTERN = re.compile(r"^#[0-9a-fA-F]{6}$")
SCENARIO_FORBIDDEN_FIELDS = {"recommendedScenarioCodes", "autoFollowUpScenarioCode"}
LINGXI_FIELDS = {
    "$schema", "schemaVersion", "version", "presentation", "enabledByDefault",
    "entrypoint", "taskParameters", "configurationParameters", "guides", "commands",
}
COMMAND_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)* [a-z0-9]+(?:-[a-z0-9]+)*$")


def load_json(path, errors):
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(value, dict):
            errors.append(f"{path.relative_to(ROOT)}: root must be an object")
            return None
        return value
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"{path.relative_to(ROOT)}: invalid JSON: {exc}")
        return None


def require_file(module_dir, value, field, errors):
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{module_dir.relative_to(ROOT)}: missing {field}")
        return
    candidate = (module_dir / value).resolve()
    if module_dir.resolve() not in candidate.parents or not candidate.is_file():
        errors.append(f"{module_dir.relative_to(ROOT)}: {field} does not resolve to a module file: {value}")


def load_scenarios(errors):
    scenarios = {}
    root = SCENARIOS
    if not root.is_dir():
        return scenarios
    for module_dir in sorted(path for path in root.iterdir() if path.is_dir()):
        manifest_path = module_dir / "manifest.json"
        manifest = load_json(manifest_path, errors) if manifest_path.is_file() else None
        if manifest is None:
            if not manifest_path.is_file():
                errors.append(f"{module_dir.relative_to(ROOT)}: missing manifest.json")
            continue
        code = manifest.get("code")
        if not isinstance(code, str) or not CODE_PATTERN.fullmatch(code):
            errors.append(f"{manifest_path.relative_to(ROOT)}: invalid code: {code!r}")
            continue
        if code != module_dir.name:
            errors.append(f"{manifest_path.relative_to(ROOT)}: code must match directory name")
        scenarios[code] = manifest
        for field in ("name", "description"):
            if not isinstance(manifest.get(field), str) or not manifest[field].strip():
                errors.append(f"{manifest_path.relative_to(ROOT)}: missing {field}")
        require_file(module_dir, manifest.get("promptFile"), "promptFile", errors)
        if manifest.get("icon"):
            require_file(module_dir, manifest.get("icon"), "icon", errors)
        color = manifest.get("color")
        if color is not None and (not isinstance(color, str) or not COLOR_PATTERN.fullmatch(color)):
            errors.append(f"{manifest_path.relative_to(ROOT)}: color must use #RRGGBB")
        for field in sorted(SCENARIO_FORBIDDEN_FIELDS.intersection(manifest)):
            errors.append(f"{manifest_path.relative_to(ROOT)}: field is not allowed in scenarios: {field}")
    return scenarios


def parse_frontmatter(skill_path, errors):
    try:
        content = skill_path.read_text(encoding="utf-8-sig")
    except OSError as exc:
        errors.append(f"{skill_path.relative_to(ROOT)}: cannot read SKILL.md: {exc}")
        return None
    match = FRONTMATTER_PATTERN.match(content)
    if not match:
        errors.append(f"{skill_path.relative_to(ROOT)}: invalid YAML frontmatter")
        return None
    fields = {}
    for line in match.group(1).splitlines():
        field_match = FRONTMATTER_FIELD_PATTERN.match(line)
        if not field_match:
            errors.append(f"{skill_path.relative_to(ROOT)}: unsupported frontmatter line: {line}")
            continue
        key, raw_value = field_match.groups()
        try:
            value = json.loads(raw_value) if raw_value.startswith(('"', "'")) else raw_value.strip()
        except json.JSONDecodeError as exc:
            errors.append(f"{skill_path.relative_to(ROOT)}: invalid {key}: {exc}")
            value = None
        fields[key] = value
    unexpected = sorted(set(fields) - SKILL_FRONTMATTER_FIELDS)
    if unexpected:
        errors.append(f"{skill_path.relative_to(ROOT)}: non-standard frontmatter fields: {', '.join(unexpected)}")
    if not content[match.end():].strip():
        errors.append(f"{skill_path.relative_to(ROOT)}: instructions must not be blank")
    return fields


def validate_parameters(extension_path, group, parameters, errors):
    if not isinstance(parameters, list):
        errors.append(f"{extension_path.relative_to(ROOT)}: {group} must be an array")
        return
    keys = set()
    for parameter in parameters:
        key = parameter.get("key") if isinstance(parameter, dict) else None
        if not isinstance(key, str) or not key.strip():
            errors.append(f"{extension_path.relative_to(ROOT)}: {group} contains an item without key")
        elif key in keys:
            errors.append(f"{extension_path.relative_to(ROOT)}: duplicate {group} key: {key}")
        keys.add(key)


def validate_commands(extension_path, commands, errors):
    if commands is None:
        return
    if not isinstance(commands, list):
        errors.append(f"{extension_path.relative_to(ROOT)}: commands must be an array")
        return
    selectors = set()
    for definition in commands:
        if not isinstance(definition, dict):
            errors.append(f"{extension_path.relative_to(ROOT)}: commands must contain objects")
            continue
        command = definition.get("command")
        if not isinstance(command, str) or not COMMAND_PATTERN.fullmatch(command.strip()):
            errors.append(f"{extension_path.relative_to(ROOT)}: commands.command must use group action format")
            continue
        display_name = definition.get("displayName")
        if not isinstance(display_name, str) or not display_name.strip():
            errors.append(f"{extension_path.relative_to(ROOT)}: commands.displayName must not be blank")
        if command in selectors:
            errors.append(f"{extension_path.relative_to(ROOT)}: duplicate commands.command: {command}")
        selectors.add(command)
        for output in definition.get("outputs", []):
            if not isinstance(output, dict):
                errors.append(f"{extension_path.relative_to(ROOT)}: command outputs must contain objects")
                continue
            for field in ("type", "pathField"):
                if not isinstance(output.get(field), str) or not output[field].strip():
                    errors.append(f"{extension_path.relative_to(ROOT)}: command output is missing {field}")


def load_capabilities(errors):
    capabilities = {}
    module_dirs = [
        path
        for root in (SKILLS, DEMO_SKILLS)
        for path in root.iterdir()
        if path.is_dir() and (path / "SKILL.md").is_file()
    ]
    for module_dir in sorted(module_dirs):
        skill_path = module_dir / "SKILL.md"
        extension_path = module_dir / "lingxi.json"
        if not skill_path.is_file():
            errors.append(f"{module_dir.relative_to(ROOT)}: missing SKILL.md")
            continue
        frontmatter = parse_frontmatter(skill_path, errors)
        if frontmatter is None:
            continue
        code = frontmatter.get("name")
        description = frontmatter.get("description")
        if not isinstance(code, str) or not CODE_PATTERN.fullmatch(code) or len(code) > 64:
            errors.append(f"{skill_path.relative_to(ROOT)}: invalid Skill name: {code!r}")
            continue
        if code != module_dir.name:
            errors.append(f"{skill_path.relative_to(ROOT)}: Skill name must match directory name")
        if code in capabilities:
            errors.append(f"{skill_path.relative_to(ROOT)}: duplicate Skill name: {code}")
        if not isinstance(description, str) or not description.strip() or len(description) > 1024:
            errors.append(f"{skill_path.relative_to(ROOT)}: invalid Skill description")
        capabilities[code] = True
        if not extension_path.is_file():
            errors.append(f"{module_dir.relative_to(ROOT)}: missing lingxi.json")
            continue
        extension = load_json(extension_path, errors)
        if extension is None:
            continue
        unexpected = sorted(set(extension) - LINGXI_FIELDS)
        if unexpected:
            errors.append(f"{extension_path.relative_to(ROOT)}: unknown fields: {', '.join(unexpected)}")
        if extension.get("schemaVersion") != 1:
            errors.append(f"{extension_path.relative_to(ROOT)}: schemaVersion must be 1")
        if not isinstance(extension.get("version"), str) or not extension["version"].strip():
            errors.append(f"{extension_path.relative_to(ROOT)}: version must not be blank")
        presentation = extension.get("presentation")
        if not isinstance(presentation, dict) or not isinstance(presentation.get("displayName"), str):
            errors.append(f"{extension_path.relative_to(ROOT)}: presentation.displayName must not be blank")
        elif presentation.get("icon"):
            require_file(module_dir, presentation["icon"], "presentation.icon", errors)
        entrypoint = extension.get("entrypoint")
        has_runtime_contract = bool(extension.get("commands"))
        if has_runtime_contract or entrypoint is not None:
            require_file(module_dir, entrypoint, "entrypoint", errors)
        if isinstance(entrypoint, str) and not entrypoint.replace("\\", "/").endswith(".py"):
            errors.append(f"{extension_path.relative_to(ROOT)}: entrypoint must be a Python .py file")
        validate_parameters(extension_path, "taskParameters", extension.get("taskParameters"), errors)
        validate_parameters(extension_path, "configurationParameters", extension.get("configurationParameters"), errors)
        for guide in extension.get("guides", []):
            if isinstance(guide, dict):
                require_file(module_dir, guide.get("file"), "guides.file", errors)
        validate_commands(extension_path, extension.get("commands"), errors)
    return capabilities


def main():
    errors = []
    scenarios = load_scenarios(errors)
    capabilities = load_capabilities(errors)
    for code, manifest in scenarios.items():
        path = SCENARIOS / code / "manifest.json"
        capability_codes = manifest.get("capabilities", [])
        if not isinstance(capability_codes, list):
            errors.append(f"{path.relative_to(ROOT)}: capabilities must be an array")
            continue
        valid_codes = [value for value in capability_codes if isinstance(value, str)]
        if len(valid_codes) != len(set(valid_codes)):
            errors.append(f"{path.relative_to(ROOT)}: capabilities must not contain duplicates")
        for capability_code in capability_codes:
            if not isinstance(capability_code, str) or not CODE_PATTERN.fullmatch(capability_code):
                errors.append(f"{path.relative_to(ROOT)}: invalid capability code: {capability_code!r}")
            elif capability_code not in capabilities:
                errors.append(f"{path.relative_to(ROOT)}: capability does not exist: {capability_code}")
    if errors:
        print("Definition validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Validated {len(scenarios)} scenarios and {len(capabilities)} Agent Skills with Lingxi extensions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
