#!/usr/bin/env python3
"""Checks the things Actions refuses to parse but a YAML parser happily accepts.

Why this exists: two pushes to this repo produced a run with *no jobs at all*, because GitHub rejects the
whole file for a duplicated mapping key (`if:` written twice on one job) and for an action input declaring
both `required: true` and a `default`. PyYAML loads both files without complaint, so the only way to know
before pushing was to burn a run and then go hunting for an annotation the API does not expose. Every rule
here is one of those "the runner is right and nothing local told me" cases, found the hard way.
"""
from __future__ import annotations

import pathlib
import re
import sys

try:  # PyYAML is not on a runner's python by default; the indentation checks are the ones that matter most.
    import yaml
    HAVE_YAML = True
except ModuleNotFoundError:
    yaml = None
    HAVE_YAML = False

ROOT = pathlib.Path(__file__).resolve().parents[2]


def dup_keys(path: pathlib.Path) -> list[str]:
    """A key written twice in the same mapping block: fatal to Actions, invisible to yaml.safe_load.

    Line-oriented on purpose. PyYAML keeps the last value of a duplicated key, so loading the document hides
    the exact mistake being looked for - and that is what cost two pushes here (a job carrying `if:` twice,
    rejected as an invalid workflow file with no job ever starting and no annotation the API will show).

    The rules, in the order they matter: a `- key:` line opens a *fresh* block for that list item at the
    column of the key (dash plus two), so sibling steps never share keys with each other; a `key:` at the
    same column as the open block is a sibling of it, and a repeat of a sibling is the bug; a deeper `key:`
    opens a nested block owned by that key.
    """
    out = []
    stack: list[list] = []  # [indent, owner, {key: first line}]
    for n, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        item = re.match(r"^(\s*)-\s+([\w.-]+):(?:\s|$)", raw)
        if item:
            col = len(item.group(1)) + 2
            while stack and stack[-1][0] >= col:
                stack.pop()
            stack.append([col, item.group(2), {item.group(2): n}])
            continue
        m = re.match(r"^(\s*)([\w.-]+):(?:\s|$)", raw)
        if not m:
            continue
        indent, key = len(m.group(1)), m.group(2)
        while stack and indent < stack[-1][0]:
            stack.pop()
        if not stack:
            stack.append([indent, key, {key: n}])
            continue
        if indent == stack[-1][0]:
            seen = stack[-1][2]
            if key in seen:
                out.append(f"{path.relative_to(ROOT)}:{n}: '{key}' is set twice in the block opened by "
                           f"'{stack[-1][1]}' (first at line {seen[key]})")
            seen[key] = n
        else:
            stack[-1][2][key] = n
            stack.append([indent, key, {}])
    return out


def check_action(path: pathlib.Path) -> list[str]:
    if not HAVE_YAML:
        return []
    out = []
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    inputs = doc.get("inputs") or {}
    for name, spec in inputs.items():
        if not isinstance(spec, dict):
            out.append(f"{path}: input '{name}' is not a mapping")
            continue
        if spec.get("required") and "default" in spec:
            out.append(f"{path}: input '{name}' has both required: true and a default - Actions rejects the file")
    text = path.read_text(encoding="utf-8")
    declared = set(inputs)
    for m in re.finditer(r"inputs\.([A-Za-z0-9_-]+)", text):
        if m.group(1) not in declared:
            out.append(f"{path}: ${{{{ inputs.{m.group(1)} }}}} is not declared under inputs:")
    runs = doc.get("runs") or {}
    if runs.get("using") == "composite":
        for i, step in enumerate(runs.get("steps") or []):
            if "uses" in step and "run" in step:
                out.append(f"{path}: step {i} has both 'uses' and 'run'")
            if "run" in step and "shell" not in step:
                out.append(f"{path}: step {i} ('{step.get('name', step.get('id', i))}') runs a script with no shell:")
            for k in ("services", "container", "timeout-minutes"):
                if k in step:
                    out.append(f"{path}: step {i} uses '{k}', which composite actions do not allow")
            cond = step.get("if") or ""
            for fn in ("always()", "failure(", "success(", "cancelled("):
                if fn in cond:
                    out.append(f"{path}: step {i} ('{step.get('name', i)}') uses {fn} in if: - a composite "
                              f"action's step conditions have no such functions, and Actions refuses to run "
                              f"the action (use steps.<id>.outcome plus continue-on-error, and decide the "
                              f"verdict in a final step)")
    return out


def check_workflows(workflows: list[pathlib.Path], actions: dict[str, pathlib.Path]) -> list[str]:
    if not HAVE_YAML:
        return []
    out = []
    for path in workflows:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        for job, jd in (doc.get("jobs") or {}).items():
            if jd.get("needs"):
                needs = jd["needs"]
                needs = [needs] if isinstance(needs, str) else needs
                for dep in needs:
                    if dep not in (doc.get("jobs") or {}):
                        out.append(f"{path}: job '{job}' needs unknown job '{dep}'")
            for i, step in enumerate(jd.get("steps") or []):
                uses = step.get("uses", "")
                if uses.startswith("./"):
                    rel = uses[2:].lstrip("/")
                    apath = actions.get(rel) or (ROOT / rel / "action.yml")
                    if not pathlib.Path(apath).is_file():
                        out.append(f"{path}: job '{job}' step {i} uses '{uses}' - no action.yml there")
                        continue
                    spec = yaml.safe_load(pathlib.Path(apath).read_text(encoding="utf-8"))
                    declared = set(spec.get("inputs") or {})
                    for key in (step.get("with") or {}):
                        if key not in declared:
                            out.append(f"{path}: job '{job}' passes 'with.{key}' to {uses}, which does not declare it")
                    required = {k for k, v in (spec.get("inputs") or {}).items() if (v or {}).get("required")}
                    for miss in sorted(required - set(step.get("with") or {})):
                        out.append(f"{path}: job '{job}' does not pass required input '{miss}' to {uses}")
    return out


def main() -> int:
    problems: list[str] = []
    workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
    actions = {p.parent.relative_to(ROOT).as_posix(): p
               for p in (ROOT / ".github" / "actions").rglob("action.yml")}
    for path in workflows + list(actions.values()):
        problems += dup_keys(path)
    for path in actions.values():
        problems += check_action(path)
    problems += check_workflows(workflows, actions)
    for p in problems:
        print(f"FAIL  {p.replace(str(ROOT) + '/', '')}")
    extra = "" if HAVE_YAML else "  (PyYAML is absent, so only the indentation checks ran: pip install pyyaml for the rest)"
    print(f"\n{len(problems)} problem(s) in {len(workflows)} workflow file(s), {len(actions)} action(s).{extra}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
