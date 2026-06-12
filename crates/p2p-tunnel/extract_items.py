#!/usr/bin/env python3
"""Move top-level items out of a source file into a sibling module file.

Usage: extract_items.py <src_rel> <dst_rel> token1 token2 ...

Paths are relative to the crate root (e.g. src/multiplex/mod.rs). A token is
either an item name (fn/struct/enum/trait/type/const/static) or `impl:Name` to
target an `impl ... Name {` block. Captures preceding attribute/doc lines,
balances braces, removes the block from <src_rel>, and appends it to <dst_rel>.
Visibility/imports are fixed up afterward.
"""
import re
import sys


def find_start(lines, token):
    if token.startswith("impl:"):
        name = token[len("impl:"):]
        pat = re.compile(r"^impl(<[^>]*>)?\s+" + re.escape(name) + r"\b")
    else:
        pat = re.compile(
            r"^(pub(\([^)]*\))?\s+)?"
            r"(async\s+)?(fn|struct|enum|trait|type|const|static)\s+" + re.escape(token) + r"\b"
        )
    hits = [i for i, line in enumerate(lines) if pat.match(line)]
    if not hits:
        raise SystemExit(f"token not found: {token}")
    if len(hits) > 1:
        raise SystemExit(f"ambiguous token {token!r}: lines {[h+1 for h in hits]}")
    return hits[0]


def block_extent(lines, start):
    head = start
    while head > 0:
        prev = lines[head - 1].lstrip()
        if prev.startswith("#[") or prev.startswith("#!") or prev.startswith("///") or prev.startswith("//!"):
            head -= 1
        else:
            break
    depth = 0
    seen = False
    i = start
    while i < len(lines):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                seen = True
            elif ch == "}":
                depth -= 1
        if seen and depth == 0:
            return head, i
        if not seen and lines[i].rstrip().endswith(";"):
            return head, i
        i += 1
    raise SystemExit(f"unterminated block at line {start+1}")


def main():
    src = sys.argv[1]
    dst = sys.argv[2]
    tokens = sys.argv[3:]
    with open(src) as f:
        lines = f.readlines()

    ranges = []
    for tok in tokens:
        s = find_start(lines, tok)
        h, e = block_extent(lines, s)
        ranges.append((h, e, tok))

    ranges.sort()
    for a, b in zip(ranges, ranges[1:]):
        if a[1] >= b[0]:
            raise SystemExit(f"overlap: {a[2]} and {b[2]}")

    moved = []
    for h, e, tok in ranges:
        moved.append("".join(lines[h : e + 1]).rstrip("\n") + "\n")
        print(f"{tok}: lines {h+1}-{e+1}")

    keep = list(lines)
    for h, e, _ in sorted(ranges, reverse=True):
        end = e
        if end + 1 < len(keep) and keep[end + 1].strip() == "":
            end += 1
        del keep[h : end + 1]

    with open(src, "w") as f:
        f.writelines(keep)

    with open(dst, "a") as f:
        f.write("\n\n".join(s.rstrip("\n") for s in moved) + "\n")


if __name__ == "__main__":
    main()
