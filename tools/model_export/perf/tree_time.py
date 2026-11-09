"""Old (delta prefix run + padded batch) vs tree (one call) latency for keyboard-like word shapes.
  .venv/bin/python android_export/perf/tree_time.py OLD.ort NEW.ort [threads]
"""
import os, sys, time
sys.argv += [] 
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tree_check.py')).read().split('rng = np.random')[0]
exec(src)

def tree_feeds(st, pm, p0, trunk, cands):
    toks, depth, mask, cidx, paths = tree_inputs(trunk, cands)
    T = len(trunk)
    head, en, et = {}, [], []
    def h(i):
        if i not in head: head[i] = len(head)
        return head[i]
    for path, t in zip(paths, cands):
        prev = T - 1
        for q, node in enumerate(path):
            if prev >= 0: en.append(h(prev)); et.append(t[q])
            prev = node
        h(path[-1])
    hl = h(T - 1) if T else 0
    return {'ids': toks[None], 'pos': depth + p0, 'past_mask': pm, **{'past_' + k: v for k, v in st.items()},
            'tree_mask': mask, 'conv_idx': cidx, 'head_nodes': np.array(sorted(head, key=head.get), np.int64),
            'edge_node': np.array(en, np.int64), 'edge_tok': np.array(et, np.int64), 'last_node': np.array([hl], np.int64)}, len(toks), len(head)

rng = np.random.default_rng(0)
st0, pm0 = fresh()
ctx = [2] + rng.integers(300, VMAX, 20).tolist()
o = old_run([ctx], 0, st0, pm0, True)
st = {k: o['present_' + k] for k in stn}; pm = np.concatenate([pm0, np.ones(len(ctx), bool)]); P = len(ctx)

def med(fn, reps=15):
    for _ in range(3): fn()
    ts = []
    for _ in range(reps):
        t = time.perf_counter(); fn(); ts.append(time.perf_counter() - t)
    return 1e3 * float(np.median(ts))

scen = {}
for name, nc, lens, shared in [('typing12', 12, (1, 1, 1, 2, 2, 3, 4), 0.3), ('typing6', 6, (1, 1, 2, 3), 0.3),
                                ('recheck5', 5, (2, 3, 3, 4), 0.5), ('swipe20', 20, (1, 1, 2, 2, 3), 0.4), ('idle18', 18, (2, 4, 6, 8), 0.6)]:
    base = rng.integers(300, VMAX, 4).tolist()
    cands = []
    for _ in range(nc):
        L = int(rng.choice(lens))
        k = min(L - 1, int(rng.integers(0, 2))) if rng.random() < shared else 0
        cands.append(base[:k] + rng.integers(300, VMAX, L - k).tolist())
    delta = rng.integers(300, VMAX, 3).tolist()
    pmd = np.concatenate([pm, np.ones(3, bool)])
    def old():
        o = old_run([delta], P, st, pm, True)
        st2 = {k: o['present_' + k] for k in stn}
        old_run(cands, P + 3, st2, pmd, False)
    f, n, u = tree_feeds(st, pm, P, delta, cands)
    def new():
        sn.run(['edge_lp', 'bnd', 'last_lp'] + [x for x in outs_n if x.startswith('present_')], f)
    cells = 3 + len(cands) * max(map(len, cands))
    scen[name] = (med(old), med(new), cells, n, u)
    print(f'{name:9s} old {scen[name][0]:6.1f} ms (cells {cells})  tree {scen[name][1]:6.1f} ms (nodes {n}, head rows {u})', flush=True)
