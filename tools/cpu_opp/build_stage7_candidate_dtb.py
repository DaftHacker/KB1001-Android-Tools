#!/usr/bin/env python3
"""
KB1001 A333 next-stage OPP DTB candidate patcher.

Input:
  a DTB extracted from the already validated CPU4-1560 vendor_boot image.

Output:
  a candidate DTB that keeps the validated CPU4 1560 changes and additionally:
    - CPU4 / cluster2 1608 MHz: vf0403 -> 1.15 V, turbo-mode
    - CPU2-3 / cluster1 1776 MHz: vf0403 -> 1.15 V, turbo-mode

This tool NEVER flashes anything. It only edits a DTB file supplied by the user.
It fails closed unless the input already contains the validated 1560 semantics.
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

VALIDATED_1560_UV = 1_150_000
CANDIDATE_1608_UV = 1_150_000
CANDIDATE_1776_UV = 1_150_000


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=True)


def need_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise SystemExit(f"Required host tool not found: {name}")


def decompile(dtb: pathlib.Path, dts: pathlib.Path) -> None:
    p = run("dtc", "-I", "dtb", "-O", "dts", "-o", str(dts), str(dtb))
    if p.stderr:
        print(p.stderr, file=sys.stderr, end="")


def compile_dts(dts: pathlib.Path, dtb: pathlib.Path) -> None:
    p = run("dtc", "-I", "dts", "-O", "dtb", "-o", str(dtb), str(dts))
    if p.stderr:
        print(p.stderr, file=sys.stderr, end="")


def node_span(text: str, table: str, hz: int) -> tuple[int, int]:
    table_marker = f"{table} {{"
    table_start = text.find(table_marker)
    if table_start < 0:
        raise SystemExit(f"Missing DT table: {table}")

    node_marker = f"opp@{hz} {{"
    node_start = text.find(node_marker, table_start)
    if node_start < 0:
        raise SystemExit(f"Missing {table}/{node_marker}")

    brace = text.find("{", node_start)
    depth = 0
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                semi = text.find(";", i)
                return node_start, semi + 1
    raise SystemExit(f"Could not parse node: {table}/{node_marker}")


def get_node(text: str, table: str, hz: int) -> str:
    a, b = node_span(text, table, hz)
    return text[a:b]


def require_property(node: str, prop: str, expected: str | None = None) -> None:
    marker = prop + " = "
    if expected is None:
        if marker not in node and (prop + ";") not in node:
            raise SystemExit(f"Required property missing: {prop}")
        return
    exact = f"{prop} = <{expected}>;"
    if exact not in node:
        raise SystemExit(f"Required property mismatch: expected {exact}")


def set_u32_property(node: str, prop: str, value: int) -> str:
    import re
    hexv = f"0x{value:08x}"
    pat = re.compile(rf"(^\s*{re.escape(prop)}\s*=\s*<)[^>]+(>;)", re.M)
    if not pat.search(node):
        raise SystemExit(f"Property not found for replacement: {prop}")
    return pat.sub(rf"\g<1>{hexv}\2", node, count=1)


def ensure_boolean_property(node: str, prop: str) -> str:
    if f"{prop};" in node:
        return node
    close = node.rfind("};")
    if close < 0:
        raise SystemExit(f"Malformed node while adding {prop}")
    indent = "\t\t\t"
    return node[:close] + f"{indent}{prop};\n" + node[close:]


def replace_node(text: str, table: str, hz: int, new_node: str) -> str:
    a, b = node_span(text, table, hz)
    return text[:a] + new_node + text[b:]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_dtb", type=pathlib.Path)
    parser.add_argument("output_dtb", type=pathlib.Path)
    args = parser.parse_args()

    need_tool("dtc")

    if not args.input_dtb.is_file():
        raise SystemExit(f"Input DTB not found: {args.input_dtb}")

    with tempfile.TemporaryDirectory(prefix="kb1001_stage7_") as td:
        td = pathlib.Path(td)
        source_dts = td / "source.dts"
        verify_dts = td / "verify.dts"

        decompile(args.input_dtb, source_dts)
        text = source_dts.read_text(encoding="utf-8")

        # Gate on the already-validated CPU4 1560 patch.
        n1560 = get_node(text, "cluster2-opp-table", 1560000000)
        require_property(n1560, "opp-microvolt-vf0403", "0x00118c30")
        require_property(n1560, "turbo-mode")

        # The next candidate nodes must still be untouched for vf0403.
        n1608 = get_node(text, "cluster2-opp-table", 1608000000)
        require_property(n1608, "opp-microvolt-vf0403", "0x00")
        n1608 = set_u32_property(n1608, "opp-microvolt-vf0403", CANDIDATE_1608_UV)
        n1608 = ensure_boolean_property(n1608, "turbo-mode")
        text = replace_node(text, "cluster2-opp-table", 1608000000, n1608)

        n1776 = get_node(text, "cluster1-opp-table", 1776000000)
        require_property(n1776, "opp-microvolt-vf0403", "0x00")
        n1776 = set_u32_property(n1776, "opp-microvolt-vf0403", CANDIDATE_1776_UV)
        n1776 = ensure_boolean_property(n1776, "turbo-mode")
        text = replace_node(text, "cluster1-opp-table", 1776000000, n1776)

        source_dts.write_text(text, encoding="utf-8", newline="\n")
        args.output_dtb.parent.mkdir(parents=True, exist_ok=True)
        compile_dts(source_dts, args.output_dtb)

        # Independent semantic re-read of the compiled result.
        decompile(args.output_dtb, verify_dts)
        verified = verify_dts.read_text(encoding="utf-8")

        for table, hz, uv in (
            ("cluster2-opp-table", 1560000000, VALIDATED_1560_UV),
            ("cluster2-opp-table", 1608000000, CANDIDATE_1608_UV),
            ("cluster1-opp-table", 1776000000, CANDIDATE_1776_UV),
        ):
            node = get_node(verified, table, hz)
            require_property(node, "opp-microvolt-vf0403", f"0x{uv:08x}")
            require_property(node, "turbo-mode")

    print("KB1001 next-stage OPP candidate DTB created.")
    print("  CPU4 1560 MHz: validated patch preserved @ 1.15 V turbo")
    print("  CPU4 1608 MHz: candidate @ 1.15 V turbo")
    print("  CPU2-3 1776 MHz: candidate @ 1.15 V turbo")
    print("NO FLASH WAS PERFORMED.")
    print("The 1608/1776 points remain UNVALIDATED until physical staged testing passes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
