# Regression run: the layouts before and after a change, byte for byte

The add-on changes the drawing through libcola, and a change of a switch, of the order in which constraints are handed
over, or of a step can change every coordinate. The regression run lays out every example map headless (without the
VANTED window) with the same code the add-on runs, writes the result in two forms and compares them byte for byte with a
committed baseline. It is a developer tool in `tools/regression/`; nothing of it is in the add-on jar.

## What it runs

For every `*.sbgn` file in `Files/` (the 14 example maps of SBGN-ED plus two larger PD maps):

1. **SBGN layout** of the map's language (PD, ER or AF, as the add-on's "auto" detects it from the file), with "Run all
   steps" on an empty constraint store. Output: `<map>.coords.txt` (every node with glyph id, label, centre and size,
   every edge with its ends and bends, sorted, doubles as Java prints them, plus the constraints the steps left in the
   store) and `<map>.sbgn` (the laid-out map as SBGN-ML, written by the add-on's headless writers, which are
   byte-stable).
2. **Mental-map layout** on four of the maps (`glycolysis`, `mapk_cascade`, `neuronal_muscle_signalling`,
   `principle_of_the_pcr`): the map as loaded is the reference snapshot (stable ids from the glyph ids), the live graph is
   the map with every fifth node deleted and three nodes added, and the Prototype and its deterministic variants
   (`PROTOTYPE_NO_OVERLAP`, `FROZEN`, `UNCONSTRAINED_FD`) run on it. Output: `<map>.mm-<choice>.coords.txt`.

The comparison is byte for byte. The one exception is the mental-map Prototype: its final overlap pass in libcola is
not reproducible on every run (measured up to a few hundred px on rare runs), so its file is compared with a tolerance
(`-Dregression.mmTolerance`, default 1000 px) and reported as "noise" when it differs within it. In practice two runs of
the same code give identical files for every map, the Prototype included.

The run takes about six minutes on a laptop; `central_plant_metabolism` (1317 nodes) is five of them. `--exclude
central_plant` skips it for a quick check.

## How to run it

The natives of Adaptagrams load from the adaptagrams jar of `vanted-libraries`; run with the Java the add-on is used
with (VANTED runs on Java 8).

```bash
# compare the current code with the committed baseline (exit code 1 if anything differs)
ant -Dregression.java=$JAVA8_HOME/bin/java regression

# a quick check without the largest map
ant -Dregression.java=$JAVA8_HOME/bin/java -Dregression.args="--exclude central_plant" regression

# rewrite the baseline from the current code (after a change of the layout that is wanted)
ant -Dregression.java=$JAVA8_HOME/bin/java regression-baseline
```

`ant regression` writes to `target/regression/` and prints one line per file at the end: `same`, `DIFFERS` (with the
largest coordinate deviation, or "different elements"), `noise` (mental-map Prototype within the tolerance), `MISSING`
(no baseline file) or `FAILED` (an exception, printed above). The last line is `RESULT same` or `RESULT n differ`.

The tool itself: `java headless.Regression --maps DIR --out DIR [--baseline DIR] [--include REGEX] [--exclude REGEX]
[--no-mm] [--stepwise]`, with the add-on's classes, `tools/regression` classes, VANTED's classes, `libs/sbgn-ed.jar`
and `vanted-libraries/*.jar` on the class path. `--stepwise` runs the steps one by one (as the Algorithms page does
when the user clicks them) instead of "Run all steps".

## The baseline

`tools/regression/baseline/` holds the output of the committed code. Every change that is meant to keep the drawings must leave
`ant regression` at `RESULT same`; a change that is meant to change them is made behind a switch (`-Dcblayout.<name>`,
old behaviour as the other value), checked once with the old value against the baseline, and then the baseline is
rewritten with `ant regression-baseline` and committed together with the change.

## Switches

Every tuning decision of the SBGN layouts is a switch with the previous behaviour as its alternative value; the full list
with defaults is in `switches.md`. The baseline was taken with every switch at its default.
