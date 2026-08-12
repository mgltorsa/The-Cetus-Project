# PAW Readable Index & Tile-Size Names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change PAW code generation so cross-strip indexes are `Ti`/`Tj`/`Tk` and tile-size variables are `Si`/`Sj`/`Sk` (with `_N` on collision), instead of `i_cetus_cross` and `cetus_tile_<nest>_<index>`.

**Architecture:** Centralize naming in `Tiler` helpers (`crossIndexName`, `tileSizeName`, `isCrossIndexFor`). Wire `stripmining`, `CandidateBrowser`, and `ParallelAwareTiling` through those helpers. Update unit tests, e2e greps, examples, and docs to match.

**Tech Stack:** Java (Cetus IR), JUnit 4 (`PawTestRunner`), bash (`run-paw-tests.sh`)

**Spec:** `docs/superpowers/specs/2026-08-12-paw-readable-index-names-design.md`

## Global Constraints

- Cross-strip prefix is exactly `T` + original index (`i` → `Ti`)
- Tile-size prefix is exactly `S` + original index (`i` → `Si`)
- Collision policy: append `_1`, `_2`, … until the name is free in the `SymbolTable`
- Allocation always picks the first free name and declares it (do not reuse an existing symbol that already occupies `Ti`/`Si` — later nests must get `Ti_1`/`Si_1` so Phase 3 initializers do not clobber each other)
- In-strip indexes stay as the original names (`i`, `j`, `k`)
- Do not change lemma / profitability / strip-mining algorithms beyond naming and the parallel-name match

## File structure

| File | Role |
| --- | --- |
| `src/cetus/transforms/paw_tiling/Tiler.java` | Naming API + stripmining declaration sites |
| `src/cetus/transforms/paw_tiling/browse/CandidateBrowser.java` | Declare symbolic sizes via `tileSizeName` |
| `src/cetus/transforms/paw_tiling/ParallelAwareTiling.java` | Match parallel cross loop via `isCrossIndexFor` |
| `src/cetus/unittest/paw_tiling/TilerNamingTest.java` | Unit tests for naming helpers |
| `src/cetus/unittest/paw_tiling/PawTestRunner.java` | Register new test class |
| `src/cetus/unittest/paw_tiling/DirectionVectorLemmasTest.java` | Cosmetic loop names `Ti` / `Tj` |
| `run-paw-tests.sh` | E2E greps for new names |
| `resources/examples/PawTiling_tiled*.c` | Golden-style examples |
| `AGENTS.md`, `Tiling.md`, `openspec/specs/paw-tiling/spec.md`, related notes | Doc sync |

---

### Task 1: Naming helpers + unit tests (TDD)

**Files:**
- Create: `src/cetus/unittest/paw_tiling/TilerNamingTest.java`
- Modify: `src/cetus/transforms/paw_tiling/Tiler.java`
- Modify: `src/cetus/unittest/paw_tiling/PawTestRunner.java`

**Interfaces:**
- Produces:
  - `public static final String CROSS_TILE_PREFIX = "T"`
  - `public static final String TILE_SIZE_PREFIX = "S"`
  - `public static IDExpression crossIndexName(SymbolTable symtab, String original)`
  - `public static IDExpression tileSizeName(SymbolTable symtab, String original)`
  - `public static boolean isCrossIndexFor(String crossName, String original)`
  - `public static boolean isCrossStripLoop(ForLoop loop)` — true when index name matches `T`+ident with optional `_<digits>`
- Removes (or stops using): `CROSS_TILE_SUFFIX`, `IN_TILE_PREFIX` as the naming contract

- [ ] **Step 1: Write the failing unit test**

Create `src/cetus/unittest/paw_tiling/TilerNamingTest.java`:

```java
package cetus.unittest.paw_tiling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cetus.hir.CompoundStatement;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.transforms.paw_tiling.Tiler;
import cetus.utils.VariableDeclarationUtils;

public class TilerNamingTest {

    @Test
    public void crossIndexPrefersTPlusOriginal() {
        CompoundStatement symtab = new CompoundStatement();
        IDExpression id = Tiler.crossIndexName(symtab, "i");
        assertEquals("Ti", id.getName());
    }

    @Test
    public void crossIndexFallsBackOnCollision() {
        CompoundStatement symtab = new CompoundStatement();
        VariableDeclarationUtils.declareVariable(symtab, "Ti");
        IDExpression id = Tiler.crossIndexName(symtab, "i");
        assertEquals("Ti_1", id.getName());
        IDExpression id2 = Tiler.crossIndexName(symtab, "i");
        assertEquals("Ti_2", id2.getName());
    }

    @Test
    public void tileSizePrefersSPlusOriginal() {
        CompoundStatement symtab = new CompoundStatement();
        IDExpression id = Tiler.tileSizeName(symtab, "k");
        assertEquals("Sk", id.getName());
    }

    @Test
    public void tileSizeFallsBackOnCollision() {
        CompoundStatement symtab = new CompoundStatement();
        VariableDeclarationUtils.declareVariable(symtab, "Sk");
        IDExpression id = Tiler.tileSizeName(symtab, "k");
        assertEquals("Sk_1", id.getName());
    }

    @Test
    public void isCrossIndexForAcceptsPreferredAndFallback() {
        assertTrue(Tiler.isCrossIndexFor("Ti", "i"));
        assertTrue(Tiler.isCrossIndexFor("Ti_1", "i"));
        assertTrue(Tiler.isCrossIndexFor("Ti_12", "i"));
        assertFalse(Tiler.isCrossIndexFor("Tj", "i"));
        assertFalse(Tiler.isCrossIndexFor("i_cetus_cross", "i"));
        assertFalse(Tiler.isCrossIndexFor("Ti", "ii"));
    }

    @Test
    public void isCrossStripLoopRecognizesPrefixedIndex() {
        ForLoop cross = DirectionVectorLemmasTest.loop("Ti");
        ForLoop in = DirectionVectorLemmasTest.loop("i");
        assertTrue(Tiler.isCrossStripLoop(cross));
        assertFalse(Tiler.isCrossStripLoop(in));
    }
}
```

- [ ] **Step 2: Register the test class in `PawTestRunner`**

In `PawTestRunner.java`, add `TilerNamingTest.class` to `JUnitCore.runClasses(...)`.

- [ ] **Step 3: Run tests to verify they fail**

```bash
./build.sh bin >/dev/null
java -cp "lib/junit.jar:lib/hamcrest-core-1.3.jar:class:lib/antlr.jar" \
  cetus.unittest.paw_tiling.PawTestRunner
```

Expected: FAIL / compile error — `crossIndexName` / `tileSizeName` / `isCrossIndexFor` missing (or old `isCrossStripLoop` still keyed on `_cetus_cross`).

- [ ] **Step 4: Implement naming helpers in `Tiler.java`**

Replace the old constants and add helpers near the top of `Tiler` (keep `stripmining` on the old names until Task 2 — helpers only in this step):

```java
public static final String CROSS_TILE_PREFIX = "T";
public static final String TILE_SIZE_PREFIX = "S";

/** @deprecated use CROSS_TILE_PREFIX / crossIndexName */
public static final String CROSS_TILE_SUFFIX = "_cetus_cross";
/** @deprecated use TILE_SIZE_PREFIX / tileSizeName */
public static final String IN_TILE_PREFIX = "cetus_tile_";

private static String allocateUniqueName(SymbolTable symtab, String prefix, String original) {
    String base = prefix + original;
    String candidate = base;
    int n = 1;
    while (VariableDeclarationUtils.getIdentifier(symtab, candidate) != null
            || symtab.findSymbol(new cetus.hir.NameID(candidate)) != null) {
        candidate = base + "_" + n;
        n++;
    }
    return candidate;
}

public static IDExpression crossIndexName(SymbolTable symtab, String original) {
    String name = allocateUniqueName(symtab, CROSS_TILE_PREFIX, original);
    return VariableDeclarationUtils.declareVariable(symtab, name);
}

public static IDExpression tileSizeName(SymbolTable symtab, String original) {
    String name = allocateUniqueName(symtab, TILE_SIZE_PREFIX, original);
    return VariableDeclarationUtils.declareVariable(symtab, name);
}

public static boolean isCrossIndexFor(String crossName, String original) {
    if (crossName == null || original == null) {
        return false;
    }
    String base = CROSS_TILE_PREFIX + original;
    if (crossName.equals(base)) {
        return true;
    }
    return crossName.matches(java.util.regex.Pattern.quote(base) + "_[0-9]+");
}

public static boolean isCrossStripLoop(ForLoop loop) {
    if (loop == null) {
        return false;
    }
    Expression indexVar = LoopTools.getIndexVariable(loop);
    if (indexVar == null) {
        return false;
    }
    String name = indexVar.toString();
    return name.matches("T[A-Za-z_][A-Za-z0-9_]*(_[0-9]+)?");
}
```

Notes for the implementer:
- Prefer a single `nameTaken(symtab, name)` check consistent with how `declareVariable` detects existing symbols (`findSymbol(new NameID(name))` is enough if that matches Cetus behavior in this codebase; align with whatever `getIdentifier` / `findSymbol` already use).
- Leave `stripmining` body unchanged in this task so other tests still compile against deprecated constants.

- [ ] **Step 5: Re-run unit tests — naming tests pass**

```bash
./build.sh bin >/dev/null
java -cp "lib/junit.jar:lib/hamcrest-core-1.3.jar:class:lib/antlr.jar" \
  cetus.unittest.paw_tiling.PawTestRunner
```

Expected: `TilerNamingTest` cases PASS (other suite members still PASS).

- [ ] **Step 6: Commit**

```bash
git add src/cetus/transforms/paw_tiling/Tiler.java \
  src/cetus/unittest/paw_tiling/TilerNamingTest.java \
  src/cetus/unittest/paw_tiling/PawTestRunner.java
git commit -m "$(cat <<'EOF'
Add PAW Ti/Si naming helpers with collision fallback.

EOF
)"
```

---

### Task 2: Wire stripmining, browser, and Phase-3 matching

**Files:**
- Modify: `src/cetus/transforms/paw_tiling/Tiler.java` (`stripmining`)
- Modify: `src/cetus/transforms/paw_tiling/browse/CandidateBrowser.java`
- Modify: `src/cetus/transforms/paw_tiling/ParallelAwareTiling.java`
- Modify: `src/cetus/unittest/paw_tiling/DirectionVectorLemmasTest.java`

**Interfaces:**
- Consumes: `Tiler.crossIndexName`, `Tiler.tileSizeName`, `Tiler.isCrossIndexFor`
- Produces: generated IR uses `Ti`/`Si` names; parallel balancing still detects the cross-strip of original `i`

- [ ] **Step 1: Update `stripmining` to use helpers**

In `Tiler.stripmining`, replace the cross-index block (the doubled-name `getIdentifier` + `CROSS_TILE_SUFFIX` declare) with:

```java
Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);
IDExpression crossIndex = crossIndexName(symbolTable, loopSymbol.getSymbolName());
```

Replace the non-literal / non-IDExpression tile-size materialization block with:

```java
if (!(strip instanceof IntegerLiteral) && !(strip instanceof IDExpression)) {
    strip = tileSizeName(symbolTable, loopSymbol.getSymbolName());
    // If an initializer is required for this path, declare with value:
    // VariableDeclarationUtils.declareVariable already ran inside tileSizeName
    // without a value. Prefer: allocate name then declare with tileSize.clone()
    // — adjust helper or add tileSizeName(symtab, original, Expression init)
    // so this path sets the initializer to tileSize.clone().
}
```

If the valued declare is needed, add overload:

```java
public static IDExpression tileSizeName(SymbolTable symtab, String original, Expression init) {
    String name = allocateUniqueName(symtab, TILE_SIZE_PREFIX, original);
    return VariableDeclarationUtils.declareVariable(symtab, name, init);
}
```

and use the no-init form from `CandidateBrowser`, valued form from this fallback.

Remove remaining uses of `CROSS_TILE_SUFFIX` / `IN_TILE_PREFIX` from `Tiler` (delete the deprecated constants once nothing references them).

- [ ] **Step 2: Update `CandidateBrowser`**

Replace:

```java
String sizeName = Tiler.IN_TILE_PREFIX + nestTag + "_" + indexName;
IDExpression sizeId = VariableDeclarationUtils.getIdentifier(symtab, sizeName);
if (sizeId == null) {
    sizeId = VariableDeclarationUtils.declareVariable(symtab, sizeName);
}
```

with:

```java
IDExpression sizeId = Tiler.tileSizeName(symtab, indexName);
```

Update the javadoc on `browse(... nestTag ...)` to state that `nestTag` is no longer embedded in size names (kept only for optional debug / call-site compatibility). Do **not** use `nestTag` in the variable name.

- [ ] **Step 3: Update `ParallelAwareTiling` Phase 3**

Replace:

```java
boolean isParallelTileLoop = parallelName != null
        && parallelName.equals(indexName + Tiler.CROSS_TILE_SUFFIX);
```

with:

```java
boolean isParallelTileLoop = parallelName != null
        && Tiler.isCrossIndexFor(parallelName, indexName);
```

- [ ] **Step 4: Update lemma unit-test loop labels**

In `DirectionVectorLemmasTest.java`, rename fixture loops:

- `"i_cetus_cross"` → `"Ti"`
- `"j_cetus_cross"` → `"Tj"`

(Lemma logic is name-identity based only within each test's loop objects; this is for consistency with generated names.)

- [ ] **Step 5: Build and run unit tests**

```bash
./build.sh bin >/dev/null
java -cp "lib/junit.jar:lib/hamcrest-core-1.3.jar:class:lib/antlr.jar" \
  cetus.unittest.paw_tiling.PawTestRunner
```

Expected: all PAW unit tests PASS.

- [ ] **Step 6: Smoke-check generated names on gemm**

```bash
rm -rf /tmp/paw-name-smoke && mkdir -p /tmp/paw-name-smoke
(cd /tmp/paw-name-smoke && \
  /mnt/d/workspace/cetus/The-Cetus-Project/bin/cetus \
  -paw_tiling=1 -cores=4 -cacheSize=1024 -cacheLine=64 -selection=NT \
  /mnt/d/workspace/cetus/The-Cetus-Project/resources/paw_tests/gemm.c) \
  >/tmp/paw-name-smoke/cetus.log 2>&1
grep -E '\b(Ti|Tj|Tk|Si|Sj|Sk)\b' /tmp/paw-name-smoke/cetus_output/gemm.c | head
grep -E '_cetus_cross|cetus_tile_' /tmp/paw-name-smoke/cetus_output/gemm.c || true
```

Expected: `Ti`/`Si`-style names present; no `_cetus_cross` / `cetus_tile_` lines.

- [ ] **Step 7: Commit**

```bash
git add src/cetus/transforms/paw_tiling/Tiler.java \
  src/cetus/transforms/paw_tiling/browse/CandidateBrowser.java \
  src/cetus/transforms/paw_tiling/ParallelAwareTiling.java \
  src/cetus/unittest/paw_tiling/DirectionVectorLemmasTest.java
git commit -m "$(cat <<'EOF'
Emit Ti/Si names from PAW strip-mining and tile-size selection.

EOF
)"
```

---

### Task 3: E2E script, examples, and docs

**Files:**
- Modify: `run-paw-tests.sh`
- Modify: `resources/examples/PawTiling_tiled.c`
- Modify: `resources/examples/PawTiling_tiled_papi.c`
- Modify: `AGENTS.md`
- Modify: `Tiling.md`
- Modify: `openspec/specs/paw-tiling/spec.md`
- Modify (light touch): `openspec/changes/extend-paw-tiling/tasks.md`, `openspec/changes/extend-paw-tiling/design.md`, `PAW_LEMMAS_REUSE_AUDIT.md` — replace old name examples where they document current behavior

**Interfaces:**
- Consumes: generated code shape from Task 2
- Produces: tests/docs that assert/describe `Ti`/`Si` naming

- [ ] **Step 1: Update `run-paw-tests.sh` greps**

Replace old checks:

```bash
check "gemm: tile size variables emitted"       grep -q "cetus_tile_" "$GEN"
check "gemm: cross-strip (tile) loops emitted"  grep -q "_cetus_cross" "$GEN"
```

with:

```bash
check "gemm: tile size variables emitted"       grep -qE '\bS[a-zA-Z_][a-zA-Z0-9_]*\b' "$GEN"
check "gemm: cross-strip (tile) loops emitted"  grep -qE '\bT[a-zA-Z_][a-zA-Z0-9_]*\b' "$GEN"
```

For `small_footprint` (must remain untiled), prefer pragma absence (more robust than size-name greps):

```bash
if grep -q "c_paw_tiling" "$GEN"; then
    bad "small: nest was tiled despite tiny footprint"
else
    ok "small: static profitability left nest untouched"
fi
```

For `symbolic_bounds`:

```bash
check "symbolic: tile variables emitted"        grep -qE '\bS[a-zA-Z_][a-zA-Z0-9_]*\b' "$GEN"
```

Scan the rest of the script for any remaining `cetus_tile_` / `_cetus_cross` and update the same way.

- [ ] **Step 2: Refresh example tiled C files**

In `resources/examples/PawTiling_tiled.c` and `PawTiling_tiled_papi.c`, mechanically rename:

| Old | New (first nest) | Later nests (collision) |
| --- | --- | --- |
| `i_cetus_cross` | `Ti` | `Ti_1`, `Ti_2`, … |
| `j_cetus_cross` | `Tj` | `Tj_1`, … |
| `k_cetus_cross` | `Tk` | `Tk_1`, … |
| `cetus_tile_main_0_i` | `Si` | |
| `cetus_tile_main_0_j` | `Sj` | |
| `cetus_tile_main_1_i` | `Si_1` (if `Si` already used) | |
| `cetus_tile_main_1_j` | `Sj_1` | |
| `cetus_tile_main_1_k` | `Sk` | |
| `cetus_tile_main_2_i` | `Si_2` | |
| `cetus_tile_main_2_j` | `Sj_2` | |

Prefer regenerating via Cetus from `resources/examples/PawTiling.c` (or `resources/paw_tests/gemm.c` style) after Task 2, then copying into the examples paths, over hand-editing if the trees diverge.

- [ ] **Step 3: Update docs**

`AGENTS.md` cross-strip naming bullet →:

```markdown
- **Cross-strip naming**: tile (cross-strip) loops use index prefix
  `T` (`i` → `Ti`); tile-size variables use prefix `S` (`i` → `Si`).
  On collision in the declaration scope, append `_1`, `_2`, ….
  Tiled regions carry the `c_paw_tiling` `PragmaAnnotation` (the PAPI
  pass keys on it).
```

`Tiling.md` example loops: use `Ti`/`Si` instead of `i_cetus_cross` / `tile_size_i`.

`openspec/specs/paw-tiling/spec.md` scenario THEN clause:

```markdown
- **THEN** the output contains cross-strip loops (index names `Ti`, `Tj`, …)
  stepping by tile-size variables (`Si`, `Sj`, …) and in-strip loops
  bounded by `MIN(cross+T, N)`
```

Update the extend-paw-tiling task/design notes and `PAW_LEMMAS_REUSE_AUDIT.md` example lines that still show `i_cetus_cross` / `cetus_tile_…` as *current* behavior.

- [ ] **Step 4: Run the full PAW test driver**

```bash
./run-paw-tests.sh
```

Expected: all checks PASS (unit + e2e plain + e2e papi).

- [ ] **Step 5: Commit**

```bash
git add run-paw-tests.sh \
  resources/examples/PawTiling_tiled.c \
  resources/examples/PawTiling_tiled_papi.c \
  AGENTS.md Tiling.md \
  openspec/specs/paw-tiling/spec.md \
  openspec/changes/extend-paw-tiling/tasks.md \
  openspec/changes/extend-paw-tiling/design.md \
  PAW_LEMMAS_REUSE_AUDIT.md
git commit -m "$(cat <<'EOF'
Align PAW tests and docs with Ti/Si generated names.

EOF
)"
```

---

## Spec coverage check

| Spec requirement | Task |
| --- | --- |
| `T`+original cross indexes | Task 1–2 |
| `S`+original tile sizes | Task 1–2 |
| `_N` collision fallback | Task 1 |
| `stripmining` uses helpers; fix doubled-name lookup | Task 2 |
| `CandidateBrowser` drops nestTag from names | Task 2 |
| Phase 3 `isCrossIndexFor` | Task 2 |
| Tests / examples / docs / `run-paw-tests.sh` | Task 3 |
| `./run-paw-tests.sh` passes | Task 3 |
| No algorithm changes beyond naming | All tasks |
