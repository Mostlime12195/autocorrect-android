"""Step-graph latency microbench (same call shapes as the keyboard's NeuralScorer).

  .venv/bin/python android_export/perf/microbench.py out/m2s.step.int4m.ort [--threads 2] [--cfg k=v ...] [--new]
"""
import argparse, json, os, time
import numpy as np
import onnxruntime as ort

ap = argparse.ArgumentParser()
ap.add_argument('graph')
ap.add_argument('--threads', type=int, default=2)
ap.add_argument('--cfg', nargs='*', default=[])
ap.add_argument('--reps', type=int, default=20)
ap.add_argument('--opt', default='none')
ap.add_argument('--shapes', default='1x1,1x3,1x24,12x4,12x6,24x5')
ap.add_argument('--ctx', type=int, default=24)
a = ap.parse_args()

so = ort.SessionOptions()
so.intra_op_num_threads = a.threads
so.inter_op_num_threads = 1
so.graph_optimization_level = {'none': ort.GraphOptimizationLevel.ORT_DISABLE_ALL, 'all': ort.GraphOptimizationLevel.ORT_ENABLE_ALL}[a.opt]
for kv in a.cfg:
    k, v = kv.split('=', 1); so.add_session_config_entry(k, v)
t = time.time()
s = ort.InferenceSession(a.graph, so, providers=['CPUExecutionProvider'])
print(f'load {1e3 * (time.time() - t):.0f} ms', a.threads, a.cfg)
ins = {i.name: i for i in s.get_inputs()}
outs = [o.name for o in s.get_outputs()]
new = 'sel' in ins
stn = [n[5:] for n in ins if n.startswith('past_') and n != 'past_mask']
rng = np.random.default_rng(0)


def fresh():
    st = {}
    for n in stn:
        shp = [d if isinstance(d, int) else 1 for d in ins['past_' + n].shape]
        st[n] = np.zeros(shp, np.float32)
    return st, np.zeros(1, bool)


def feeds(B, N, P, st, pm):
    ids = rng.integers(300, 20000, (B, N)).astype(np.int64)
    nxt = np.roll(ids, -1, 1)
    f = {'ids': ids, 'next_ids': nxt, 'pos': np.arange(P, P + N, dtype=np.int64), 'past_mask': pm}
    f.update({'past_' + n: v for n, v in st.items()})
    if new:
        f['sel'] = np.arange(B * N, dtype=np.int64)
    return f


# context state of a.ctx tokens
st, pm = fresh()
o = s.run(None, feeds(1, a.ctx, 0, st, pm))
pres = dict(zip(outs, o))
st = {n: pres['present_' + n] for n in stn}
pm = np.concatenate([pm, np.ones(a.ctx, bool)])
P = a.ctx + 1
want_b = [n for n in outs if not n.startswith('present_') and n != 'last_lp']
res = {}
for shp in a.shapes.split(','):
    B, N = map(int, shp.split('x'))
    f = feeds(B, N, P, st, pm)
    keep = B == 1
    want = outs if keep else want_b
    for _ in range(3): s.run(want, f)
    ts = []
    for _ in range(a.reps):
        t = time.perf_counter(); s.run(want, f); ts.append(time.perf_counter() - t)
    res[shp] = 1e3 * float(np.median(ts))
print(json.dumps({k: round(v, 1) for k, v in res.items()}))
