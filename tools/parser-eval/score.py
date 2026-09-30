import json, os, re, sys
gold = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'queries.json')))
def toks(s): return [t for t in re.split(r'[^a-z0-9]+', s.lower()) if t]
def score(path):
    rows = {}
    for line in open(path):
        parts = line.rstrip('\n').split('|', 2)
        if len(parts) < 3: continue
        m = re.search(r'\{.*?\}', parts[2])
        try: rows[int(parts[0])] = json.loads(m.group(0)) if m else None
        except Exception: rows[int(parts[0])] = None
    n = len(gold); a = k = t = full = valid = 0; misses = []
    for i, g in enumerate(gold):
        p = rows.get(i)
        if p is None: misses.append((g['q'], 'NO/INVALID JSON')); continue
        valid += 1
        ao = p.get('action') == g['action']; ko = p.get('kind') == g['kind']
        gt, pt = set(toks(g['text'])), toks(str(p.get('text', '')))
        to = gt <= set(pt) and len(pt) <= len(gt) + 1
        a += ao; k += ko; t += to; full += (ao and ko and to)
        if not (ao and ko and to): misses.append((g['q'], f"{p.get('action')}/{p.get('kind')}/{p.get('text')}  (want {g['action']}/{g['kind']}/{g['text']})"))
    return dict(n=n, valid=valid, action=a, kind=k, text=t, full=full), misses
for path in sys.argv[1:]:
    s, misses = score(path)
    print(f"== {path}: full {s['full']}/{s['n']}  action {s['action']}  kind {s['kind']}  text {s['text']}  valid {s['valid']}")
    for q, m in misses: print(f"   miss: {q!r} -> {m}")
