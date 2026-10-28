"""Writes the example subnetwork as two TSV files (nodes, edges) with a seeded spring layout.

Subnetwork: CRP, the given sugar-specific regulators (default AraC, XylR, GalS, RhaS, LacI) and all their
target genes; induced subgraph of the gene-level network built by trn.load (RegulonDB 10.7, network_tf_gene.txt).
Node attribute regulator: 'global' for crp, 'local' for every other gene that encodes a transcription factor of the
full network, '' otherwise. Edge attribute effect: activator / repressor / dual / unknown.
"""
import collections
import sys

import networkx as nx

sys.path.insert(0, sys.argv[2])
import trn  # noqa: E402

E, names = trn.load(sys.argv[1])
outdir = sys.argv[3]
out = collections.defaultdict(set)
for (a, b) in E:
    if a != b:
        out[a].add(b)
regulators_full = set(a for a, b in E)

SEED_REGS = sys.argv[4].split(',') if len(sys.argv) > 4 else ['araC', 'xylR', 'galS', 'rhaS', 'lacI']
nodes = {'crp'} | set(SEED_REGS)
for r in SEED_REGS:
    nodes |= out[r]
edges = {(a, b): e for (a, b), e in E.items() if a in nodes and b in nodes}
print('nodes', len(nodes), 'edges', len(edges), 'self-loops', sum(1 for a, b in edges if a == b))
print('global regulators of the full network in the subnetwork:',
      [g for g in trn.GLOBAL if g in nodes])

G = nx.DiGraph()
G.add_nodes_from(sorted(nodes))
G.add_edges_from((a, b) for (a, b) in edges if a != b)
pos = nx.spring_layout(G.to_undirected(), seed=7, k=0.35, iterations=200)
xs = [p[0] for p in pos.values()]
ys = [p[1] for p in pos.values()]
minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)


def sx(v):
    return round(60 + (v - minx) / (maxx - minx) * 1100, 1)


def sy(v):
    return round(60 + (v - miny) / (maxy - miny) * 800, 1)


with open(outdir + '/trn_nodes.tsv', 'w') as f:
    f.write('id\tlabel\tregulator\tx\ty\n')
    for n in sorted(nodes):
        cls = 'global' if n == 'crp' else ('local' if n in regulators_full else '')
        f.write(f'{n}\t{n}\t{cls}\t{sx(pos[n][0])}\t{sy(pos[n][1])}\n')
with open(outdir + '/trn_edges.tsv', 'w') as f:
    f.write('source\ttarget\teffect\n')
    for (a, b), e in sorted(edges.items()):
        f.write(f'{a}\t{b}\t{e}\n')
cls = collections.Counter('global' if n == 'crp' else ('local' if n in regulators_full else 'gene') for n in nodes)
print('classes', cls)
print('regulators with targets in the subnetwork:',
      sorted(a for a in set(a for (a, b) in edges if a != b)))
