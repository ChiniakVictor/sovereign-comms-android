#!/usr/bin/env python3
"""Copy meshtastic/protobufs (pinned tag) into app/src/main/proto, stripping the
meshtastic.*_metadata custom options (UI metadata only; they need descriptor.proto,
which protobuf-javalite does not ship). Wire format is unchanged."""
import os, re, shutil, sys
TAG = "v2.8.1"
src = os.path.join(os.path.dirname(__file__), "..", "third_party", f"protobufs-{TAG[1:]}", "meshtastic")
dst = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "proto", "meshtastic")
shutil.rmtree(dst, ignore_errors=True); os.makedirs(dst)
SKIP = {"field_metadata.proto", "nanopb.proto", "deviceonly.proto", "deviceonly_legacy.proto"}

def strip(text):
    out, i = [], 0
    pat = re.compile(r"\(meshtastic\.\w+_metadata\)\s*=\s*\{")
    while True:
        m = pat.search(text, i)
        if not m:
            out.append(text[i:]); break
        out.append(text[i:m.start()])
        j, depth = m.end(), 1
        while depth:
            c = text[j]
            if c == '{': depth += 1
            elif c == '}': depth -= 1
            elif c == '"':
                j += 1
                while text[j] != '"':
                    if text[j] == '\\': j += 1
                    j += 1
            j += 1
        out.append("\x00")  # marker for removed option
        i = j
    t = "".join(out)
    # remove marker plus adjoining comma
    t = re.sub(r",\s*\x00", "", t)
    t = re.sub(r"\x00\s*,", "", t)
    t = t.replace("\x00", "")
    t = re.sub(r"\[\s*\]", "", t)          # empty option lists
    t = re.sub(r'^\s*import "meshtastic/field_metadata.proto";\s*$', "", t, flags=re.M)
    t = re.sub(r'^\s*option\s*\(meshtastic\.\w+\)\s*=.*?;\s*$', "", t, flags=re.M)
    return t

for f in sorted(os.listdir(src)):
    if not f.endswith(".proto") or f in SKIP: continue
    t = open(os.path.join(src, f)).read()
    t2 = strip(t)
    if "_metadata)" in t2: sys.exit(f"left metadata in {f}")
    open(os.path.join(dst, f), "w").write(t2)
print("protos ->", os.path.normpath(dst), "tag", TAG)
