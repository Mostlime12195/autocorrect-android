"""Tree graph (tree_graph.py) vs batched step graph: same log P(cont + boundary | ctx) for shared-context candidates.
  .venv/bin/python android_export/perf/tree_check.py OLD.ort NEW.ort [--K 7]
Also times one word's work both ways (old: prefix run + padded batch; new: one tree call).
"""
import sys, time
import numpy as np
import onnxruntime as ort

old_p, new_p = sys.argv[1], sys.argv[2]
thr = int(sys.argv[3]) if len(sys.argv) > 3 else 2
def sess(p):
    so = ort.SessionOptions(); so.intra_op_num_threads = thr; so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    return ort.InferenceSession(p, so, providers=['CPUExecutionProvider'])
so_, sn = sess(old_p), sess(new_p)
ins = {i.name: i for i in so_.get_inputs()}
outs_o = [o.name for o in so_.get_outputs()]; outs_n = [o.name for o in sn.get_outputs()]
stn = [n[5:] for n in ins if n.startswith('past_') and n != 'past_mask']
K = None
def fresh():
    st = {n: np.zeros([d if isinstance(d, int) else 1 for d in ins['past_' + n].shape], np.float32) for n in stn}
    return st, np.zeros(1, bool)
cshape = [d for d in ins['past_' + [n for n in stn if n[0] == 'c'][0]].shape]
K = cshape[1] + 1
VMAX = sn.get_outputs()[[o.name for o in sn.get_outputs()].index('last_lp')].shape[1] - 1

def old_run(rows, p, st, pm, keep):
    b = len(rows); n = max(map(len, rows))
    ids = np.zeros((b, n), np.int64); nx = np.zeros((b, n), np.int64)
    for r, row in enumerate(rows):
        ids[r, :len(row)] = row; nx[r, :len(row) - 1] = row[1:]
    f = {'ids': ids, 'next_ids': nx, 'pos': np.arange(p, p + n, dtype=np.int64), 'past_mask': pm, **{'past_' + k: v for k, v in st.items()}}
    o = dict(zip(outs_o, so_.run(None, f)))
    return o

def old_score(ctx, cands):
    st, pm = fresh()
    o = old_run([ctx], 0, st, pm, True)
    st = {k: o['present_' + k] for k in stn}; pm = np.concatenate([pm, np.ones(len(ctx), bool)]); last = o['last_lp'][0]
    o2 = old_run(cands, len(ctx), st, pm, False)
    res = []
    for b, t in enumerate(cands):
        s = last[t[0]] + sum(o2['tok_lp'][b, j - 1] for j in range(1, len(t))) + o2['bnd'][b, len(t) - 1]
        res.append(s)
    return np.array(res)

def tree_inputs(trunk, seqs):
    """trunk: tokens (chain), seqs: branches after the trunk. Returns feeds pieces + per-seq (edges, bnd node)."""
    toks, par, depth = [], [], []
    for j, t in enumerate(trunk):
        toks.append(t); par.append(j - 1); depth.append(j)
    children = {}
    paths = []
    for s in seqs:
        cur = len(trunk) - 1; path = []
        for t in s:
            key = (cur, t)
            if key not in children:
                children[key] = len(toks); toks.append(t); par.append(cur); depth.append(depth[cur] + 1 if cur >= 0 else 0)
            cur = children[key]; path.append(cur)
        paths.append(path)
    N = len(toks)
    mask = np.zeros((N, N), bool)
    cidx = np.zeros((N, K), np.int64)
    for i in range(N):
        a = i
        while a >= 0: mask[i, a] = True; a = par[a]
        # conv window: full idx of self = K-1+i; walk ancestors, then the past tail (K-2, K-3, ...)
        x = K - 1 + i
        for k in range(K - 1, -1, -1):
            cidx[i, k] = x
            if x >= K - 1:
                pi = par[x - (K - 1)]
                x = K - 1 + pi if pi >= 0 else K - 2
            else:
                x = x - 1
    return np.array(toks, np.int64), np.array(depth, np.int64), mask, cidx, paths

def tree_score(ctx, cands, p_cached=0):
    st, pm = fresh()
    trunk = ctx  # no cache: whole context is the trunk
    toks, depth, mask, cidx, paths = tree_inputs(trunk, cands)
    T = len(trunk)
    head, edges_n, edges_t, bnd_nodes = {}, [], [], []
    def h(i):
        if i not in head: head[i] = len(head)
        return head[i]
    req = []
    for path, t in zip(paths, cands):
        e = []
        prev = T - 1
        for q, node in enumerate(path):
            e.append(len(edges_n)); edges_n.append(h(prev)); edges_t.append(t[q]); prev = node
        req.append((e, h(path[-1])))
    hl = h(T - 1)
    hn = np.array(sorted(head, key=head.get), np.int64)
    f = {'ids': toks[None], 'pos': depth, 'past_mask': pm, **{'past_' + k: v for k, v in st.items()},
         'tree_mask': mask, 'conv_idx': cidx, 'head_nodes': hn, 'edge_node': np.array(edges_n, np.int64),
         'edge_tok': np.array(edges_t, np.int64), 'last_node': np.array([hl], np.int64)}
    o = dict(zip(outs_n, sn.run(None, f)))
    return np.array([o['edge_lp'][e].sum() + o['bnd'][bn] for e, bn in req]), o, f

rng = np.random.default_rng(1)
maxd = 0
for trial in range(6):
    ctx = [2] + rng.integers(300, VMAX, int(rng.integers(3, 30))).tolist()
    nc = int(rng.integers(2, 15))
    base = rng.integers(300, VMAX, 3).tolist()
    cands = []
    for _ in range(nc):   # shared prefixes like real candidates
        L = int(rng.integers(1, 5))
        c = base[:int(rng.integers(0, 3))] + rng.integers(300, VMAX, L).tolist()
        cands.append(c[:max(1, L)])
    a = old_score(ctx, cands)
    b, _, _ = tree_score(ctx, cands)
    d = np.abs(a - b).max(); maxd = max(maxd, d)
    print(f'trial {trial}: ctx {len(ctx)} cands {nc} max |d| {d:.2e}  argmax same {a.argmax() == b.argmax()}')
print('MAX', maxd)
