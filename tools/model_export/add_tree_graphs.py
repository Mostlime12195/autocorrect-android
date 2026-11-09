"""Add "graph_tree" entries to an Android model manifest (run after android_export/make_manifest.py).

Apps with the tree scorer (NeuralScorer tree path) load graph_tree when a model has one; older apps only read
"graph", so the manifest stays valid for them. Tree files are matched by name: <graph stem>t.ort, e.g.
m2s.step.int4m.ort -> m2s.step.int4t.ort, lm_block4.step.int8.ort -> lm_block4.step.int8t.ort.

  python3 tools/model_export/add_tree_graphs.py manifest.json DIR_WITH_TREE_ORT_FILES [--out manifest.json]
"""
import argparse, hashlib, json, os

TREE = {'m2s.step.int4m.ort': 'm2s.step.int4t.ort', 'm2.step.int4m.ort': 'm2.step.int4t.ort',
        'lm_block4.step.int8.ort': 'lm_block4.step.int8t.ort'}

ap = argparse.ArgumentParser()
ap.add_argument('manifest')
ap.add_argument('dir')
ap.add_argument('--out', default='')
a = ap.parse_args()
man = json.load(open(a.manifest))
for m in man['models']:
    t = TREE.get(m['graph']['file'])
    if not t or not os.path.exists(os.path.join(a.dir, t)):
        m.pop('graph_tree', None)
        continue
    p = os.path.join(a.dir, t)
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    m['graph_tree'] = dict(file=t, size=os.path.getsize(p), sha256=h.hexdigest())
    print(m['id'], '->', m['graph_tree'])
json.dump(man, open(a.out or a.manifest, 'w'), indent=1)
