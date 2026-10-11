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


def disp_path(path: pathlib.Path) -> str:
    """Repo-relative when it is in the repo, absolute otherwise. The checker is also pointed at scratch files
    while it is being developed, and a gate that crashes over a path display teaches nothing."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def dup_keys(path: pathlib.Path) -> list[str]:
    """A key written twice in the same mapping block: fatal to Actions, invisible to yaml.safe_load.

    PyYAML keeps the last value of a duplicated key, so loading the document hides exactly the mistake being
    looked for - which is what cost four pushes here (a job with `if:` twice, an input with `default:` twice).
    The whole file is rejected, no job starts, the run is named by its path and the API shows no annotation.

    So this is structural, line-oriented, and reads the document the way the runner's parser does:

      - a frame is a mapping whose *children's* column is discovered on the first child (workflow files are
        written with a consistent indent, and the frame model must not care what that width is);
      - `key:` with nothing after it opens a frame for the block that follows;
      - a `- ` line closes the previous list item's frame and opens a fresh one, so two steps in a list
        never share keys with each other;
      - inside a block scalar (`run: |`, `description: >`) nothing is YAML, including lines that look like
        keys - so they are skipped by column, which is what makes `echo "x: y"` in a harness script safe.
    """
    out: list[str] = []
    stack: list[list] = []          # [child_col, owner, {key: line}]
    skip_col: int | None = None     # while set, lines deeper than this are block-scalar text
    for n, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        if not raw.strip():
            continue
        col = len(raw) - len(raw.lstrip(" "))
        if skip_col is not None:
            if col > skip_col:
                continue
            skip_col = None
        stripped = raw.strip()
        if stripped.startswith("#"):
            continue

        if stripped.startswith("- "):
            while stack and stack[-1][0] is not None and stack[-1][0] >= col + 2:
                stack.pop()
            rest = stripped[2:]
            m = re.match(r"^([\w.\-]+):(.*)$", rest)
            if not m:
                continue                       # a scalar list item (`- foo`), no keys to track
            stack.append([col + 2, rest.split(":")[0], {}])
            key, val = m.group(1), m.group(2).strip()
            stack[-1][2][key] = n
            if val.startswith("|") or val.startswith(">"):
                skip_col = col + 2
            continue

        m = re.match(r"^([\w.\-]+):(.*)$", stripped)
        if not m:
            continue
        key, val = m.group(1), m.group(2).strip()
        while stack and stack[-1][0] is not None and stack[-1][0] > col:
            stack.pop()
        if stack and stack[-1][0] is None:     # first child of a block: it decides the column
            stack[-1][0] = col
        if not stack or stack[-1][0] != col:
            stack.append([col, key, {}])
        frame = stack[-1]
        seen = frame[2]
        if key in seen:
            out.append(f"{disp_path(path)}:{n}: '{key}' is set twice in the block opened by "
                       f"'{frame[1]}' (first at line {seen[key]}) - Actions rejects the whole file for this, "
                       f"and PyYAML will not: the later value simply wins")
        seen[key] = n
        if val == "":
            stack.append([None, key, {}])      # a block follows; its column is not known yet
        elif val.startswith("|") or val.startswith(">"):
            skip_col = col
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
                uses = str(step.get("uses") or "")
                if uses.startswith("./"):
                    # The runner resolves a path-based action from the workspace before the action's own
                    # steps run, so a checkout inside the action is too late - the job has to do it first.
                    if not any(str(s.get("uses") or "").startswith("actions/checkout")
                               for s in (jd.get("steps") or [])[:i]):
                        out.append(f"{path}: job '{job}' runs the local action '{uses}' at step {i+1} with "
                                  f"no actions/checkout step before it - add "
                                  f"'- uses: actions/checkout@v4' to the JOB, above it")
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
