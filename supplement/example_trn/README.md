# Example for Sect. 5.4: a template library for transcription networks

## Files

- `ecoli_sugar_regulation.gml`: the example graph (40 nodes, 90 arcs), written by VANTED's own GML writer. It opens in VANTED as usual.
  - Node attribute `trn.regulator`: `global` (crp) or `local` (araC, xylR, galS, galR, rhaS, rhaR, lacI).
  - Edge attribute `trn.effect`: `activator`, `repressor` or `dual`. Repression arcs have the inhibitor arrowhead.
  - The initial positions come from a seeded spring layout (networkx, seed 7).
- `ecoli_sugar_regulation_after.gml`: the same graph after the rule set was applied and solved headless with the add-on's own code. This is for comparison only: it holds positions, not the constraints.
- `library/`: three pattern templates (`.cbltemplate.json`) and the rule set `Transcription network.cblrules.json`, which embeds all three.
  - **Mutual regulation**, priority High: A ⇄ B are aligned in one row, with A left of B.
  - **Regulation**, priority Normal: the regulator is above its target (vertical gap ≥ 70).
  - **Shared regulator**, priority Low: two targets of the same regulator are aligned in one row. Condition: `trn.regulator` is `local`.
- `build/`: everything needed to reproduce the graph, the templates and the numbers (see below).

## Reproducing the figure in VANTED

1. Open `ecoli_sugar_regulation.gml`.
2. In the template panel, load the rule set `library/Transcription network.cblrules.json` and run *Find*. Expect 259 matches: Mutual regulation 2, Regulation 81, Shared regulator 176.
3. Apply all matches. At most 100 are applied per step, so this takes three steps.
   - Settings for the solve: force-directed run **off**, overlap removal **on**. This is what the headless run used.
4. Expected result: 261 constraints, of which 24 are not applied.
   - 2 Regulation constraints lose against Mutual regulation (galR/galS, rhaR/rhaS).
   - 22 Shared-regulator rows lose against Regulation.
   - All applied constraints hold after the solve.
   - The rejected rows can differ slightly, because in the GUI the constraints are applied in several batches, so their ages differ. The number should stay close.

## Data and processing (`build/`)

- **Source:** RegulonDB release 10.7 (05/04/2020), file `network_tf_gene.txt`, retrieved from a GitHub copy (AnVales/Gene-networks). The header and licence of the file are intact.
  - Licence: free for academic and noncommercial use. Do **not** redistribute the raw file or derived databases. Cite Santos-Zavaleta et al., NAR 47(D1):D212–D220, 2019, doi:10.1093/nar/gky1077.
- **`trn.py`:** builds the gene-level network with 1,908 genes and 4,439 regulatory pairs.
  - Each transcription factor is merged with the gene of the same name (AraC → araC, CRP → crp). 150 of 211 transcription factors map this way. The others, mostly complexes such as IHF, keep a node of their own.
  - A pair listed both as activator and as repressor gets the effect `dual`.
- **`make_subnet.py`:** builds the induced subgraph on CRP, AraC, XylR, GalS, RhaS and LacI plus all their targets. GalR and RhaR come in as targets. It also writes the attributes and the spring layout as TSV files.
- **`make_templates.py`:** writes the three templates and the rule set in the format of `TemplateCodec` (cbl-template / cbl-rules, formatVersion 1).
- **`Run.java`:** the headless check, run with the add-on's and VANTED's classes on the classpath. In order, it:
  - builds the graph and writes and reads it with VANTED's GML writer and reader;
  - loads the rule set and runs `TemplateValidator` (no problems found);
  - runs one `SearchRun`, applies every match (first variant) with `TemplateApplier`, and runs `ConstraintConflicts.resolve`;
  - optionally runs `CBLayoutAlgorithm.solveUserConstraints` (with `nofd` as the 6th argument, there is no force-directed run).
  - Usage: `java -cp "<ConstraintLayout classes>:<vanted classes>:<vanted-libraries>/*" Run.java trn_nodes.tsv trn_edges.tsv "Transcription network.cblrules.json" out.gml prefix nofd`
  - `headless_run.log` is its output for the files in this folder.
