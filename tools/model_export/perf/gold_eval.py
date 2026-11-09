"""Decision-level check of a tree graph against the batched graph (and an fp32-activation reference) on the eval set.

corrector.decode (system e params, re-check on) over the first N messages of data/eval/test.json with
  old  : pipeline_ref.OnnxScorer on the batched step graph (what the app runs today)
  tree : TreeScorer below (port of NeuralScorer's tree path in the Android engine) on the tree graph
  ref  : optional, OnnxScorer on another graph (e.g. web/dist/m2s.step.int4.onnx = exact int4 weights, fp32 activations)
Prints word accuracy vs gold and how many decisions differ between the scorers.

  .venv/bin/python android_export/perf/gold_eval.py --model m2s --old android_export/out/m2s.step.int4m.ort \
      --tree android_export/work/m2s.step.int4t.ort [--ref web/dist/m2s.step.int4.onnx] --n 100
"""
import argparse, json, os, sys, time
from collections import OrderedDict
import numpy as np
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, ROOT); sys.path.insert(0, os.path.join(ROOT, 'web'))
import pipeline_ref as R
from corrector import decode, load_set
import onnxruntime as ort
import sentencepiece as spm

ap = argparse.ArgumentParser()
ap.add_argument('--model', default='m2s')
ap.add_argument('--old', required=True)
ap.add_argument('--tree', required=True)
ap.add_argument('--ref', default='')
ap.add_argument('--n', type=int, default=100)
ap.add_argument('--threads', type=int, default=2)
ap.add_argument('--out', default='')
a = ap.parse_args()
D = f'{ROOT}/web/dist'
meta, params = json.load(open(f'{D}/models.json')), json.load(open(f'{D}/params.json'))
mt = meta[a.model]
sp = spm.SentencePieceProcessor(model_file=f"{ROOT}/{ {'vocab.json': 'data/tokenizer.model', 'vocab_m2.json': 'data/m2tok/tokenizer.model'}[mt['vocab']]}")
MAX_CTX, EOT = R.MAX_CTX, R.EOT


class TreeScorer:
    """NeuralScorer tree path: the shared context's delta and every candidate in one call; state cached per context."""
    def __init__(self, sess, meta, cache_size=16):
        self.s, self.meta, self.names = sess, meta, meta['state_names']
        self.K = meta['conv_kernel']
        self.cache, self.cache_size = OrderedDict(), cache_size

    def enc(self, s):
        return [sp.eos_id()] + (sp.encode(s) if s else [])

    def fresh(self):
        m = self.meta
        st = {n: (np.zeros((1, m['n_kv_heads'], 1, m['head_dim']), np.float32) if n[0] in 'kv'
                  else np.zeros((1, self.K - 1, m['d_model']), np.float32)) for n in self.names}
        return st, np.zeros(1, bool), None

    def cached(self, toks):
        t = tuple(toks)
        if t in self.cache:
            self.cache.move_to_end(t); return t, self.cache[t]
        best = None
        for k in self.cache:
            if len(k) < len(t) and t[:len(k)] == k and (best is None or len(k) > len(best)): best = k
        return (best, self.cache[best]) if best is not None else (None, None)

    def tree(self, toks, base, branches):
        bk, bst = base
        b0 = len(bk) if bk is not None else 0
        st, pm, last = bst if bst is not None else self.fresh()
        T = len(toks) - b0
        ntok, npar = list(toks[b0:]), list(range(-1, T - 1))
        root = T - 1
        child, head, en, et, bedges, bbnd = {}, {}, [], [], [], []
        def h(i):
            if i not in head: head[i] = len(head)
            return head[i]
        for seq, tf, bnd in branches:
            cur, e = root, []
            for q, t in enumerate(seq):
                if q >= tf:
                    if cur < 0: e.append(-1 - t)
                    else: e.append(len(en)); en.append(h(cur)); et.append(t)
                key = (cur, t)
                if key not in child:
                    child[key] = len(ntok); ntok.append(t); npar.append(cur)
                cur = child[key]
            bedges.append(e); bbnd.append(h(cur) if bnd else -1)
        last_row = h(T - 1) if T > 0 else 0
        N, K = len(ntok), self.K
        depth = np.zeros(N, np.int64); mask = np.zeros((N, N), bool); cidx = np.zeros((N, K), np.int64)
        for i in range(N):
            p = npar[i]; depth[i] = 0 if p < 0 else depth[p] + 1
            x = i
            while x >= 0: mask[i, x] = True; x = npar[x]
            x = K - 1 + i
            for q in range(K - 1, -1, -1):
                cidx[i, q] = x
                if x >= K - 1:
                    pp = npar[x - (K - 1)]; x = K - 1 + pp if pp >= 0 else K - 2
                else:
                    x -= 1
        f = {'ids': np.array([ntok], np.int64), 'pos': depth + b0, 'past_mask': pm, 'tree_mask': mask, 'conv_idx': cidx,
             'head_nodes': np.array(sorted(head, key=head.get), np.int64), 'edge_node': np.array(en, np.int64),
             'edge_tok': np.array(et, np.int64), 'last_node': np.array([last_row], np.int64),
             **{'past_' + n: v for n, v in st.items()}}
        names = ['edge_lp', 'bnd', 'last_lp'] + ['present_' + n for n in self.names]
        o = dict(zip(names, self.s.run(names, f)))
        new = None
        if T > 0:
            P = len(pm)
            ns = {}
            for n in self.names:
                v = o['present_' + n]
                ns[n] = v[:, :, :P + T] if n[0] in 'kv' else v[:, T:T + K - 1]
            new = (ns, np.concatenate([pm, np.ones(T, bool)]), o['last_lp'][0])
        scores = []
        for e, b in zip(bedges, bbnd):
            s = 0.0
            for x in e: s += float(last[-1 - x]) if x < 0 else float(o['edge_lp'][x])
            if b >= 0: s += float(o['bnd'][b])
            scores.append(s)
        return new, np.array(scores)

    def score(self, reqs):
        keys = []
        for left, cont, *_ in reqs:
            eot = cont.endswith(EOT)
            if eot: cont = cont[:-1]
            lt, ft = self.enc(left), self.enc(left + cont)
            k, n = 0, min(len(lt), len(ft) - 1)
            while k < n and lt[k] == ft[k]: k += 1
            keys.append((ft[:k][-MAX_CTX:], ft[k:] + ([sp.eos_id()] if eot else []), eot))
        ctxs = [c for c, _, _ in keys]
        P = 0
        while all(len(c) > P for c in ctxs) and len({c[P] for c in ctxs}) == 1: P += 1
        shared = ctxs[0][:P]
        bk, bst = self.cached(shared)
        exact = bk is not None and len(bk) == len(shared)
        new, sc = self.tree(shared, (bk, bst), [(c[P:] + t, len(c) - P, not e) for c, t, e in keys])
        if not exact:
            self.cache[tuple(shared)] = new
            while len(self.cache) > self.cache_size: self.cache.popitem(last=False)
        return sc


def sess(p):
    so = ort.SessionOptions(); so.intra_op_num_threads = a.threads
    so.add_session_config_entry('session.intra_op.allow_spinning', '0')
    if p.endswith('.int4.onnx'): so.add_session_config_entry('session.disable_quant_qdq', '1')
    return ort.InferenceSession(p, so, providers=['CPUExecutionProvider'])


msgs = load_set(f'{ROOT}/data/eval/test.json')[:a.n]
p = params[a.model]['e']
def run(make):
    t0 = time.time(); outs = []
    for m in msgs:
        outs.append(decode([m], make(), p['lam'], p['bias'], margin=p.get('margin'), lam_r=p.get('lam_r'))[0])
    return outs, time.time() - t0
gold = [[w['gold'] for w in m['words']] for m in msgs]
res = {}
old_step = R.Step(a.old, mt, threads=a.threads)
res['old'] = run(lambda: R.OnnxScorer(sp, old_step))
for tp in a.tree.split(','):
    ts = sess(tp)
    res['tree' if ',' not in a.tree else 'tree:' + os.path.basename(tp)] = run(lambda: TreeScorer(ts, mt))
if a.ref:
    ref_step = R.Step(a.ref, mt, threads=a.threads)
    res['ref'] = run(lambda: R.OnnxScorer(sp, ref_step))
nw = sum(map(len, gold))
for k, (o, dt) in res.items():
    acc = sum(x == y for oo, gg in zip(o, gold) for x, y in zip(oo, gg))
    print(f'{k}: word acc {100 * acc / nw:.2f}% ({acc}/{nw}), {dt:.0f}s')
ks = list(res)
for i in range(len(ks)):
    for j in range(i + 1, len(ks)):
        d = [(mi, wi, x, y) for mi, (oa, ob) in enumerate(zip(res[ks[i]][0], res[ks[j]][0])) for wi, (x, y) in enumerate(zip(oa, ob)) if x != y]
        print(f'{ks[i]} vs {ks[j]}: {len(d)} of {nw} decisions differ', d[:8])
if a.out: json.dump({k: v[0] for k, v in res.items()}, open(a.out, 'w'))
