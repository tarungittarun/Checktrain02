#!/usr/bin/env python3
"""Verify every R.* and ViewBinding reference in Kotlin against the resources that exist.

The binding fields a screen touches are generated from XML ids, so a renamed id is a compile error
that only surfaces in CI; checking it here costs a second and turns that into a message that names the
layout. Complements `check_kotlin_balance.py`, which catches cut-off files.
"""
from __future__ import annotations
import pathlib
import re
import sys

RES = pathlib.Path("app/src/main/res")
SRC = pathlib.Path("app/src/main/java")
KIND_TO_DIR = {
    "layout": "layout", "string": "values", "plurals": "values", "color": "values",
    "style": "values", "drawable": "drawable", "xml": "xml", "id": "layout",
}


def resource_names() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {k: set() for k in KIND_TO_DIR}
    for xml in RES.rglob("*.xml"):
        text = xml.read_text()
        if xml.parent.name == "layout":
            out["layout"].add(xml.stem)
            for m in re.finditer(r'android:id="@\+id/(\w+)"', text):
                out["id"].add(m.group(1))
        for m in re.finditer(r'<(string|plurals|color|string-array)\s+name="(\w+)"', text):
            key = "plurals" if m.group(1) == "plurals" else m.group(1)
            if key in out:
                out[key].add(m.group(2))
        for m in re.finditer(r'<style\s+name="([\w.]+)"', text):
            out["style"].add(m.group(1).split(".")[-1])
            for part in m.group(1).split("."):
                out["style"].add(part)
        if xml.parent.name in ("drawable", "mipmap-anydpi-v26"):
            out["drawable"].add(xml.stem)
    return out


def camel(snake: str) -> str:
    head, *rest = snake.split("_")
    return head + "".join(w[:1].upper() + w[1:] for w in rest)


def main() -> int:
    names = resource_names()
    problems = 0
    for kt in sorted(SRC.rglob("*.kt")):
        text = kt.read_text()
        # `android.R.layout.…` is a framework resource and is not ours to check.
        for m in re.finditer(r"(?<!android\.)\bR\.(layout|string|plurals|color|style|drawable|xml|id)\.(\w+)", text):
            kind, res = m.group(1), m.group(2)
            if res not in names[kind]:
                problems += 1
                print(f"{kt}: R.{kind}.{res} — no such resource")
        # ViewBinding: `binding.foo` in an activity that inflates ActivityFooBinding
        for m in re.finditer(r"(\w+Binding)\.inflate", text):
            binding = m.group(1)
            layout = re.sub(r"(?<!^)(?=[A-Z])", "_", binding[:-7]).lower()
            layout_file = RES / "layout" / f"{layout}.xml"
            if not layout_file.exists():
                problems += 1
                print(f"{kt}: {binding} has no res/layout/{layout}.xml")
                continue
            ids = set(re.findall(r'android:id="@\+id/(\w+)"', layout_file.read_text()))
            id_fields = {camel(i) for i in ids}
            block = text[m.end():]
            for use in re.finditer(r"\bbinding\.(\w+)", text):
                field = use.group(1)
                if field in ("root",) or field in id_fields:
                    continue
                if field not in names["id"] and field not in {camel(i) for i in names["id"]}:
                    problems += 1
                    print(f"{kt}: binding.{field} — no @+id/{field} in {layout}.xml")
            del block
    print(f"checked {len(list(SRC.rglob('*.kt')))} sources, {problems} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
