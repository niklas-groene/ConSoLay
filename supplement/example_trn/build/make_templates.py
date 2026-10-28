"""Writes the example library for transcription networks: three pattern templates and a rule set
(format cbl-template / cbl-rules, formatVersion 1, as written by TemplateCodec)."""
import json
import os
import sys

OUT = sys.argv[1]
COORD = ("local: origin = top-left corner of the pattern's bounding box when the template was made; x to the right, "
         "y downwards, in px; node positions are centres. Positions in constraint definitions use the same coordinates; "
         "applying a template moves them with the instance.")


def node(i, label, x, y, conditions=(), w=70.0, h=26.0):
    return {"id": i, "label": label, "role": "", "x": x, "y": y, "width": w, "height": h,
            "conditions": list(conditions)}


def edge(i, s, t, conditions=()):
    return {"id": i, "source": s, "target": t, "directed": True, "role": "", "conditions": list(conditions)}


def cond(attr, op, values=()):
    return {"attribute": attr, "op": op, "values": list(values)}


def template(tid, name, desc, nodes, edges, ports, constraints):
    return {"format": "cbl-template", "formatVersion": 1, "id": tid, "revision": 1, "name": name,
            "description": desc, "kind": "pattern", "source": "file", "coordinates": COORD,
            "nodes": nodes, "edges": edges, "ports": ports, "constraints": constraints}


def ref(t):
    return {"id": t["id"], "revision": t["revision"], "name": t["name"]}


regulation = template(
    "trn:regulation", "Regulation",
    "A transcription factor and a gene it regulates: the regulator above the target "
    "(Shen-Orr et al. 2002 draw regulating transcription factors above the regulated operons).",
    [node("n1", "Regulator", 35, 13), node("n2", "Target", 35, 93)],
    [edge("e1", "n1", "n2")],
    [{"id": "p1", "name": "Regulator", "node": "n1"}, {"id": "p2", "name": "Target", "node": "n2"}],
    [{"id": "k1", "type": "SEPARATION_Y", "name": "Regulator above target", "priority": "NORMAL", "enabled": True,
      "roles": {"first": ["n1"], "second": ["n2"]}, "params": {"axis": "y", "distance": 70.0, "exact": False}}])

shared = template(
    "trn:shared-regulator", "Shared regulator",
    "Two genes regulated by the same local (non-global) regulator lie in one row; applied to all pairs, "
    "the targets of a local regulator form a row.",
    [node("n1", "Regulator", 85, 13, [cond("trn.regulator", "equals", ["local"])]),
     node("n2", "Target A", 35, 93), node("n3", "Target B", 135, 93)],
    [edge("e1", "n1", "n2"), edge("e2", "n1", "n3")],
    [{"id": "p1", "name": "Regulator", "node": "n1"}, {"id": "p2", "name": "Target A", "node": "n2"},
     {"id": "p3", "name": "Target B", "node": "n3"}],
    [{"id": "k1", "type": "ALIGN_MIDDLE", "name": "Targets in one row", "priority": "NORMAL", "enabled": True,
      "roles": {"nodes": ["n2", "n3"]}, "params": {"axis": "y", "anchor": "middle"}}])

mutual = template(
    "trn:mutual-regulation", "Mutual regulation",
    "Two regulators that regulate each other lie side by side in one row instead of one above the other.",
    [node("n1", "A", 35, 13), node("n2", "B", 135, 13)],
    [edge("e1", "n1", "n2"), edge("e2", "n2", "n1")],
    [{"id": "p1", "name": "A", "node": "n1"}, {"id": "p2", "name": "B", "node": "n2"}],
    [{"id": "k1", "type": "ALIGN_MIDDLE", "name": "Side by side", "priority": "NORMAL", "enabled": True,
      "roles": {"nodes": ["n1", "n2"]}, "params": {"axis": "y", "anchor": "middle"}},
     {"id": "k2", "type": "SEPARATION_X", "name": "A left of B", "priority": "NORMAL", "enabled": True,
      "roles": {"first": ["n1"], "second": ["n2"]}, "params": {"axis": "x", "distance": 100.0, "exact": False}}])

rules = {
    "format": "cbl-rules", "formatVersion": 1, "id": "trn:motif-style", "revision": 1,
    "name": "Transcription network: regulators above targets",
    "description": "House style for transcription networks after Shen-Orr et al. (2002): regulators above the genes "
                   "they regulate, the targets of a local regulator in one row, mutually regulating regulators side "
                   "by side. The node attribute trn.regulator marks global and local regulators.",
    "rules": [
        {"id": "r1", "name": "Mutual regulation", "description": "Wins over Regulation for the two arcs of the pair.",
         "enabled": True, "priority": "HIGH", "template": ref(mutual)},
        {"id": "r2", "name": "Regulation", "description": "The convention itself.",
         "enabled": True, "priority": "NORMAL", "template": ref(regulation)},
        {"id": "r3", "name": "Shared regulator", "description": "Yields to Regulation where a target regulates another "
                                                                 "target of the same regulator.",
         "enabled": True, "priority": "LOW", "template": ref(shared)},
    ],
    "templates": [mutual, regulation, shared],
}

os.makedirs(OUT, exist_ok=True)
for t, fn in ((regulation, "Regulation"), (shared, "Shared regulator"), (mutual, "Mutual regulation")):
    with open(os.path.join(OUT, fn + ".cbltemplate.json"), "w", encoding="utf-8") as f:
        json.dump(t, f, indent=2, ensure_ascii=False)
with open(os.path.join(OUT, "Transcription network.cblrules.json"), "w", encoding="utf-8") as f:
    json.dump(rules, f, indent=2, ensure_ascii=False)
print("written to", OUT)
