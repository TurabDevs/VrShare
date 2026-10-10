#!/usr/bin/env python3
"""Resolve a Maven dependency closure and download jars/aars (parallel).

Usage: resolve.py <outdir>
"""
import os, re, sys, threading, urllib.request, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

OUT = sys.argv[1]
JARS = os.path.join(OUT, "jars")
AARS = os.path.join(OUT, "aars")
os.makedirs(JARS, exist_ok=True)
os.makedirs(AARS, exist_ok=True)

REPOS = [
    "https://repo.maven.apache.org/maven2",
    "https://dl.google.com/dl/android/maven2",
]

ROOTS = [
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.appcompat", "appcompat", "1.7.0"),
    ("com.google.android.material", "material", "1.12.0"),
    ("androidx.constraintlayout", "constraintlayout", "2.1.4"),
    ("androidx.recyclerview", "recyclerview", "1.3.2"),
    ("androidx.activity", "activity-ktx", "1.9.2"),
    ("androidx.fragment", "fragment-ktx", "1.8.2"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.3"),
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.8.1"),
    ("androidx.security", "security-crypto", "1.1.0-alpha06"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.20"),
]

NS = 'xmlns="http://maven.apache.org/POM/4.0.0"'
_lock = threading.Lock()
_pom_cache = {}

def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": "vrshare-resolver/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

def gav_url(g, a, v, ext):
    return [(f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}", repo) for repo in REPOS]

def fetch_first(g, a, v, ext):
    key = (g, a, v, ext)
    with _lock:
        if key in _pom_cache:
            return _pom_cache[key]
    last = None
    for url, _ in gav_url(g, a, v, ext):
        try:
            data = fetch(url)
            with _lock:
                _pom_cache[key] = data
            return data
        except Exception as e:
            last = e
    raise RuntimeError(f"not found: {g}:{a}:{v}:{ext} ({last})")

def text(e, tag):
    c = e.find(tag)
    return c.text.strip() if c is not None and c.text else ""

def subst(s, props):
    for _ in range(5):
        ns = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), s)
        if ns == s:
            break
        s = ns
    return s

def parse_pom(data):
    data = data.replace(NS.encode(), b"")
    root = ET.fromstring(data)
    props = {p.tag: (p.text.strip() if p.text else "") for p in root.findall("./properties/*")}
    mgmt = {}
    for d in root.findall("./dependencyManagement/dependencies/dependency"):
        g, a, v = text(d, "groupId"), text(d, "artifactId"), text(d, "version")
        if g and a and v:
            mgmt[(g, a)] = v
    deps = []
    for d in root.findall("./dependencies/dependency"):
        if (text(d, "scope") or "compile") not in ("compile", "runtime"):
            continue
        if text(d, "optional") == "true":
            continue
        if text(d, "classifier"):
            continue
        g, a = text(d, "groupId"), text(d, "artifactId")
        v, t = text(d, "version"), text(d, "type") or "jar"
        excl = {(text(e, "groupId"), text(e, "artifactId")) for e in d.findall("./exclusions/exclusion")}
        if g and a:
            deps.append((g, a, v, t, excl))
    packaging = text(root, "packaging") or "jar"
    parent = root.find("./parent")
    parent_gav = None
    if parent is not None and text(parent, "groupId"):
        parent_gav = (text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"))
    return props, mgmt, deps, packaging, parent_gav

def get_pom_info(g, a, v):
    """Returns (props, mgmt, deps, packaging) with one parent level merged."""
    data = fetch_first(g, a, v, "pom")
    props, mgmt, deps, packaging, parent_gav = parse_pom(data)
    if parent_gav:
        try:
            pdata = fetch_first(*parent_gav, "pom")
            p_props, p_mgmt, _, _, _ = parse_pom(pdata)
            props = {**p_props, **props}
            mgmt = {**p_mgmt, **mgmt}
        except Exception as e:
            print(f"WARN: parent pom failed for {g}:{a}: {e}", file=sys.stderr)
    return props, mgmt, deps, packaging

# ---- phase 1: BFS closure ----
seen = set(ROOTS)
ext_of = {}
frontier = list(ROOTS)

def norm_version(v):
    # Maven version ranges: "[1.8.2]" means exactly 1.8.2; "[1.0,2.0)" -> use lower bound.
    m = re.match(r"^\[\s*([^,\]]+)", v or "")
    return m.group(1).strip() if m else v

def process_one(gav):
    g, a, v = gav
    try:
        props, mgmt, deps, packaging = get_pom_info(g, a, v)
    except Exception as e:
        print(f"WARN: pom failed {g}:{a}:{v}: {e}", file=sys.stderr)
        return []
    with _lock:
        ext_of[gav] = "aar" if packaging == "aar" else "jar"
    out = []
    for dg, da, dv, dt, dexc in deps:
        dg, da = subst(dg, props), subst(da, props)
        dv = subst(dv, props) if dv else mgmt.get((dg, da), "")
        if not dv:
            print(f"WARN: no version for {dg}:{da} (from {g}:{a})", file=sys.stderr)
            continue
        if (dg, da) in dexc:
            continue
        key = (dg, da, norm_version(dv))
        with _lock:
            if key in seen:
                continue
            seen.add(key)
        out.append(key)
    return out

with ThreadPoolExecutor(max_workers=12) as ex:
    while frontier:
        print(f"resolving {len(frontier)} poms... ({len(seen)} total)", flush=True)
        nxt = []
        for res in ex.map(process_one, frontier):
            nxt.extend(res)
        frontier = nxt

print(f"closure: {len(seen)} artifacts", flush=True)

# ---- conflict resolution: keep highest version per (group, artifact) ----
def vkey(v):
    parts = re.split(r"[.\-]", v)
    out = []
    for p in parts:
        out.append((0, int(p)) if p.isdigit() else (1, p))
    return out

best = {}
for g, a, v in seen:
    k = (g, a)
    if k not in best or vkey(v) > vkey(best[k][2]):
        best[k] = (g, a, v)
dropped = len(seen) - len(best)
seen = set(best.values())
print(f"conflict resolution: kept {len(seen)}, dropped {dropped}", flush=True)

# ---- phase 2: download binaries ----
manifest = []

def download_one(gav):
    g, a, v = gav
    ext = ext_of.get(gav, "jar")
    try:
        data = fetch_first(g, a, v, ext)
    except Exception as e:
        print(f"WARN: binary failed {g}:{a}:{v}:{ext}: {e}", file=sys.stderr)
        return None
    dest = os.path.join(AARS if ext == "aar" else JARS, f"{a}-{v}.{ext}")
    if not os.path.exists(dest):
        with open(dest, "wb") as f:
            f.write(data)
    print(f"got {a}-{v}.{ext} ({len(data)//1024} KB)", flush=True)
    return dest

with ThreadPoolExecutor(max_workers=8) as ex:
    for dest in ex.map(download_one, sorted(seen)):
        if dest:
            manifest.append(dest)

with open(os.path.join(OUT, "classpath.txt"), "w") as f:
    f.write("\n".join(sorted(set(manifest))))
print(f"\n{len(set(manifest))} artifacts -> {OUT}")
