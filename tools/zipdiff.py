#!/usr/bin/env python3
"""Diff the ZIP-level metadata of two APKs, and name every entry that differs.

Answers "which files inside the APK are not reproducing?" -- run this before
reaching for diffoscope, which is far slower and buries the answer in noise.

    ./zipdiff.py <a.apk> <b.apk>

A differing CRC means the entry's *uncompressed content* differs: a real build
difference. A matching CRC with a differing compressed size means the same bytes
were deflated differently: a toolchain difference (JDK/zlib version), not a
source difference. The summary at the end says which of the two you have.
"""

import collections
import posixpath
import sys
import zipfile


def dump(path):
    with zipfile.ZipFile(path) as z:
        return [{
            "name": i.filename, "method": i.compress_type, "crc": i.CRC,
            "usize": i.file_size, "csize": i.compress_size,
            "time": i.date_time, "flags": i.flag_bits, "extra": i.extra.hex(),
        } for i in z.infolist()]


def ext_of(name):
    base = posixpath.basename(name)
    if name.endswith("/"):
        return "<dir>"
    _, dot, ext = base.rpartition(".")
    return "." + ext if dot else "<no ext>"


def main(a, b):
    A, B = dump(a), dump(b)
    na, nb = [e["name"] for e in A], [e["name"] for e in B]
    print(f"A = {a}  ({len(A)} entries)")
    print(f"B = {b}  ({len(B)} entries)\n")

    only_a, only_b = set(na) - set(nb), set(nb) - set(na)
    if only_a:
        print(f"ONLY IN A ({len(only_a)}): {sorted(only_a)[:10]}")
    if only_b:
        print(f"ONLY IN B ({len(only_b)}): {sorted(only_b)[:10]}")
    if not only_a and not only_b and na != nb:
        print("!! same entries, DIFFERENT ORDER -- this alone breaks byte-identity")

    ta = sorted({e["time"] for e in A})
    tb = sorted({e["time"] for e in B})
    print(f"timestamps A: {ta[:3]}  ({len(ta)} distinct)")
    print(f"timestamps B: {tb[:3]}  ({len(tb)} distinct)")
    if ta != tb:
        print("!! TIMESTAMP MISMATCH -- SOURCE_DATE_EPOCH differs between the builds")

    print(f"entries with extra field: A={sum(1 for e in A if e['extra'])} "
          f"B={sum(1 for e in B if e['extra'])}")

    # Per-entry comparison over the shared entries.
    mb = {e["name"]: e for e in B}
    content_diff, deflate_diff, meta_diff = [], [], []
    for e in A:
        o = mb.get(e["name"])
        if not o:
            continue
        if e["crc"] != o["crc"]:
            content_diff.append((e, o))
        elif e["csize"] != o["csize"]:
            deflate_diff.append((e, o))
        elif any(e[k] != o[k] for k in ("method", "time", "extra", "flags")):
            meta_diff.append((e, o))

    def report(title, rows, cols):
        if not rows:
            return
        print(f"\n{title} ({len(rows)}):")
        by_ext = collections.Counter(ext_of(e["name"]) for e, _ in rows)
        print("  by extension: " + ", ".join(
            f"{ext} x{n}" for ext, n in by_ext.most_common()))
        print()
        for e, o in sorted(rows, key=lambda r: -r[0]["usize"])[:40]:
            print(f"  {e['name']}")
            for c in cols:
                va, vb = e[c], o[c]
                if va != vb:
                    print(f"      {c:6} A={va}  B={vb}")
        if len(rows) > 40:
            print(f"  ... and {len(rows) - 40} more")

    report("CONTENT DIFFERS (crc mismatch -- a real build difference)",
           content_diff, ("crc", "usize", "csize"))
    report("SAME CONTENT, DIFFERENT COMPRESSION (crc equal, csize differs)",
           deflate_diff, ("csize", "method"))
    report("METADATA ONLY (content and size equal)",
           meta_diff, ("time", "method", "extra", "flags"))

    print("\n" + "=" * 60)
    if not (content_diff or deflate_diff or meta_diff or only_a or only_b):
        print("No ZIP-level differences at all. If the APKs still differ, the")
        print("delta is in the signing block or the central directory padding.")
    if content_diff:
        print(f"{len(content_diff)} entries differ in CONTENT. These are real build")
        print("differences -- the compiler/packager produced different bytes.")
    if deflate_diff:
        print(f"{len(deflate_diff)} entries have identical content but compressed")
        print("differently -- a JDK/zlib mismatch between the two build machines,")
        print("not a source difference.")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: zipdiff.py <a.apk> <b.apk>")
    main(sys.argv[1], sys.argv[2])
