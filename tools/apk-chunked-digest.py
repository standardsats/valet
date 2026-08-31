#!/usr/bin/env python3
"""Show the CHUNKED_SHA256 digest F-Droid's reproducible-build check compares.

When F-Droid's CI rejects a build it reports something like:

    APK Signature Scheme v3 signer #1: APK integrity check failed.
    CHUNKED_SHA256 digest mismatch.
    Expected: <243be37c...>, actual: <02da71f6...>

"Expected" is the digest *stored* in the APK Signing Block, computed by whoever
signed the reference APK. "actual" is the digest *recomputed* from the APK's
current bytes. They differ whenever any byte of the ZIP changed after signing --
which, for a reproducible-build check, means our rebuild is not byte-identical to
the published one.

This prints both numbers for one APK, and can diff two APKs chunk by chunk so a
mismatch can be localised to a 1 MiB window instead of "somewhere in the file".

    ./apk-chunked-digest.py <apk>                 stored vs recomputed
    ./apk-chunked-digest.py <apk> --chunks        per-chunk digests
    ./apk-chunked-digest.py <a.apk> <b.apk>       compare two APKs chunk by chunk
    ./apk-chunked-digest.py <apk> --strip <out>   write the APK without its
                                                  signing block, so it can be
                                                  byte-compared with a rebuild

Spec: https://source.android.com/docs/security/features/apksigning/v2
Pure stdlib -- no apksigner, apksigtool or androguard needed.
"""

import hashlib
import struct
import sys

CHUNK_SIZE = 1048576  # 1 MiB, fixed by the spec
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
V3_BLOCK_ID = 0xF05368C0
V31_BLOCK_ID = 0x1B93AD61

BLOCK_NAMES = {
    V2_BLOCK_ID: "v2 (APK Signature Scheme v2)",
    V3_BLOCK_ID: "v3 (APK Signature Scheme v3)",
    V31_BLOCK_ID: "v3.1 (APK Signature Scheme v3.1)",
}

# signature algorithm ID -> content digest algorithm
SIG_ALGO_DIGEST = {
    0x0101: "CHUNKED_SHA256", 0x0102: "CHUNKED_SHA512",
    0x0103: "CHUNKED_SHA256", 0x0104: "CHUNKED_SHA512",
    0x0201: "CHUNKED_SHA256", 0x0202: "CHUNKED_SHA512",
    0x0301: "CHUNKED_SHA256",
    0x0421: "VERITY_CHUNKED_SHA256", 0x0423: "VERITY_CHUNKED_SHA256",
    0x0425: "VERITY_CHUNKED_SHA256",
}


def u32(b, off):
    return struct.unpack_from("<I", b, off)[0]


def u64(b, off):
    return struct.unpack_from("<Q", b, off)[0]


def find_eocd(data):
    """Locate the End Of Central Directory record (it may carry a comment)."""
    for off in range(len(data) - 22, max(-1, len(data) - 22 - 65535), -1):
        if data[off:off + 4] == b"PK\x05\x06":
            return off
    raise ValueError("no End Of Central Directory record -- not a ZIP/APK")


def locate(data):
    """Return the three offsets the digest is computed over."""
    eocd_off = find_eocd(data)
    cd_size = u32(data, eocd_off + 12)
    cd_off = u32(data, eocd_off + 16)

    # The APK Signing Block sits immediately before the Central Directory:
    #   uint64 size | id-value pairs | uint64 size | 16-byte magic
    sig_start = None
    if cd_off >= 24 and data[cd_off - 16:cd_off] == APK_SIG_BLOCK_MAGIC:
        size_end = u64(data, cd_off - 24)
        sig_start = cd_off - size_end - 8
        if sig_start < 0 or u64(data, sig_start) != size_end:
            raise ValueError("APK Signing Block size fields disagree -- APK is corrupt")
    return eocd_off, cd_off, cd_size, sig_start


def parse_sig_block(data, sig_start, cd_off):
    """Yield (block_id, value_bytes) for every ID-value pair in the signing block."""
    pos = sig_start + 8
    end = cd_off - 24
    while pos < end:
        pair_len = u64(data, pos)
        if pair_len < 4 or pos + 8 + pair_len > cd_off:
            break
        block_id = u32(data, pos + 8)
        yield block_id, data[pos + 12:pos + 8 + pair_len]
        pos += 8 + pair_len


def read_lenprefixed(buf, pos):
    """Read a uint32-length-prefixed chunk; return (payload, new_pos)."""
    n = u32(buf, pos)
    return buf[pos + 4:pos + 4 + n], pos + 4 + n


def stored_digests(value):
    """Extract the digests each signer recorded inside a v2/v3 block."""
    out = []
    signers, _ = read_lenprefixed(value, 0)
    pos = 0
    idx = 0
    while pos < len(signers):
        signer, pos = read_lenprefixed(signers, pos)
        idx += 1
        if not signer:
            continue
        # signer := signed data | (v3: minSDK, maxSDK) | signatures | public key
        signed_data, _ = read_lenprefixed(signer, 0)
        # signed data := digests | certificates | ...
        digests, _ = read_lenprefixed(signed_data, 0)
        dpos = 0
        while dpos < len(digests):
            entry, dpos = read_lenprefixed(digests, dpos)
            if len(entry) < 8:
                continue
            algo = u32(entry, 0)
            digest, _ = read_lenprefixed(entry, 4)
            out.append((idx, algo, digest))
    return out


def sections(data):
    """The three byte ranges the v2/v3 digest covers, in order."""
    eocd_off, cd_off, cd_size, sig_start = locate(data)
    content_end = sig_start if sig_start is not None else cd_off

    eocd = bytearray(data[eocd_off:])
    # The EOCD's "offset of central directory" is replaced by the offset of the
    # signing block, so the digest stays stable when the block's size changes.
    struct.pack_into("<I", eocd, 16, content_end)

    return [
        ("zip entries", data[:content_end]),
        ("central directory", data[cd_off:cd_off + cd_size]),
        ("eocd", bytes(eocd)),
    ]


def strip_block(data):
    """Drop the APK Signing Block, leaving the bytes the digest actually covers.

    The EOCD's "offset of central directory" is rewritten to the offset the
    signing block had, which is where the Central Directory now starts. The
    result is what an unsigned build of the same sources must equal byte for
    byte -- so `cmp` on it says *where* a reproducible build diverged.
    """
    eocd_off, cd_off, cd_size, sig_start = locate(data)
    if sig_start is None:
        raise ValueError("APK has no signing block -- nothing to strip")
    out = bytearray(data[:sig_start] + data[cd_off:])
    struct.pack_into("<I", out, eocd_off - (cd_off - sig_start) + 16, sig_start)
    return bytes(out)


def chunk_digests(data, algo="sha256"):
    """Per-chunk digests: SHA256(0xa5 || uint32le(len) || chunk)."""
    out = []
    for name, blob in sections(data):
        for i in range(0, max(len(blob), 1) if blob else 0, CHUNK_SIZE):
            chunk = blob[i:i + CHUNK_SIZE]
            h = hashlib.new(algo)
            h.update(b"\xa5" + struct.pack("<I", len(chunk)) + chunk)
            out.append((name, i // CHUNK_SIZE, len(chunk), h.digest()))
    return out


def compute(data, algo="sha256"):
    """Top-level digest: SHA256(0x5a || uint32le(count) || concat(chunk digests))."""
    chunks = chunk_digests(data, algo)
    h = hashlib.new(algo)
    h.update(b"\x5a" + struct.pack("<I", len(chunks)))
    for _, _, _, d in chunks:
        h.update(d)
    return h.digest(), chunks


def report(path, show_chunks=False):
    data = open(path, "rb").read()
    eocd_off, cd_off, cd_size, sig_start = locate(data)

    print(f"{path}  ({len(data)} bytes)")
    print(f"  zip entries       0 .. {sig_start if sig_start is not None else cd_off}")
    print(f"  signing block     {sig_start if sig_start is not None else '(none)'}")
    print(f"  central directory {cd_off} .. {cd_off + cd_size}")
    print(f"  eocd              {eocd_off}")

    # Both are cheap on APK-sized files, and a signer may have used either.
    recomputed = {}
    for kind, hname in (("CHUNKED_SHA256", "sha256"), ("CHUNKED_SHA512", "sha512")):
        recomputed[kind], chunks = compute(data, hname)

    print(f"\n  recomputed ({len(chunks)} chunks of {CHUNK_SIZE} bytes):")
    for kind, digest in recomputed.items():
        print(f"    {kind}: {digest.hex()}")

    if sig_start is None:
        print("\n  no APK Signing Block -- unsigned APK, so there is no stored digest")
        print("  to compare against. This is what F-Droid's builder produces.")
    else:
        print()
        for block_id, value in parse_sig_block(data, sig_start, cd_off):
            name = BLOCK_NAMES.get(block_id)
            if not name:
                continue
            print(f"  {name}:")
            for idx, algo, digest in stored_digests(value):
                kind = SIG_ALGO_DIGEST.get(algo, f"unknown(0x{algo:04x})")
                mark = ""
                if kind in recomputed:
                    mark = "  <-- MATCHES" if digest == recomputed[kind] else "  <-- MISMATCH"
                elif kind.startswith("VERITY"):
                    mark = "  (fs-verity tree, not recomputed here)"
                print(f"    signer #{idx}  {kind}")
                print(f"      stored: {digest.hex()}{mark}")

    if show_chunks:
        print(f"\n  per-chunk digests:")
        for name, i, size, d in chunks:
            print(f"    {name:18} chunk {i:4}  {size:8} bytes  {d.hex()}")
    return chunks


def compare(a, b):
    print("=== A ===")
    ca = report(a)
    print("\n=== B ===")
    cb = report(b)

    print("\n=== per-chunk comparison ===")
    if len(ca) != len(cb):
        print(f"!! different chunk counts: A={len(ca)} B={len(cb)}")
        print("   the files differ in size, so every later chunk shifts -- compare")
        print("   the ZIP structure first (tools/zipdiff.py)")
    differing = 0
    for x, y in zip(ca, cb):
        if x[3] != y[3]:
            differing += 1
            if differing <= 10:
                print(f"  {x[0]:18} chunk {x[1]:4}  A={x[3].hex()[:16]}...  B={y[3].hex()[:16]}...")
    if not differing and len(ca) == len(cb):
        print("  all chunks identical -- the APKs are byte-identical over the")
        print("  signed sections; any difference is inside the signing block itself")
    else:
        print(f"\n  {differing} of {min(len(ca), len(cb))} chunks differ")
        first = next((x[1] for x, y in zip(ca, cb) if x[3] != y[3]), None)
        if first is not None:
            lo = first * CHUNK_SIZE
            print(f"  first difference in the chunk covering bytes {lo}..{lo + CHUNK_SIZE}")


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    if "--strip" in argv:
        if len(args) != 2:
            sys.exit("--strip needs an input APK and an output path")
        data = open(args[0], "rb").read()
        out = strip_block(data)
        open(args[1], "wb").write(out)
        print(f"{args[1]}: {len(out)} bytes ({len(data) - len(out)} bytes of "
              f"signing block removed)")
        print("compare it with the unsigned rebuild:")
        print(f"  cmp -l {args[1]} <rebuild.apk> | head")
        print(f"  ./tools/zipdiff.py {args[1]} <rebuild.apk>")
    elif len(args) == 1:
        report(args[0], show_chunks="--chunks" in argv)
    else:
        compare(args[0], args[1])


if __name__ == "__main__":
    main(sys.argv[1:])
