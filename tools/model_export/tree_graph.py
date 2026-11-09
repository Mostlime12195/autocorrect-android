"""Tree ("packed") variant of a step graph for the Android keyboard: one call scores every candidate of a word.

The keyboard scores ~5-20 candidates that share a left context. The batched step graph runs them as B padded rows
and computes the full 32k-vocab head + log-softmax at every padded position, and the context itself needs a second
call. This graph instead takes ONE row of N tokens laid out as a token tree (trie of context delta + candidates):

  * attention: the causal tril mask (one Trilu per attention layer) becomes the input tree_mask[N, N]
    (token i sees token j iff j is i itself or an ancestor). Past slots stay as before (past_mask).
  * short conv: Conv over the (past tail ++ new tokens) buffer becomes Gather(conv_idx[N, K]) * w -> sum, so each
    token convolves over its own ancestors (conv_idx = full-buffer indices: tail slots 0..K-2, token j at K-1+j).
  * RoPE: pos[N] is already per token (depth in the tree).
  * head: only on the rows that need it (head_nodes[U] = token indices), as before (same MatMulNBits / int8 kernel).
    Outputs per edge (edge_node[E] index into head_nodes, edge_tok[E] next token): edge_lp = log p(tok | node);
    bnd[U] = log P(word boundary after node); last_lp[1, V] = full log-probs at head row last_node (context cache).
    The normaliser is exact: logits - max -> Exp -> MatMul with [ones, non-continuation] gives both sums at once
    (replaces LogSoftmax + Where + ReduceLogSumExp over [B, N, V]).
  * present_k/v: [1, nkv, P+N, hd] (all new tokens; the caller keeps the trunk prefix), present_c: the whole conv
    buffer [1, K-1+N, d] (the caller takes the K-1 slots ending at the trunk's last token).

A chain (tree_mask = tril, conv_idx = sliding windows) is exactly the old graph, so the same file also serves the
context-prefix runs. Works for the int4m (MatMulNBits) and int8 (DynamicQuantizeLinear + MatMulInteger) graphs.

Pipeline (autocorrect repo; a copy of these scripts lives in autocorrect-android/tools/model_export/):
  .venv/bin/python android_export/int4_head.py web/dist/m2s.step.int4.onnx work/m2s.step.int4m.onnx
  .venv/bin/python android_export/tree_graph.py work/m2s.step.int4m.onnx work/m2s.step.int4t.onnx
  .venv/bin/python android_export/to_ort.py work/m2s.step.int4t.onnx android_export/out/m2s.step.int4t.ort
  34M: tree_graph.py web/dist/lm_block4.step.int8.onnx -> to_ort.py -> lm_block4.step.int8t.ort
Checks: perf/tree_check.py (tree vs batched graph on random tokens), perf/tree_time.py (latency), perf/gold_eval.py
(decisions + word accuracy on data/eval/test.json).
"""
import collections, sys
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
inits = {i.name: i for i in g.initializer}
prod = {o: n for n in g.node for o in n.output}


def cons_of(name):
    return [n for n in g.node if name in n.input]


def rename_uses(old, new):
    for n in g.node:
        for k, i in enumerate(n.input):
            if i == old: n.input[k] = new


new_nodes = []   # appended at the end, then the graph is topologically sorted
new_inits = []


def node(op, ins, outs, name, **kw):
    n = helper.make_node(op, ins, outs, name=name, **kw)
    new_nodes.append(n)
    return n


def const(name, arr):
    new_inits.append(numpy_helper.from_array(np.asarray(arr), name))
    return name


# ---- 1. attention masks: Trilu(ones(N, N)) -> tree_mask
trilus = [n for n in g.node if n.op_type == 'Trilu']
assert trilus
for t in trilus:
    rename_uses(t.output[0], 'tree_mask')
    g.node.remove(t)

# ---- 2. short convs -> gather over conv_idx
convs = [n for n in g.node if n.op_type == 'Conv']
present_c = {}
for ci, cv in enumerate(convs):
    tr_in = prod[cv.input[0]]
    assert tr_in.op_type == 'Transpose'
    full = tr_in.input[0]                                   # [B, K-1+N, d]
    w = numpy_helper.to_array(inits[cv.input[1]])          # [d, 1, K]
    d, _, K = w.shape
    wk = const(f'tree.conv{ci}.w', np.ascontiguousarray(w[:, 0, :].T).astype(np.float32))   # [K, d]
    outs = cons_of(cv.output[0])
    assert len(outs) == 1 and outs[0].op_type == 'Transpose'
    tr_out = outs[0]
    p = f'/tree/conv{ci}'
    node('Gather', [full, 'conv_idx'], [p + '_g'], p + '_g', axis=1)            # [B, N, K, d]
    node('Mul', [p + '_g', wk], [p + '_m'], p + '_m')
    node('ReduceSum', [p + '_m', const(p + '_ax', np.array([2], np.int64))], [p + '_y'], p + '_y', keepdims=0)
    rename_uses(tr_out.output[0], p + '_y')
    g.node.remove(cv); g.node.remove(tr_out)
    if not cons_of(tr_in.output[0]): g.node.remove(tr_in)
    present_c[full] = ci

# present_c*: the whole conv buffer instead of its last K-1 slots
for o in g.output:
    if o.name.startswith('present_c'):
        sl = prod[o.name]
        assert sl.op_type == 'Slice' and sl.input[0] in present_c, sl
        g.node.remove(sl)
        node('Identity', [sl.input[0]], [o.name], '/tree/' + o.name)

# ---- 3. head on head_nodes only, edge log-probs
ls = [n for n in g.node if n.op_type == 'LogSoftmax']
assert len(ls) == 1
ls = ls[0]
Z = ls.input[0]                                             # logits [B, N, V]
hidden = '/final_norm/Mul_1_output_0'
assert hidden in prod, 'final hidden state not found'
# vocabulary / model dims from the cont mask and the conv weight
where = [n for n in g.node if n.op_type == 'Where' and ls.output[0] in n.input]
assert len(where) == 1
cont = numpy_helper.to_array(inits[where[0].input[0]]).astype(bool)   # [V] True = continuation token
V = cont.shape[0]
# drop the old head tail (LogSoftmax, GatherElements, Gather -> tok_lp, Where, ReduceLogSumExp, Gather -> last_lp)
old_tail = set()
stack = [ls]
while stack:
    n = stack.pop()
    if n.name in old_tail: continue
    old_tail.add(n.name)
    for o in n.output: stack.extend(cons_of(o))
for n in [n for n in g.node if n.name in old_tail]: g.node.remove(n)
# also the next_ids Unsqueeze feeding GatherElements
for n in [n for n in g.node if 'next_ids' in n.input]: g.node.remove(n)

# hidden rows of head_nodes: Reshape [B, N, d] -> [B*N, d] -> Gather
node('Shape', [hidden], ['/tree/hshape'], '/tree/hshape', start=2)
node('Concat', [const('/tree/m1', np.array([-1], np.int64)), '/tree/hshape'], ['/tree/h2shape'], '/tree/h2shape', axis=0)
node('Reshape', [hidden, '/tree/h2shape'], ['/tree/h2'], '/tree/h2_rs')
node('Gather', ['/tree/h2', 'head_nodes'], ['/tree/hs'], '/tree/hs', axis=0)      # [U, d]
rename_uses(hidden, '/tree/hs')
# Z is now [U, V]
opset = max(o.version for o in m.opset_import if o.domain in ('', 'ai.onnx'))
if opset >= 18:
    node('ReduceMax', [Z, const('/tree/axm1', np.array([-1], np.int64))], ['/tree/zmax'], '/tree/zmax', keepdims=1)  # [U, 1]
else:
    node('ReduceMax', [Z], ['/tree/zmax'], '/tree/zmax', axes=[-1], keepdims=1)
node('Sub', [Z, '/tree/zmax'], ['/tree/zc'], '/tree/zc')
node('Exp', ['/tree/zc'], ['/tree/e'], '/tree/e')
wsum = np.stack([np.ones(V, np.float32), (~cont).astype(np.float32)], 1)          # [V, 2]
node('MatMul', ['/tree/e', const('/tree/wsum', wsum)], ['/tree/s'], '/tree/s')       # [U, 2]
node('Log', ['/tree/s'], ['/tree/ls'], '/tree/ls')
node('Slice', ['/tree/ls', const('/tree/i0', np.array([0], np.int64)), const('/tree/i1', np.array([1], np.int64)),
               const('/tree/i1a', np.array([1], np.int64))], ['/tree/lsall'], '/tree/lsall')          # [U, 1]
node('Slice', ['/tree/ls', const('/tree/i1b', np.array([1], np.int64)), const('/tree/i2', np.array([2], np.int64)),
               const('/tree/i1c', np.array([1], np.int64))], ['/tree/lsnc'], '/tree/lsnc')
node('Sub', ['/tree/lsnc', '/tree/lsall'], ['/tree/bnd2'], '/tree/bnd2')
node('Squeeze', ['/tree/bnd2', const('/tree/ax1', np.array([1], np.int64))], ['bnd'], '/tree/bnd')       # [U]
node('Add', ['/tree/zmax', '/tree/lsall'], ['/tree/lse'], '/tree/lse')                                  # [U, 1]
node('Squeeze', ['/tree/lse', const('/tree/ax1b', np.array([1], np.int64))], ['/tree/lse1'], '/tree/lse1')  # [U]
# edges: logits[edge_node, edge_tok] - lse[edge_node]
node('Reshape', [Z, const('/tree/flat', np.array([-1], np.int64))], ['/tree/zf'], '/tree/zf')
node('Mul', ['edge_node', const('/tree/V', np.array(V, np.int64))], ['/tree/en_v'], '/tree/en_v')
node('Add', ['/tree/en_v', 'edge_tok'], ['/tree/eidx'], '/tree/eidx')
node('Gather', ['/tree/zf', '/tree/eidx'], ['/tree/ez'], '/tree/ez', axis=0)
node('Gather', ['/tree/lse1', 'edge_node'], ['/tree/else'], '/tree/else', axis=0)
node('Sub', ['/tree/ez', '/tree/else'], ['edge_lp'], '/tree/edge_lp')
# full log-probs of one head row (context cache)
node('Gather', [Z, 'last_node'], ['/tree/lz'], '/tree/lz', axis=0)              # [1, V]
node('Gather', ['/tree/lse', 'last_node'], ['/tree/llse'], '/tree/llse', axis=0)  # [1, 1]
node('Sub', ['/tree/lz', '/tree/llse'], ['last_lp'], '/tree/last_lp')

g.node.extend(new_nodes)
g.initializer.extend(new_inits)

# ---- I/O
ins = [i for i in g.input if i.name != 'next_ids']
del g.input[:]
g.input.extend(ins)
I64 = TensorProto.INT64
g.input.extend([
    helper.make_tensor_value_info('tree_mask', TensorProto.BOOL, ['N', 'N']),
    helper.make_tensor_value_info('conv_idx', I64, ['N', 'K']),
    helper.make_tensor_value_info('head_nodes', I64, ['U']),
    helper.make_tensor_value_info('edge_node', I64, ['E']),
    helper.make_tensor_value_info('edge_tok', I64, ['E']),
    helper.make_tensor_value_info('last_node', I64, [1]),
])
outs = []
for o in g.output:
    if o.name == 'tok_lp':
        outs.append(helper.make_tensor_value_info('edge_lp', TensorProto.FLOAT, ['E']))
    elif o.name == 'bnd':
        outs.append(helper.make_tensor_value_info('bnd', TensorProto.FLOAT, ['U']))
    elif o.name == 'last_lp':
        outs.append(helper.make_tensor_value_info('last_lp', TensorProto.FLOAT, [1, V]))
    elif o.name.startswith('present_c'):
        outs.append(helper.make_tensor_value_info(o.name, TensorProto.FLOAT, [1, 'KN', o.type.tensor_type.shape.dim[2].dim_value or 'D']))
    else:
        outs.append(o)
del g.output[:]
g.output.extend(outs)
del g.value_info[:]

# ---- topological sort
avail = {i.name for i in g.input} | {i.name for i in g.initializer} | {''}
nodes = list(g.node)
order, pending = [], nodes
while pending:
    rest = []
    for n in pending:
        if all(i in avail for i in n.input):
            order.append(n); avail.update(n.output)
        else:
            rest.append(n)
    assert len(rest) < len(pending), [(n.name, [i for i in n.input if i not in avail]) for n in rest[:5]]
    pending = rest
del g.node[:]
g.node.extend(order)
# drop dead nodes / initializers
needed = {o.name for o in g.output}
live = []
for n in reversed(order):
    if any(o in needed for o in n.output):
        live.append(n); needed.update(n.input)
del g.node[:]
g.node.extend(reversed(live))
keep_init = [i for i in g.initializer if i.name in needed]
del g.initializer[:]
g.initializer.extend(keep_init)
onnx.checker.check_model(m)
onnx.save(m, dst)
print(f'{src} -> {dst}: {len(trilus)} attn masks, {len(convs)} convs, V={V}, nodes {len(g.node)}')
