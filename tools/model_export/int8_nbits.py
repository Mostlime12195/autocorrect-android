"""int8 (quantize_dynamic) step graph -> MatMulNBits bits=8 with accuracy_level 4, same int8 weights.

quantize_dynamic writes DynamicQuantizeLinear -> MatMulInteger -> Cast -> Mul(x_scale * w_scale). The activation
scale is ONE number per call over the whole [B, N, d] tensor, so a candidate's score depends on which other rows (and
padding) share the batch (scores move by up to ~0.5 nat with the batch layout). MatMulNBits accuracy_level 4 quantizes
activations per row and 32-wide block instead (batch-independent, finer), with the same int8 weights (uint8 = q + 128,
the default 8-bit zero point; per-column scale repeated per block) and ARM dotprod kernels.

  .venv/bin/python android_export/int8_nbits.py web/dist/lm_block4.step.int8.onnx android_export/work/lm_block4.step.int8n.onnx
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
inits = {i.name: i for i in g.initializer}
prod = {o: n for n in g.node for o in n.output}
BS = 32


def cons(name):
    return [n for n in g.node if name in n.input]


done = 0
made = set()
for mi in [n for n in g.node if n.op_type == 'MatMulInteger']:
    xq, wq, xzp, wzp = mi.input
    if wq not in inits: continue
    dql = prod[xq]
    assert dql.op_type == 'DynamicQuantizeLinear'
    x = dql.input[0]
    W = numpy_helper.to_array(inits[wq]).astype(np.int16)        # [K, N] int8
    assert not numpy_helper.to_array(inits[wzp]).any(), 'asymmetric weights'
    cast = cons(mi.output[0]); assert len(cast) == 1 and cast[0].op_type == 'Cast'; cast = cast[0]
    mul = cons(cast.output[0]); assert len(mul) == 1 and mul[0].op_type == 'Mul'; mul = mul[0]
    smul = prod[[i for i in mul.input if i != cast.output[0]][0]]
    assert smul.op_type == 'Mul'
    ws_name = [i for i in smul.input if i in inits][0]
    ws = numpy_helper.to_array(inits[ws_name]).astype(np.float32).reshape(-1)   # [N] (or scalar)
    K, N = W.shape
    if ws.size == 1: ws = np.repeat(ws, N)
    assert K % BS == 0, (mi.name, K)
    kb = K // BS
    B = (W.T + 128).astype(np.uint8).reshape(N, kb, BS)
    S = np.repeat(ws[:, None], kb, axis=1).reshape(-1).astype(np.float32)
    bn, sn = wq + '.nbits8', wq + '.nbits8_scale'
    if bn not in made:   # weights shared by several MatMuls (deduplicated initializers) are converted once
        g.initializer.extend([numpy_helper.from_array(B, bn), numpy_helper.from_array(S, sn)])
        made.add(bn)
    nb = helper.make_node('MatMulNBits', [x, bn, sn], [mul.output[0]], name=mi.name + '_nbits8', domain='com.microsoft',
                          K=K, N=N, bits=8, block_size=BS, accuracy_level=4)
    g.node.insert(list(g.node).index(mi), nb)
    for n in (mi, cast, mul): g.node.remove(n)
    if not cons(smul.output[0]): g.node.remove(smul)
    if not any(cons(o) for o in dql.output): g.node.remove(dql)
    done += 1
used = {i for n in g.node for i in n.input}
keep = [i for i in g.initializer if i.name in used]
del g.initializer[:]
g.initializer.extend(keep)
if not any(o.domain == 'com.microsoft' for o in m.opset_import):
    m.opset_import.append(helper.make_opsetid('com.microsoft', 1))
onnx.checker.check_model(m)
onnx.save(m, dst)
print(f'{src} -> {dst}: {done} MatMulInteger -> MatMulNBits bits=8 (block {BS}, accuracy_level 4)')
