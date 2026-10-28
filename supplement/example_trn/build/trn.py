"""Builds the gene-level E. coli transcription network from RegulonDB network_tf_gene.txt and reports motifs.

Model: one node per gene; a transcription factor (TF) is merged with the target gene of the same name
(AraC -> araC, CRP -> crp, H-NS -> hns); a TF without such a gene (complexes such as IHF, FlhDC) stays a node
of its own, named by the TF. An edge X -> y means that the TF encoded by X regulates gene y. The effect of a pair
listed as activator and repressor is 'dual'.
"""
import collections
import sys

GLOBAL = ['crp', 'fnr', 'IHF', 'fis', 'arcA', 'narL', 'lrp']  # Martinez-Antonio & Collado-Vides 2003


def load(path):
    rows = [l.rstrip('\n').split('\t') for l in open(path, encoding='latin1')
            if not l.startswith('#') and l.strip()]
    genes = set(r[1] for r in rows)

    def node_of(tf):
        for c in (tf[0].lower() + tf[1:], tf.lower(), tf.replace('-', '').lower()):
            if c in genes:
                return c
        return tf

    eff = collections.defaultdict(set)
    tf_name = {}
    for r in rows:
        a = node_of(r[0])
        tf_name[a] = r[0]
        eff[(a, r[1])].add(r[2])
    E = {}
    for (a, b), s in eff.items():
        s2 = s - {'unknown'}
        E[(a, b)] = 'dual' if len(s2) > 1 else (next(iter(s2)) if s2 else 'unknown')
    return E, tf_name


def stats(E):
    out = collections.defaultdict(set)
    for (a, b) in E:
        if a != b:
            out[a].add(b)
    nodes = set(a for a, b in E) | set(b for a, b in E)
    print('nodes', len(nodes), 'edges', len(E), 'self-loops', sum(1 for a, b in E if a == b),
          'regulators', len(set(a for a, b in E)))
    print('2-cycles', sum(1 for (a, b) in E if a < b and (b, a) in E))
    ffl = [(x, y, z) for x in out for y in out[x] for z in out.get(y, ()) if z in out[x] and len({x, y, z}) == 3]
    print('FFLs', len(ffl))
    print('FFL by X:', collections.Counter(x for x, y, z in ffl).most_common(10))
    print('global out-degrees:', [(g, len(out.get(g, ()))) for g in GLOBAL])
    print('top out-degree:', sorted(((len(out[r]), r) for r in out), reverse=True)[:12])
    sib = sum(len(out[r]) * (len(out[r]) - 1) // 2 for r in out if r not in GLOBAL)
    print('sibling pairs under non-global regulators', sib)
    xy = collections.Counter((x, y) for x, y, z in ffl)
    print('(X,Y) with >=2 Z:', sum(1 for v in xy.values() if v >= 2),
          'pairs of FFLs sharing X and Y:', sum(v * (v - 1) // 2 for v in xy.values()))
    print('CRP FFL middles:', collections.Counter(y for x, y, z in ffl if x == 'crp').most_common(45))
    return out, ffl


if __name__ == '__main__':
    E, names = load(sys.argv[1])
    stats(E)
