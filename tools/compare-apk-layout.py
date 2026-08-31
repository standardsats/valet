#!/usr/bin/env python3
"""Explain why a published APK and a rebuild of it are not the same file.

F-Droid does not compare the two APKs directly: it takes the signature off the
published one, transplants it onto its own unsigned rebuild with apksigcopier and
checks that the v2/v3 digests still match.  Those digests cover the whole ZIP,
so the rebuild has to agree with the published APK byte for byte over everything
that precedes the signature -- not just in the files it contains, but in ZIP
metadata nobody normally looks at: entry order, compression, local header fields
and the alignment padding in the extra field.

`fdroid build --test` only reports "digest mismatch" plus a `diff -r` of the
unpacked trees, which is empty whenever the difference is in that metadata.  This
prints the difference instead.

    tools/compare-apk-layout.py published.apk rebuild.apk

The rebuild is expected to be unsigned (the v1 signature entries and the APK
Signing Block of the published APK are ignored).  Exit status is 0 when the
rebuild can carry the published signature.
"""

import struct
import sys
import zipfile

META = ('META-INF/MANIFEST.MF',)
META_SUFFIXES = ('.SF', '.RSA', '.DSA', '.EC')


def is_signature_entry(name):
    """v1 (JAR) signature files, which apksigner appends and apksigcopier re-creates."""
    if name in META:
        return True
    return name.startswith('META-INF/') and name.endswith(META_SUFFIXES) \
        and '/' not in name[len('META-INF/'):]


def local_headers(path):
    """Per-entry local file header fields, in physical order."""
    raw = open(path, 'rb').read()
    out = []
    with zipfile.ZipFile(path) as zf:
        for info in sorted(zf.infolist(), key=lambda i: i.header_offset):
            off = info.header_offset
            if raw[off:off + 4] != b'PK\x03\x04':
                sys.exit(f'{path}: no local file header at {off}')
            (_, ver, flags, method, mtime, mdate, crc, csize, usize,
             nlen, elen) = struct.unpack('<IHHHHHIIIHH', raw[off:off + 30])
            out.append(dict(
                name=info.filename, offset=off, version=ver, flags=flags,
                method=method, dostime=(mdate << 16) | mtime, crc=crc,
                lfh_csize=csize, lfh_usize=usize,
                extra=raw[off + 30 + nlen:off + 30 + nlen + elen],
                data_offset=off + 30 + nlen + elen,
                csize=info.compress_size, usize=info.file_size,
                cd_crc=info.CRC, date_time=info.date_time,
            ))
    return out, raw


def central_directory_offset(raw):
    eocd = raw.rfind(b'PK\x05\x06')
    return struct.unpack('<I', raw[eocd + 16:eocd + 20])[0]


def describe_extra(extra):
    if not extra:
        return 'none'
    if extra[:2] == b'\x35\xd9':
        return f'apksigner alignment record (0xd935), {len(extra)} bytes'
    if not any(extra):
        return f'{len(extra)} zero bytes (zipalign padding)'
    return f'{len(extra)} bytes: {extra.hex()}'


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().splitlines()[0] + '\n\nusage: '
                 f'{sys.argv[0]} published.apk rebuild.apk')
    published, rebuild = sys.argv[1], sys.argv[2]

    pub, pub_raw = local_headers(published)
    reb, reb_raw = local_headers(rebuild)
    pub = [e for e in pub if not is_signature_entry(e['name'])]
    reb = [e for e in reb if not is_signature_entry(e['name'])]
    problems = []

    pub_names = [e['name'] for e in pub]
    reb_names = [e['name'] for e in reb]
    if set(pub_names) != set(reb_names):
        only_pub = [n for n in pub_names if n not in set(reb_names)]
        only_reb = [n for n in reb_names if n not in set(pub_names)]
        problems.append(f'entry sets differ: only in published {only_pub[:10]}, '
                        f'only in rebuild {only_reb[:10]}')
    elif pub_names != reb_names:
        i = next(k for k in range(len(pub_names)) if pub_names[k] != reb_names[k])
        problems.append(f'entry order differs from index {i}: published has '
                        f'{pub_names[i]!r}, rebuild has {reb_names[i]!r}')
    else:
        # Same entries in the same order: report the first field that differs, per class,
        # so one run tells you whether it is content, compression or ZIP bookkeeping.
        fields = [
            ('cd_crc', 'file contents (CRC)'),
            ('method', 'compression method'),
            ('csize', 'compressed size (compressor or its settings differ)'),
            ('date_time', 'entry timestamp (SOURCE_DATE_EPOCH or TZ differs)'),
            ('flags', 'general purpose flags'),
            ('version', 'version-needed-to-extract'),
            ('lfh_crc', 'local header CRC/size fields'),
        ]
        for key, what in fields:
            if key == 'lfh_crc':
                bad = [(a, b) for a, b in zip(pub, reb)
                       if (a['crc'], a['lfh_csize'], a['lfh_usize'])
                       != (b['crc'], b['lfh_csize'], b['lfh_usize'])]
                if bad:
                    a, b = bad[0]
                    problems.append(
                        f'{what}: {len(bad)} entries, e.g. {a["name"]!r} '
                        f'published crc={a["crc"]:#x} sizes={a["lfh_csize"]}/{a["lfh_usize"]}, '
                        f'rebuild crc={b["crc"]:#x} sizes={b["lfh_csize"]}/{b["lfh_usize"]} '
                        '-- one side ran the file through a tool that rewrites local '
                        'headers (zipalign) and the other did not')
                continue
            bad = [(a, b) for a, b in zip(pub, reb) if a[key] != b[key]]
            if bad:
                a, b = bad[0]
                problems.append(f'{what}: {len(bad)} entries, e.g. {a["name"]!r} '
                                f'published {a[key]!r} vs rebuild {b[key]!r}')

        bad = [(a, b) for a, b in zip(pub, reb) if a['extra'] != b['extra']]
        if bad:
            a, b = bad[0]
            problems.append(
                f'alignment padding: {len(bad)} entries, e.g. {a["name"]!r} '
                f'published {describe_extra(a["extra"])}, rebuild {describe_extra(b["extra"])} '
                '-- apksigner realigns unless it is passed --alignment-preserved true, '
                'and apksigcopier will not undo that')

        same_data = [(a, b) for a, b in zip(pub, reb)
                     if pub_raw[a['data_offset']:a['data_offset'] + a['csize']]
                     != reb_raw[b['data_offset']:b['data_offset'] + b['csize']]]
        if same_data:
            problems.append(f'compressed bytes: {len(same_data)} entries differ although the '
                            'entry metadata matches, e.g. ' + repr(same_data[0][0]['name']))

    # The check that actually decides it: everything up to the signature must be identical.
    content = central_directory_offset(reb_raw)
    prefix_ok = len(pub_raw) > content and pub_raw[:content] == reb_raw[:content]

    if not problems and prefix_ok:
        print(f'OK: {published} is {rebuild} plus a signature '
              f'({content} identical bytes of entry region)')
        return 0

    print(f'{published} cannot carry a signature verifiable against {rebuild}:')
    for p in problems:
        print(f'  - {p}')
    if not problems and not prefix_ok:
        first = next(i for i in range(content) if pub_raw[i] != reb_raw[i])
        print(f'  - entry regions differ from byte {first} although every entry matches; '
              'the difference is in the central directory or end-of-central-directory record')
    return 1


if __name__ == '__main__':
    sys.exit(main())
