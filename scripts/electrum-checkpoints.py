#!/usr/bin/env python3
"""Write an Electrum checkpoints file (app/src/<flavor>/assets/checkpoints_*.json).

Checkpoint N is the hash of block (N + 1) * 2016 - 1 and the target of the next block,
which is how CheckPoint.load reads them. Both come from the header at (N + 1) * 2016.

Usage: electrum-checkpoints.py HOST PORT OUT_FILE [--ssl]
Example: electrum-checkpoints.py electrum.mutinynet.com 50001 app/src/signet/assets/checkpoints_signet.json
"""

import json
import socket
import ssl
import sys

RETARGETING_PERIOD = 2016
# Keep the last checkpoint this many blocks below the tip, as Blockchain.MAX_REORG does
MAX_REORG = 72
BATCH = 200


def connect(host, port, use_ssl):
    sock = socket.create_connection((host, port), timeout=30)
    if use_ssl:
        # Valet itself does not verify Electrum certificates (SSL.LOOSE)
        sock = ssl._create_unverified_context().wrap_socket(sock, server_hostname=host)
    return sock.makefile("rwb")


def call_many(conn, calls):
    for request_id, (method, params) in enumerate(calls):
        conn.write((json.dumps({"id": request_id, "method": method, "params": params}) + "\n").encode())
    conn.flush()
    results = {}
    while len(results) < len(calls):
        response = json.loads(conn.readline())
        if "id" not in response:
            continue
        if response.get("error"):
            raise RuntimeError(response["error"])
        results[response["id"]] = response["result"]
    return [results[request_id] for request_id in range(len(calls))]


def target_from_bits(bits):
    exponent, mantissa = bits >> 24, bits & 0x007FFFFF
    return mantissa >> (8 * (3 - exponent)) if exponent <= 3 else mantissa << (8 * (exponent - 3))


def checkpoint(raw_header_hex):
    raw = bytes.fromhex(raw_header_hex)
    previous_block_id = raw[4:36][::-1].hex()
    bits = int.from_bytes(raw[72:76], "little")
    return [previous_block_id, target_from_bits(bits)]


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    host, port, out_file = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    conn = connect(host, port, "--ssl" in sys.argv[4:])

    tip = call_many(conn, [("blockchain.headers.subscribe", [])])[0]["height"]
    heights = list(range(RETARGETING_PERIOD, tip - MAX_REORG + 1, RETARGETING_PERIOD))

    checkpoints = []
    for start in range(0, len(heights), BATCH):
        calls = [("blockchain.block.header", [height]) for height in heights[start:start + BATCH]]
        checkpoints += [checkpoint(raw) for raw in call_many(conn, calls)]
        print(f"{len(checkpoints)} / {len(heights)}", file=sys.stderr)

    with open(out_file, "w") as out:
        json.dump(checkpoints, out, indent=2)
        out.write("\n")
    print(f"tip {tip}, {len(checkpoints)} checkpoints, last at height {len(checkpoints) * RETARGETING_PERIOD - 1}", file=sys.stderr)


if __name__ == "__main__":
    main()
