#!/usr/bin/env python3
"""Cross-file member reference check for the app's own Kotlin types.

Catches the exact class of mistake this project generates when code is written without a compiler:
`Text.normalize` when the declaration is `normalise`, or a call to a helper that was never added
(`resolveAnchor` shipped broken exactly this way). It is a heuristic, not a type-checker: it only
reports references to a *declared* object/class/companion in our own sources whose member cannot be
found by name anywhere in that file's text.
"""
from __future__ import annotations
import pathlib
import re
import sys

DECL = re.compile(
    r"^(?:\s*)(?:@\w+\s+)*(?:public |internal |private |open |abstract |sealed |data |value |)*(?P<kind>object|class|interface|enum class)\s+(?P<name>\w+)",
    re.M,
)
MEMBER = re.compile(r"(?:fun|val|var|const val)\s+(?:<[^>]+>\s+)?(\w+)", re.M)
USE = re.compile(r"\b([A-Z]\w*)\.([a-z]\w*)\s*[(\[]?")


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java")
    files = sorted(root.rglob("*.kt"))
    # name -> (file text, path) for our own objects/classes
    decls: dict[str, tuple[str, pathlib.Path]] = {}
    for f in files:
        text = f.read_text()
        for m in DECL.finditer(text):
            decls.setdefault(m.group("name"), (text, f))
    problems = 0
    for f in files:
        text = f.read_text()
        for use in USE.finditer(text):
            owner, member = use.group(1), use.group(2)
            if owner not in decls or member in ("companion", "class", "copy", "let", "also"):
                continue
            otext, opath = decls[owner]
            names = set(MEMBER.findall(otext))
            # enum constants, nested types and constructor params all count as "declared"
            if member in names:
                continue
            # a member defined only as `operator fun invoke`/`get()` or an enum entry: check textually
            if re.search(rf"\b{re.escape(member)}\b", otext):
                continue
            problems += 1
            print(f"{f}: `{owner}.{member}` not found in {opath.name}")
    # also: constructor arg names used in `Type(field = ...)` must exist on the type
    for f in files:
        text = f.read_text()
        for m in re.finditer(r"\b([A-Z]\w*)\(([^)]{5,})\)", text, re.S):
            owner, body = m.group(1), m.group(2)
            if owner not in decls:
                continue
            kw = re.findall(r"(?:^|[,\s(])([a-z]\w*)\s*=", body)
            if not kw:
                continue
            otext, _ = decls[owner]
            declared = set(re.findall(r"\b(?:val|var)\s+(\w+)", otext))
            declared |= set(MEMBER.findall(otext))
            for k in kw:
                if k not in declared and not re.search(rf"\b{re.escape(k)}\b", otext):
                    problems += 1
                    print(f"{f}: `{owner}({k} = …)` — no such property on {owner}")
    print(f"checked {len(files)} files against {len(decls)} declared types, {problems} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
