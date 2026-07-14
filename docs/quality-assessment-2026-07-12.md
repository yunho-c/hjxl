# HJXL Quality Assessment — 2026-07-12

## Executive summary

`hjxl` is a strong verification-led research prototype and a thoughtful
hardware/software co-design scaffold. It is not yet a complete JPEG XL encoder,
an FPGA-ready accelerator, or a release-ready codebase.

The project’s best decision is its trace-first decomposition around
`libjxl-tiny`. It does not hide incomplete image stages behind a final-byte
comparison. Instead, it exposes stable `StageTrace` boundaries, separates
approximate RGB-input stages from exact prepared-data stages, and verifies
quantized and tokenized results against a pinned software oracle. The
prepared-DCT path can already take software-prepared coefficients through RTL
quantization/tokenization and then through host-side assembly to bytes matching
the constrained `libjxl-tiny` all-DCT oracle. That is meaningful progress, and
the test suite is unusually serious for a project this young.

The main limitation is that the implemented architecture is still optimized
for observability and simulation, not for a useful KV260 implementation. Most
frame schedulers buffer complete bounded frames or coefficient planes in
`Reg(Vec(...))`; several arithmetic paths contain wide combinational products
or division; the default frame bound is only 32x32; and there are no synthesis,
utilization, timing, clock-rate, throughput, power, bitstream, or board-run
results. The RGB path uses approximate XYB; its default quantization/token
routes remain fixed all-8x8-DCT, while a separate focused route now computes an
adaptive transform-strategy map. A standalone prepared-AQ boundary exactly converts real libjxl-tiny
AQ maps from Q24 into raw-quant traces, and the RGB core now implements the
first image-dependent quarter-resolution contrast grid plus fuzzy erosion into
one value per padded block and the reciprocal mask used by AC-strategy scoring.
The separate nonlinear `_compute_mask` branch now also emits the signed
log-domain seed for final-map modulation, and the first per-block HF term now
subtracts a 112-edge Y-detail sum from that seed without repeating XYB
conversion. The next distance-dependent color term now adds capped Q16 red/blue
coverage through a shared single-converter block scheduler. The gamma term now
continues through an averaged inverse response and normalized Q20 log. The
normalized Q24 exponent, distance scale/damping, completed final map, and exact
raw-quant conversion are now connected behind focused RGB routes. A nonzero
fixed byte remains an explicit override. The resulting raw-quant field now
feeds the RGB DCT quantizer and AC-metadata path with a matching per-block
inverse scale; a standalone wrapper carries the same Q12 all-DCT blocks through
the exact prepared schedulers to combined logical tokens. That path now also
estimates per-tile CFL from the same RGB-derived Q12 DCT records, feeds those
maps through quantization and AC metadata, exposes focused map/AC-token core
routes, and preserves the prepared-Q16 stream ABI. Deterministic map fixtures
match the independent fixed model exactly and stay within two signed int8 units
of libjxl-tiny. The recursive DCT-16 kernel, both canonical rectangular
transforms, a prepared fixed-point candidate entropy/loss evaluator, and exact
2x2 orientation/first-block decision boundary are now implemented as reusable
primitives. The integer scorer matches its independent model exactly, but its
Q12 coefficient seam remains approximate against float libjxl-tiny: a 30-case
pattern/distance audit selected the same final map in 27 cases, with three
symmetric checkerboard orientation disagreements. The prepared frame scheduler
now fits tile CFL, generates all eight candidate shapes with covered-block
AQ/mask maxima, preserves incomplete tile/frame edges as DCT, and emits the
raster strategy map; a focused RGB/core route feeds it from the shared AQ/DCT
source. The scheduler now also performs libjxl-tiny's post-search raw-quant
adjustment, and a second focused core route exposes the aligned adjusted byte
field. Rectangular quantization/tokens and consumption of that strategy/field
by the existing all-DCT output path remain open.
Entropy coding and bitstream assembly also remain software-only, and the most
parity-ready hardware interface starts after the host has already computed
prepared DCT blocks.

At the start of this review, the working tree was an engineering risk in its
own right. Before this report was added, it
contained 64 modified and 22 untracked paths, with roughly 10,500 inserted and
1,500 deleted lines relative to `main`. Much of the most advanced estimated-CFL
work exists only in that uncommitted state. Local validation is valuable, but a
reviewer or CI run of `main` cannot reproduce this snapshot.

**Follow-up on 2026-07-12:** this immediate reproducibility risk was remediated
after the review. The implementation wave was partitioned into independently
validated commits `bbee6a1` (fixed quantization metadata routes), `fec2527`
(prepared estimated-CFL pipeline), and `748ee78` (host replay contracts).

### Overall judgment

| Perspective | Assessment |
| --- | --- |
| Research/verification scaffold | **Very good** |
| Architectural thoughtfulness | **Good, with a clear trace-first strategy** |
| RTL maintainability | **Good at the primitive level; increasingly strained at frame-wrapper level** |
| Verification of implemented logical behavior | **Very good** |
| Documentation content | **Comprehensive and candid, but overgrown and repetitive** |
| Completeness versus the stated `libjxl-tiny` target | **Partial** |
| FPGA implementation readiness | **Early** |
| Release/reproducibility readiness | **Moderate: committed validation and generated contracts, but no releases or physical artifacts** |

As an indicative score, the project is about **6.5/10 overall today**: closer
to 8/10 as a correctness-oriented RTL exploration environment, but closer to
2–3/10 as a demonstrated FPGA JPEG XL encoder. Those numbers deliberately
separate code quality from product completeness.

## Scope and method

The original 2026-07-12 assessment covered the complete working checkout, not
only the then-current `main`. Dated follow-ups in this document record the
validated changes committed since that snapshot. The review included:

- the complete repository inventory, Git history, worktree state, build files,
  CI workflow, ignore rules, README, architecture document, and agent guide;
- all RTL and test file inventories, with detailed inspection of the shared
  bundles/configuration, core routing, frame schedulers, fixed-point primitives,
  prepared-DCT pipelines, CFL estimation, tokenization, AXI-stream/AXI-Lite
  wrappers, KV260 top, elaborators, representative unit/oracle/integration
  tests, and the host-tool architecture;
- code-size, test-size, duplication, missing-tooling, and incomplete-stage
  searches across the checkout;
- local compilation, Python syntax checking, host-metadata smoke testing, and
  the Scala/Verilator test suite;
- local toolchain inspection. Java, sbt, Python, and Verilator are available;
  Vivado, Vitis, and Yosys were not found.

Commit `973c3b5` (`feat: complete RGB adaptive quantization map`) was the final
baseline for the original assessment refresh. Commit `1f591d7` (`feat:
integrate adaptive AQ with RGB quantization`) is the baseline immediately
before the RGB-derived CFL follow-up described below. Commit `725141b` (`feat:
add rectangular AC strategy primitives`) is the baseline immediately before
the prepared cost-evaluator follow-up. Commit `a3f3279` (`feat: add prepared AC
strategy cost scoring`) is the baseline immediately before the frame/RGB
scheduler follow-up. Commit `f428908` (`feat: add adaptive AC strategy frame
scheduling`) is the baseline immediately before the strategy-adjusted
raw-quant follow-up; verification evidence is always stated with the
implementation slice it covers.

Snapshot size is approximately:

| Area | Files | Lines |
| --- | ---: | ---: |
| Main HJXL Scala/Chisel | 63 | 14,689 |
| Scala tests | 69 | 26,481 |
| Python host/oracle tools | 18 | 13,901 |
| `README.md` + `docs/architecture.md` + `AGENTS.md` | 3 | 4,106 |

The test-to-RTL ratio is a strength, but these counts also reveal where
complexity has moved: host tooling and test harnesses are now materially larger
than the RTL implementation.

## Product and architecture assessment

### What the architecture gets right

1. **The project scope is explicit and defensible.** It targets the lossy
   VarDCT subset represented by `libjxl-tiny`, not the full JPEG XL surface.
   The repository is candid about lossless mode, alpha, metadata boxes, full
   strategy coverage, and bit-exact floating-point parity being out of early
   scope.

2. **The hardware/software split is pragmatic.** Host software handles file
   decoding and final entropy/bitstream work while RTL targets deterministic
   transforms, quantization, maps, and logical tokens. That lets the project
   demonstrate useful parity before attempting a hardware entropy coder.

3. **Traceability is a first-class architectural feature.** `StageTrace`
   provides one common stage/group/index/value contract, and `traceLast` gives
   all current frame routes an explicit capture boundary. This makes earliest-
   mismatch debugging possible and maps well to the reference pipeline.

4. **Prepared boundaries isolate uncertainty well.** The prepared-DC,
   prepared-AC, prepared-token, direct prepared-DCT, and estimated-CFL prepared
   paths allow exact testing of downstream RTL even while RGB-to-XYB, AQ, and
   strategy selection remain incomplete. This is the project’s strongest
   architectural pattern.

5. **Protocol boundaries are deliberately specified.** The stream packing,
   201-word prepared-block ABI, TLAST rules, full-word TKEEP rule, 88-bit trace
   packing, 128-bit KV260 trace padding, AXI-Lite register map, sticky protocol
   error, busy/overflow status, and unsupported-distance fallback are all
   documented and tested.

6. **Compile-time route selection controls elaboration cost.** Keeping the
   heavy full-AC-token scheduler out of the default all-route core is a sensible
   response to simulation and generated-RTL growth.

### Where the architecture is still provisional

1. **The validation architecture is not yet a scalable FPGA architecture.**
   Many modules buffer complete frames, full coefficient sets, quantized
   planes, or token inputs in registers. This is excellent for small deterministic
   fixtures but will scale area approximately with configured image capacity.
   A useful image-size configuration would elaborate enormous register arrays
   unless these stores become inferred RAMs, line/tile buffers, or external
   memory transactions.

2. **The datapath is largely batch-oriented.** A frame or prepared block set is
   received, buffered, transformed, and later emitted. There is little overlap
   between acquisition, compute, and output, and no established steady-state
   pixels/blocks-per-cycle contract. `Decoupled` interfaces are present, but
   the macro-architecture is not yet a streaming pipeline.

3. **Several arithmetic choices are correctness prototypes.** Dynamic division
   in CFL estimation, wide chained products in quantization, large combinational
   DCT networks, and LUTs expressed as `VecInit` are reasonable first models.
   Their DSP mapping, critical paths, initiation intervals, and resource cost
   are unknown. They should not be assumed to meet KV260 timing merely because
   Verilator accepts them.

4. **Control-plane selection remains overloaded.** `HjxlCore` combines compile-
   time `traceRoute` selection with a priority tree derived from runtime feature
   flags. Discovery now reports the frame-stable active route, and the new AQ
   extension uses an explicit previously reserved selector so older flag words
   retain their behavior. Even so, a single explicit runtime route enum would
   be clearer than interpreting feature flags as both pipeline controls and
   route selection.

5. **Configuration lifetime is now transactional but implicit.** Stream shells
   snapshot the complete configuration on the first accepted frame beat, keep
   it stable through the final accepted trace, and treat AXI-Lite writes while
   busy as next-frame shadow values. This closes the former mid-frame drift
   risk. An explicit start/commit command may still be preferable once a DMA
   driver needs deterministic coordination across control and stream channels.

6. **A versioned discovery contract now exists, but compatibility policy is
   still informal.** Read-only registers expose ABI version, build ID,
   capabilities, maximum dimensions, and frame-stable active route, with Scala
   and Python constants generated from `abi/hjxl_abi.json`. The repository still
   needs a concise public compatibility policy and generated ABI reference.

7. **The adaptive-strategy output is a diagnostic seam, not yet a compositional
   data boundary.** `FramePreparedAcStrategyTraceStage` emits one encoded
   decision and one adjusted raw-quant byte per raster cell, but the selected
   64- or 128-coefficient payload remains inside the scheduler. Conversely, the
   existing prepared quantization, metadata, and AC-token schedulers accept one
   ordinary 64-coefficient result per raster cell and assume that every cell
   owns a transform. Those interfaces fit all-DCT verification well but cannot
   represent a rectangular transform without either recomputing or duplicating
   the full-frame XYB/coefficient source.

   This is more than a width mismatch. In the reference implementation one
   first block owns the rectangle; its two lowest-frequency coefficients form
   two block-grid DC samples, its raw nonzero count excludes the rectangular
   low-frequency region, and a shifted count is replicated over both covered
   cells for later neighbor prediction. Metadata and coefficient-token streams
   skip continuation cells, while coefficient scanning uses a rectangular
   order and covered-block-aware zero-density contexts. These four semantics
   must move together. The clean next boundary is therefore an internal
   first-block-owned `Decoupled` record carrying strategy, covered geometry, up
   to 128 selected coefficients, adjusted quant, tile CFL, and distance-derived
   scalars, plus explicitly separate block-grid DC/nonzero maps. It should be
   stored in inferred memory or regenerated from one owned image buffer—not
   exposed as hundreds of public trace ports or added as another parallel
   full-frame wrapper.

## RTL implementation quality

### Strengths

- Naming is descriptive and generally maps directly to `libjxl-tiny` concepts.
  Module comments usually state what is implemented and, importantly, what is
  not implemented.
- Shared configuration and bundles are small and easy to inspect. Elaborative
  `require` checks catch invalid widths and dimensions early.
- Carry-preserving ceil-division is used in important capacity calculations,
  including non-aligned 72-pixel cases that previously exposed width bugs.
- Ready/valid handling is treated seriously. Many emitters hold output stable
  under backpressure, and the tests explicitly stall trace outputs.
- Fixed-point intent is documented with Q formats, rounding, clamping, and
  signedness. Exact comparisons are used after quantization/tokenization, while
  tolerances are confined to approximate floating/fixed stages.
- Primitive decomposition is good: DCT, distance lookup, quant-scale selection,
  Y roundtrip, chroma residual, DC quantization, token packing, coefficient
  scan, CFL weighting/accumulation/fitting, and tile geometry can be tested
  independently.
- AXI-Lite byte strobes, independent address/data arrival, DECERR responses,
  TLAST mismatches, partial TKEEP, sticky errors, and recovery receive direct
  test coverage.

### Weaknesses and risks

- The package is flat: 59 HJXL source files and 67 test files all live in one
  namespace/directory. The naming remains navigable today, but the many
  `FrameDctOnly*`, `FramePrepared*`, `FramePreparedCfl*`, stream, controlled-
  stream, and KV260 variants are beginning to form a combinatorial wrapper
  matrix. Subpackages or composition around shared schedulers would reduce the
  cognitive load.
- Frame buffering and traversal logic is repeated across many schedulers. The
  extraction of `CflTileGeometry` is a good example of the refactoring pattern
  that should continue for frame geometry, capacity checking, config latching,
  trace emission, and prepared-block storage.
- `HjxlCore` is more than 300 lines largely because every route repeats wiring
  and mux participation. `Elaborate.scala` is another near-mechanical 509-line
  matrix. These are maintainable only while every new route is changed in all
  required places.
- In-RTL assertions remain sparse. Recent AQ arithmetic checks nonzero divisors,
  accepted-pixel counts, traversal order, and accumulator bounds, while most
  protocol checking still relies on status signals and external tests. Broader
  assertions for legal states, stable output under stall, final-beat
  relationships, and index bounds would improve debug and formal-readiness.
- Some combinational expressions use values that are only meaningful in active
  states—for example frame traversal arithmetic before nonzero dimensions have
  been latched. Invalid outputs are normally masked by `valid`, but safe
  denominators and explicit state guards would generate cleaner hardware and
  reduce X-propagation risk.
- There is no style formatter or static-analysis gate for Scala or Python. The
  code is currently consistent by discipline, not automation.
- Generated RTL size makes the scaling concern concrete even before synthesis:
  the default focused RGB estimated-CFL tops emit about 46.9k SystemVerilog
  lines for maps, 49.3k for metadata, 67.2k for quantization, and 82.4k for
  combined tokens. These figures are hierarchy/complexity indicators, not
  utilization or timing evidence.

## Verification and correctness

Verification is the project’s strongest quality dimension.

### Strong evidence

- The assessed checkout has roughly 25.4k lines of Scala tests for 13.4k lines
  of RTL, plus extensive validation inside the Python tools.
- Tests span small arithmetic primitives, frame traversal, exact 72-pixel
  capacity boundaries, two-dimensional 72x72 CFL tile ordering, route
  elaboration, generated SystemVerilog port surfaces, AXI-Lite behavior,
  stream framing, TKEEP/TLAST errors, backpressure stability, and host artifact
  validation.
- The pinned `libjxl-tiny` Python port is used as a behavioral oracle. Prepared
  Q16 coefficients are checked through RTL quantization and tokenization, then
  converted back through host tooling to frame and bare codestream bytes.
- Token contexts, values, order, metadata maps, and final bytes are exact where
  the implementation claims parity. Approximate XYB/DCT comparisons use
  explicit tolerances rather than silently weakening downstream checks.
- Host validators reject malformed types, range errors, stale paths,
  contradictory metadata, incorrect checksums, wrong stream sizes, invalid
  TLAST/TKEEP, and mismatched target geometry. Generated C and C++ headers are
  smoke-compiled.
- Both sbt and Mill builds are represented in CI, and the external oracle is
  pinned to a commit rather than a moving branch.

### Gaps in the evidence

- There is no formal verification, protocol checker, property-based test suite,
  or systematic randomized ready/valid and AXI ordering campaign. Directed
  backpressure coverage is good, but state-space coverage is unknown.
- There is no line, branch, toggle, FSM-state, assertion, or functional coverage
  report. Test volume is not the same as measured coverage.
- Several unit references duplicate formulas in Scala rather than importing an
  independently generated oracle. The higher-level oracle-backed tests mitigate
  this, but primitive tests can still share the same conceptual mistake as the
  implementation.
- Oracle-backed tests can be skipped through ScalaTest `assume` when NumPy or
  the reference checkout is absent. CI installs them, which is appropriate, but
  local “green” results need to state whether skips occurred.
- The largest test harness, `StreamTraceToolSpec.scala`, is about 4,800 lines.
  It provides valuable integration coverage but is difficult to review,
  parallelize intentionally, and diagnose compared with focused Python unit
  tests plus smaller Scala bridge tests.
- Running both complete sbt and Mill test suites in one CI job is useful as a
  build-definition consistency check but expensive. A shared fast gate plus a
  scheduled second-build/full-oracle job would likely give better feedback
  latency. On this machine, the original `sbt test` run for the 2026-07-12
  assessment took 8 minutes 52 seconds, with many separate Verilator models
  built by the simulator-backed suites.
- No Vivado simulation, synthesis, implementation, timing, or board test exists.
  Generated-port checks prove interface shape, not AXI protocol certification
  or FPGA viability.

## Host tooling quality

The host side is functionally impressive. It can generate reference fixtures,
pack RGB and prepared-DCT streams, emit and validate AXI-Lite write sequences,
generate C headers, create portable bundles with checksums, describe replay
plans, preflight capture expectations, decode packed traces, extract tokens and
metadata, compare oracles, and assemble codestream bytes. Error messages are
generally field- and line-specific, and numeric type/range checks avoid common
Python/NumPy coercion bugs.

The maintainability picture is less strong:

- Python tooling is about 13.2k lines with no `pyproject.toml`, package layout,
  dependency lock, formatter, linter configuration, type checker, or Python
  test runner. Tests are largely driven indirectly from Scala subprocesses.
- `hjxl_replay_capture.py` and `hjxl_host_metadata_smoke.py` are each roughly
  1,500 lines. The smoke test imports private underscore-prefixed helpers from
  the CLI module, coupling tests to implementation details.
- Register offsets, trace stage IDs, route names, status bits, packing widths,
  keep masks, target names, and stream-layout constants exist in multiple Scala
  and Python locations. The project compensates with strong lockstep tests, but
  generation from one machine-readable contract would prevent drift instead of
  detecting it after duplication.
- The host path depends on a separate `libjxl-tiny` Python checkout and several
  internal functions. CI pins a known fork/commit, which is reproducible, but
  there is no standalone installable host package or compatibility adapter.

## Documentation and developer experience

Documentation is candid, technically rich, and unusually precise about the
difference between fixed scaffolds, approximate stages, prepared parity, and
future work. Commands, formats, register meanings, fixture shapes, generated
directories, and test responsibilities are documented in detail. The pinned
versions and CI prerequisites are easy to find.

The information architecture is poor, however:

- The README is 1,401 lines, the architecture document 1,242 lines, and the
  agent guide 1,294 lines. All three repeat long inventories of nearly every module,
  elaborator, tool, format, and regression.
- The README has only a handful of top-level sections, so it functions as a
  changelog/reference dump rather than an onboarding document.
- The architecture document mixes enduring boundaries with generated-target
  commands, tool manuals, test catalogs, and status notes. Some statements about
  remaining CFL plumbing are already ambiguous relative to the new prepared
  estimated-CFL implementation.
- There is no concise current-status matrix, roadmap, FPGA bring-up document,
  ABI specification, contribution guide, or release checklist. Those concerns
  are embedded in prose across three large files.

Recommended documentation split:

1. Keep `README.md` to purpose, maturity, one architecture picture, quick start,
   and links.
2. Keep `docs/architecture.md` to stable responsibilities, dataflow, buffering,
   fixed-point policy, and hardware/software boundaries.
3. Add `docs/status.md` with a stage-by-stage implemented/verified/missing table.
4. Add `docs/host-abi.md` generated or checked from the authoritative contract.
5. Add `docs/kv260-bringup.md` for synthesis, block design, DMA, clocks,
   constraints, timing, bitstream, and board evidence.
6. Keep `AGENTS.md` concise and procedural; move module catalogs to generated
   reference documentation if they remain useful.

## Build, CI, reproducibility, and repository hygiene

### Positive

- Scala, Chisel, ScalaTest, sbt, Mill, and the oracle commit are pinned.
- CI runs on pull requests and `main`, installs declared system dependencies,
  checks the listed Python helpers, and runs both Scala build systems.
- Generated RTL, simulator output, build products, Python bytecode, and local
  caches are ignored. Generated SystemVerilog can be recreated through explicit
  elaboration entry points.
- The repository is GPLv3-licensed and small enough to clone and inspect easily.

### Negative or outstanding at review time

- At the start of the review, the advanced state was not reproducible from Git:
  86 paths were dirty or untracked. The follow-up commits named in the executive
  summary have since resolved this immediate issue.
- The repository has 53 commits before this latest assessment follow-up, but
  still has no tags or releases. The formerly uncommitted development wave
  was recovered into reviewable commits; release-level checkpoints and
  published artifacts remain absent.
- CI has no formatter, lint, warnings-as-errors, coverage, synthesis smoke, or
  artifact publication. Generated ABI drift is checked, which is an important
  exception.
- GitHub Actions are version-tag pinned rather than commit-SHA pinned. That is
  common, but weaker supply-chain reproducibility.
- The broad template-derived `.gitignore` works but contains substantial
  unrelated legacy IDE/ISE material; a smaller project-specific file would be
  easier to audit.
- There is no documented contributor workflow, review checklist, changelog, or
  compatibility policy. These are not urgent for a solo prototype but become
  important before external use.

## Completeness against the stated goal

| Pipeline/capability | Current state | Quality of evidence | Remaining gap |
| --- | --- | --- | --- |
| Linear RGB/PFM host input | Implemented for fixtures and stream generation | Parser/packing/manifest tests | General image decode/integration and production driver |
| Frame padding | Implemented | Exact small-frame and edge-padding tests | Scalable storage architecture |
| RGB to XYB | Range-normalized Q8 to Q12 approximation with signed Q26 matrix and Q24 absorbance | Exact normalization-boundary/model tests, three libjxl-tiny fixture families within two Q12 units, 100k full signed-range sweep within five, and frame/downstream regressions | Synthesis feasibility, broader real-image evidence, and end-to-end parity |
| DCT transforms | Approximate fixed-point 8x8 plus reusable Q12 DCT-16, 16x8, and 8x16 primitives; all three shapes feed focused strategy scoring and a separate prepared variable-shape quantizer | 8x8 primitive/frame tests; five signed independent float and exact-fixed fixtures per new transform; axis-layout, candidate-generation, quantization, backpressure, and elaboration checks | Timing/resource architecture and composition of the prepared shapes with the RGB strategy result and tokens |
| Adaptive quantization | RGB-connected quarter-resolution contrast, block-resolution fuzzy erosion, AC-strategy reciprocal mask, signed-Q24 nonlinear `_compute_mask` seed, cumulative Y HF/color/gamma modulation, normalized exponent, distance scale/damping, completed final map, exact raw-quant conversion, post-strategy rectangle adjustment, estimated tile CFL, quantized traces, AC metadata, and focused strategy candidates | Gamma/sqrt/four-minimum, reciprocal, nonlinear, 112-edge/Q32 HF, capped Q16 coverage/Q24 color, clamped inverse-ratio/normalized-Q20-log, and normalized-Q24-`fast_pow2f` model boundaries; exact prepared fixtures and fixed-model raw bytes/maps/strategy decisions/adjusted bytes; five RGB oracle families within two percent for AQ and within two int8 units for CFL; full-frame versus stitched-tile equality; active/zero/early-return/damping distance regimes; explicit unsupported-distance fallback; exact dynamic reciprocal and prepared-handoff equivalence; 65x1 and two-dimensional 65x65-to-72x72 traversal; backpressure/control and packed-AXI/TLAST tests; one-converter elaboration | Feeding the selected rectangles and adjusted byte field into the now-available prepared variable-shape quantizer and then metadata/tokens; synthesis feasibility of the combinational square root, minima network, constant erosion division, CFL fitting divider, ratio/log/power lookup tables, multipliers, register-backed grids, and duplicated full-frame X/Y/B/coefficient buffers is unproven, while the rational transforms and HF/color/gamma/reciprocal traversals are sequential |
| Chroma from luma | Implemented for prepared-Q16 and approximate RGB-Q12 all-DCT paths; prepared DCT/16x8/8x16 quantization applies caller-supplied tile CFL | Primitive and exact fixed-model tests; five RGB/libjxl-tiny map families within two int8 units; horizontal/vertical/2D multi-tile, all-DCT integration, variable-shape quantization, stream, wrapper, and focused-core tests | Physical implementation quality, broader real-image fixtures, and feeding RGB-estimated maps into non-DCT ownership |
| AC strategy | Default all-route core remains fixed DCT; focused routes generate a full adaptive DCT/16x8/8x16 map from RGB AQ/tile CFL and its post-search adjusted raw-quant field; a separate prepared quantizer consumes first-block strategy ownership | Exact fixed-model costs for 40 oracle-backed candidates; exact fixed rectangular-transform and constant/gradient/checkerboard frame decisions; exact DCT/horizontal/vertical raw-quant adjustment; complete 2x2, incomplete 24x24 edge, 72x16 cross-tile, unsupported-distance, backpressure, RGB/core, quantization, and hierarchy checks; 520 directed/random selector transactions; 27/30 decisions match the broader float audit | Reduce/characterize the Q12 scorer discrepancy; prove or redesign wide products, lookup/square-root hardware, and duplicate frame storage; compose the search output with prepared quantization and implement variable-shape metadata/tokens |
| Distance parameters | Six Q8 lookup points plus explicit fallback | Exact RTL/host lockstep tests | General supported range or a clearly frozen discrete API |
| Quantized AC/DC/nonzero | Strong prepared-DCT all-DCT implementation plus a first-block-owned prepared DCT/16x8/8x16 boundary; RGB path remains all-DCT with approximate Q12 XYB/DCT, adaptive raw quant, and estimated tile CFL | Exact prepared-block oracles, RGB-to-prepared estimated-CFL equivalence, and 12 variable-shape fixtures exact to the frozen Q16 model with native AC within one integer and exact DC/counts | Compose the RGB strategy/CFL output with variable-shape quantization, broaden image coverage, and perform full-reference frame comparison |
| DC/AC metadata/AC logical tokens | Strong for prepared all-DCT paths; adaptive raw quant and estimated tile CFL reach RGB AC metadata, the focused core AC route, and a combined token stream | Exact context/value/order tests, prepared codestream reconstruction, RGB/prepared estimated-CFL equivalence, and packed AXI metadata/map checks | Core DC-only adaptive routing, non-DCT strategies, and RGB token-to-codestream proof |
| Entropy optimization/coding | Host-only through `libjxl-tiny` | Byte-parity assembly tests | Native host library or RTL implementation, depending final partition |
| JXL frame/codestream assembly | Host-only | Exact bytes for constrained fixtures | Standalone production integration and broader decoder validation |
| AXI-stream/AXI-Lite shells | Implemented and simulated with transactional config and discovery registers | Port, handshake, register, framing, error, discovery-readback, and mid-frame shadow-write tests | AXI protocol checker and physical integration |
| KV260 top-level shape | Generated and simulated as a flat wrapper | SystemVerilog surface, functional wrapper tests, and non-Vivado source/constraint preflight | Vivado synthesis/implementation, PS/DMA integration, and clock/reset proof |
| Synthesis/timing/utilization | Not demonstrated | None | Synthesis, reports, timing closure, resource budget |
| Bitstream and board execution | Not demonstrated | None | XSA/bitstream, driver, DMA capture, decoded-image validation |
| Performance/power | Direct prepared path characterized in simulation for sparse and maximum-density multi-tile frames | Exact phase/stall/stage-count cycle regression and 200 MHz projections | Synthesized clock, DMA overlap, end-to-end fps, and power |

The practical conclusion is that the project has demonstrated both a credible
**logical token accelerator boundary** for host-prepared all-DCT data and a
traceable approximate-RGB/adaptive-quant all-DCT path to logical tokens. It has
not demonstrated a complete or physically viable RGB-to-JXL FPGA encoder.

## Prioritized findings

### P0 — Must address before making stronger project claims

1. **Checkpoint the current work into reviewable commits.** Separate the
   estimated-CFL RTL, host-contract expansion, wrapper changes, tests, and docs.
   Run the full gate on each final slice. Until then, the best work is local-only.
   **Completed 2026-07-12:** commits `bbee6a1`, `fec2527`, and `748ee78` preserve
   the implementation as three staged-state-validated slices.
2. **State the maturity in one short authoritative status table.** The first
   screen of the README should say that the strongest boundary begins at
   prepared DCT coefficients and that Vivado/board validation has not happened.
   **Completed 2026-07-12:** the README now opens with a compact status table
   covering the strongest boundary, remaining RGB work, host/hardware split,
   selected top, and missing physical validation.
3. **Choose and freeze one near-term FPGA top.** The direct prepared-DCT top is
   the lowest-risk first synthesis target; the estimated-CFL top is more
   ambitious. Avoid growing both surfaces equally until one has timing and
   resource evidence.
   **Completed 2026-07-12:** `HjxlKv260PreparedDctTop` is the frozen first
   synthesis and bring-up target; the estimated-CFL sibling remains
   experimental until direct-top physical evidence exists.
4. **Run first physical feasibility experiments.** Add a reproducible Vivado Tcl
   flow, KV260 part/board selection, clock constraint, utilization report,
   post-synthesis timing report, and a documented resource budget. This may
   force architectural changes before more feature work is worthwhile.
   **Infrastructure added 2026-07-12:** `fpga/vivado/` now defines the direct
   top, K26/KV260 identities, 200 MHz timing contract, 70% resource budget, and
   checkpoint/utilization/timing/methodology report flow. Its non-Vivado
   preflight passes locally. Vivado execution and physical evidence remain
   outstanding because the toolchain is unavailable on this machine.

### P1 — Architecture and maintainability

5. **Replace whole-frame register arrays along the chosen top’s critical path.**
   Use inferred BRAM/URAM, tile buffers, ping-pong buffers, or external-memory
   scheduling. Define which stage owns each stored plane and whether stages can
   overlap.
   **Partially completed 2026-07-12:** the direct prepared-DCT orchestration
   stage no longer duplicates full-frame quantized AC/nonzero and metadata
   arrays already owned by its downstream schedulers. Atomic streaming removes
   6,192 register bits per configured block (99,072 bits at the frozen 32x32
   default) and shortens pre-output feed latency. The owning AC scheduler now
   serializes its frame coefficient plane into a 96-bit-wide `SyncReadMem`; the
   default generated RTL contains one 1024x96 memory instead of another 98,304
   frame-scaled coefficient register bits, and read prefetch overlaps dense
   token emission. Small nonzero/DC/metadata register planes and other frame
   schedulers still need conversion, and Vivado must confirm BRAM inference,
   utilization, timing, and the sparse-content latency tradeoff.
6. **Create an explicit throughput model.** Record cycles per input word, block,
   tile, token, and frame; initiation interval; worst-case token expansion;
   input/output bandwidth; and target clock. Add counters or a simulation
   profiler so regressions are visible.
   **Partially completed 2026-07-12:** `HjxlPreparedDctThroughputSpec` now
   profiles and locks input span/stalls, first-output latency, trace span/bubbles,
   token expansion, and total cycles for tiny, 81-block/four-tile, and maximum-
   density direct prepared-DCT frames; `docs/performance.md` records the 200 MHz
   conversion and limits. Simulation characterization is now representative of
   sparse and worst-case ordinary-DCT token expansion, but synthesis timing,
   DMA overlap, and end-to-end throughput remain open.
7. **Make control transactional.** Snapshot all configuration on frame start and
   reject writes while busy, or introduce shadow registers plus an explicit
   start/commit command. Add ABI version, build ID, capabilities, maximum frame
   dimensions, and active route registers.
   **Substantially completed 2026-07-12:** both RGB and prepared stream shells
   snapshot the complete configuration from the first accepted input through
   the accepted final trace. AXI-Lite writes during `busy` are documented and
   tested as readable next-frame shadow updates. Read-only identity, ABI
   version, contract build, capabilities, maximum geometry, and frame-stable
   active-route registers now propagate into host artifacts. An explicit
   start/commit command remains open if implicit first-beat commit proves
   insufficient for the eventual DMA driver.
8. **Generate cross-language contracts from one source.** Register offsets,
   status bits, trace stages, packing, target variants, and the prepared stream
   layout should originate in one checked schema and emit Scala/Python/C
   definitions and documentation.
   **Substantially completed 2026-07-12:** `abi/hjxl_abi.json` now generates
   checked Scala and Python bindings for registers, status/control, trace
   packing/stages, token selection, and the prepared layout; existing APIs are
   compatibility aliases and CI rejects drift. Target-variant descriptions,
   manifest-specific C output, and generated reference documentation remain to
   be folded into the schema.
9. **Refactor the wrapper matrix.** Extract reusable frame geometry, config
   latching, storage, trace packing, and AXI-Lite register-bank components.
   Organize sources into `core`, `stages`, `prepared`, `interfaces`, and
   `elaboration` packages.

### P2 — Verification and pipeline completeness

10. **Add measured verification.** Introduce randomized ready/valid stalls,
    malformed-frame sequences, mid-frame control writes, reset-at-each-state,
    capacity-edge generation, and coverage reporting. Add Chisel assertions and
    formal checks to small protocol/state modules first.
11. **Split Python tooling into a package with native tests.** Add
    `pyproject.toml`, pinned dependencies, formatter/linter/type checks, and
    focused unit tests. Keep Scala tests for RTL-to-host integration rather than
    as the primary Python test runner.
12. **Close the RGB path in earliest-mismatch order.** Improve and lock XYB,
    implement AQ, integrate prepared-CFL logic with the RGB DCT path, then add
    rectangular AC strategy. Do not use final codestream differences as the
    first diagnostic.
    **Started 2026-07-12:** the Q24 prepared-AQ conversion seam now matches
    libjxl-tiny raw-quant bytes exactly across deterministic patterns and
    distances. The upstream image heuristics and pipeline integration remain
    open.
    **XYB follow-up 2026-07-13:** signed mixing now clamps after bias like the
    reference, Q24 absorbance plus normalized cube-root interpolation removes
    the former range saturation, and independent fixtures lock the Q12 error
    bound.
    **AQ contrast follow-up 2026-07-13:** the RGB core now computes the global
    quarter-resolution Q16 pre-erosion contrast grid with a shared fixed-point
    datapath, and five libjxl-tiny fixture families bound the cell error to two
    percent. An explicit selector preserves the prior default-selector route,
    and a 65x1 regression crosses the 64-pixel tile boundary at a carry-safe
    72-pixel padded width.
    **Fuzzy-erosion follow-up 2026-07-13:** a prepared Q16 scheduler now matches
    the full reference intermediate within one LSB across five patterns and a
    two-dimensional 65x65-to-72x72 traversal. The RGB-composed focused route
    reuses the contrast stage, stays within two percent, and preserves the
    default all-route ABI behavior. The strategy-mask/nonlinear-mask transforms
    were the next earliest mismatch.
    **Strategy-mask follow-up 2026-07-13:** a 42-cycle restoring reciprocal now
    emits the Q16 mask used by AC-strategy scoring, with exact prepared fixture
    values and full-frame equality against encoder-style stitched tile calls.
    The composed RGB route remains within two percent and crosses both 65x1 and
    65x65 boundaries. The separate nonlinear `_compute_mask` that begins final
    AQ-map modulation is now the next earliest mismatch; real rectangular
    strategy scoring also remains downstream.
    **Nonlinear-mask follow-up 2026-07-13:** the parallel final-map branch now
    evaluates `_compute_mask` from positive Q16 erosion to a signed-Q24 log-
    domain seed. One 56-cycle divider is shared across three rational terms for
    a 174-cycle evaluator. The independent exporter matches the real private
    function, proves full-frame equality with stitched 64x64 calls, and covers
    negative trace packing plus 65x1/65x65 traversal. HF, color, gamma,
    power/scale, and final-map assembly are now the next earliest mismatch.
    **HF-modulation follow-up 2026-07-13:** the RGB path now continues through
    `_hf_modulation`, accumulating all 112 internal edges of each signed-Q12 Y
    block over exactly 64 cycles and applying a Q32 coefficient to the signed-
    Q24 seed. A passive accepted-XYB tap keeps the composed top to one converter.
    The independent exporter matches the private float32 function, proves
    stitched 64x64 equality, and covers Q8-input/Q12 prepared fixtures,
    saturation, backpressure, 65x1, and 65x65 order. Color modulation is now the
    next earliest mismatch, followed by gamma and power/scale assembly.
    **Color-modulation follow-up 2026-07-13:** the RGB path now continues through
    `_color_modulation`. One shared scheduler captures padded XYB blocks for the
    cumulative pipeline; the focused HF route specializes it to Y-only storage,
    while the color route retains X/Y/B and pipelines the 64-cycle HF and color
    walkers. The color block uses capped Q16 ramp coverage and distance-folded
    Q24 coefficients with no runtime divider, including the exact reference
    bypass above distance 4. The independent exporter matches the private
    float32 call, proves stitched 64x64 equality, and covers five patterns,
    signed saturation, backpressure, 65x1, and 65x65 order. Gamma modulation is
    now the next earliest mismatch, followed by power/scale and final-map
    assembly.
    **Gamma-modulation follow-up 2026-07-13:** the RGB path now continues through
    `_gamma_modulation`. A reusable HF/color block pipeline retains X/Y for the
    128-cycle gamma walker without repeating conversion or frame storage. Fine,
    coarse, and HDR Q20 tables approximate each inverse response over the
    explicit `[0, 8]` prepared range; a normalized Q20 table follows the
    reference `fast_log2f`, and the stage contains no runtime divider. The
    independent exporter matches the private float32 call exactly, proves
    stitched 64x64 equality, and covers ratio/log seams, signed saturation,
    backpressure, five patterns, 65x1, and 65x65 order. Power/scale and final-
    map assembly are now the next earliest mismatch.
    **Final-map follow-up 2026-07-13:** the focused RGB path now completes
    `_per_block_modulations` through a normalized 257-entry Q24 `fast_pow2f`
    table, distance-derived scale/damping/base addition, and the existing exact
    raw-quant converter. Unsupported distances consistently fall back to the
    shared distance-1 parameters before color/final arithmetic. The final-map
    oracle matches libjxl-tiny exactly before fixed quantization; deterministic
    focused tests cover exponent seams, distance-7/14 damping, prepared exact
    maps, five RGB families, exact fixed-model raw bytes at distances 0.5/1/8, fixed
    override, backpressure, 65x1, and 65x65 order. The next earliest integration
    mismatch is feeding these adaptive bytes into the RGB DCT quantizer and AC
    metadata/token schedulers; real rectangular strategy scoring remains open.
    **Quantization/token integration follow-up 2026-07-13:** one retained Q12
    X/Y/B block now feeds three shared DCTs and the exact prepared quantizer,
    with a 33-cycle restoring divider recomputing the inverse AC scale for each
    adaptive byte. The core quantized and AC-metadata routes consume adaptive
    raw quant; a standalone wrapper emits the combined DC/strategy/metadata/AC
    token stream. Exhaustive supported/custom-scale reciprocal tests, fixed-
    override and live-control retention, RGB-versus-prepared trace equality,
    65x1 traversal, final-only AXI TLAST, and structural elaboration are covered.
    Metadata intentionally bypasses DCT/reciprocal hardware. RGB-derived CFL,
    rectangular strategy scoring, and RGB-token codestream proof are now the
    next earliest mismatches.
    **RGB-derived CFL follow-up 2026-07-13:** the completed AQ block stream is
    now split at a reusable native-Q12 DCT boundary so map/metadata routes avoid
    unused reciprocal hardware. Q12 coefficients widen to Q16 only at the
    prepared CFL estimator; the prepared stream ABI remains Q16. The core
    quantized, AC-metadata, focused CFL-map, and focused AC-token routes now use
    estimated tile maps, while the earlier scalar-CFL wrappers remain explicit
    diagnostics. Five deterministic 8x8 families at varied distances match an
    independent integer model exactly and remain within two signed int8 units
    of libjxl-tiny; a 65x1 case proves two-tile ordering, and RGB quantized,
    metadata, and combined-token streams exactly match the same Q12 prepared
    handoff. Horizontal, vertical, 2D, AXI, KV260-wrapper, backpressure, TLAST,
    and structural regressions preserve the prepared-Q16 path. Rectangular
    strategy scoring is now the next earliest image-stage mismatch; the RGB
    combined token stream still lacks a direct codestream/decoder proof.
    **Rectangular-strategy prerequisite follow-up 2026-07-13:** reusable Q12
    DCT-16, 16x8, and 8x16 primitives now reproduce libjxl-tiny's two distinct
    canonical rectangular layouts within two Q12 units across constant, axis
    ramp, impulse, and signed fixtures. The exact 2x2 selector separately
    covers aggregate orientation, horizontal tie behavior, strict subregion
    replacement, and first/continuation encoding over caller-supplied costs.
    At that checkpoint, the public strategy route was still fixed DCT and the
    entropy-cost arithmetic, tile/frame scheduler, adjusted-quant interaction,
    and downstream rectangular quantization/tokenization remained open.
    **Prepared strategy-cost follow-up 2026-07-13:**
    `AcStrategyCandidateCostEvaluator` now walks canonical Q12 coefficients for
    DCT/16x8/8x16 candidates, reproduces the integer entropy/loss model exactly,
    applies six distance-specific outer multipliers, and reports fallback or
    overflow. `PreparedAcStrategy2x2Selector` enforces the eight-candidate
    order and holds 64-bit costs plus the exact decision under backpressure.
    Automated fixtures cover 40 candidates across five patterns and varied
    distances; a broader 30-region audit matches the float reference decision
    in 27 cases. The three misses are checkerboard orientation changes at
    distances 0.5, 4, and 8, and the worst audited candidate-cost delta is
    14.62 percent, so the prepared Q12 seam is not claimed as float parity.
    **Adaptive strategy-frame follow-up 2026-07-13:**
    `FramePreparedAcStrategyTraceStage` now stores Q12 XYB/DCT, AQ, and mask
    blocks; estimates CFL independently for each 64x64 tile; forms the exact
    four DCT/two 16x8/two 8x16 candidate order; applies covered-block maxima;
    and emits the complete raster map after tile-local 2x2 traversal. Exact
    integer transform/CFL oracle logic distinguishes the scheduler-fixed result
    from the earlier rounded-float candidate fixture. Constant and gradient
    decisions match that model; 24x24 and 72x16 regressions prove incomplete
    edges remain DCT and rectangles never cross tiles; backpressure and
    unsupported-distance diagnostics are covered. The shared RGB AQ/DCT source
    carries the original strategy mask and effective distance into a focused
    core route with one converter and three ordinary DCTs. The default all-route
    shell intentionally stays fixed DCT. Adjusting raw quant and driving
    rectangular quantization, metadata, and tokens remain the next earliest
    mismatch.
    **Strategy-adjusted raw-quant follow-up 2026-07-13:** the prepared strategy
    scheduler now stores each pre-strategy byte and, after the search, follows
    the raster first-block map exactly: DCT leaves one byte unchanged, 8x16
    raises the current/right pair to their maximum, and 16x8 does the same for
    the current/below pair. Continuation cells never act. The adjusted byte is
    emitted beside its strategy record and remains stable with that record
    under output stalls. Prepared tests independently cover DCT, horizontal,
    and vertical decisions; arbitrary bytes `[2, 9, 7, 4]` prove both
    orientations, while the RGB checkerboard oracle converts the real
    pre-strategy field `[4, 5, 5, 4]` to `[5, 5, 5, 5]`. The existing 24x24
    incomplete-edge and 72x16 cross-tile cases also verify byte adjustment and
    bounds. `FrameAqAdjustedRawQuantTraceStage` exposes the aligned sideband,
    and the focused public `RawQuantField` core route uses it at zero override;
    a uniform fixed override keeps the inexpensive path. The remaining earliest
    mismatch is consuming the selected rectangles and adjusted bytes in
    quantization, metadata, and token scheduling.
    **Non-DCT integration audit 2026-07-13:** the reference and current RTL
    interfaces were traced through quantization, DC layout, nonzero prediction,
    metadata, and coefficient tokenization. A rectangular block cannot be
    safely bolted onto `DctOnlyQuantizeBlock`: the frame schedulers currently
    require one 64-coefficient X/Y/B record per raster cell, emit a strategy and
    quant-field token for every cell, and use ordinary-DCT coefficient order and
    contexts. The reference instead gives one first block a 128-coefficient
    payload, derives two DC cells, replicates a shifted nonzero count for
    prediction while tokenizing the raw count, and suppresses continuation-cell
    metadata and AC ownership. Implement the first-block-owned prepared record
    described in the architecture assessment before composing the RGB search
    with quantization; validate the primitive and frame maps independently,
    then extend metadata and AC scanning, and only then add a combined token
    route. This sequencing preserves the project's earliest-mismatch discipline
    and avoids a second adaptive full-frame source.
    **Prepared variable-shape quantization follow-up 2026-07-14:** that first-
    block-owned seam is now implemented. `VarDctQuantizeBlock` accepts one
    canonical Q16 X/Y/B payload with 64 DCT or 128 rectangular coefficients,
    performs Y reconstruction and CFL residual quantization, derives one or two
    raster-ordered DC cells, and exposes both the raw token-prefix count and the
    shifted prediction-map count. `FramePreparedVarDctQuantizeStage` consumes
    owners only, maps a 16x8 continuation below and an 8x16 continuation right,
    rejects gaps, overlaps, bounds errors, and bad final markers, and recovers
    after dropping a malformed frame. Twelve DCT/16x8/8x16 fixtures across four
    image/distance/quant/CFL combinations match the frozen Q16 model exactly;
    against native-float libjxl-tiny, AC differs by at most one integer while DC
    and both count forms match exactly. A review-driven refactor serializes Y
    reconstruction over 64 or 128 cycles, reducing emitted dynamic quant-bias
    division from 128 operators to one. The generated five-file hierarchy is
    13,363 lines, but still contains three combinational 128-lane AC quantizers,
    so timing and resource feasibility remain unproven. The next earliest
    mismatch is composing the RGB search with this seam, followed by first-
    block-aware metadata and rectangular AC scanning.
13. **Expand oracle diversity.** Add several deterministic patterns, signed and
    near-saturation values, supported distances, non-block/tile-aligned sizes,
    multi-tile 2D images, and at least a few small real-image crops. Validate
    emitted codestreams with an independent JPEG XL decoder in addition to
    byte parity with the assembly oracle.

### P3 — Board and release readiness

14. Add Vivado block-design/DMA integration, host driver code, cache coherency
    rules, timeout/error recovery, repeat-frame operation, and board-captured
    trace tests.
15. Define release artifacts and compatibility: generated RTL, schema/header,
    host package, example bundle, known-good bitstream/XSA, board instructions,
    changelog, and signed or checksummed outputs.
16. Only after timing and correctness are stable, decide whether entropy coding
    stays in host software or moves into RTL. The present split is reasonable;
    hardware entropy coding should not block a useful accelerator milestone.

## Verification performed for this assessment

The following local checks were run immediately before committing the complete
implementation and documentation tree described by this assessment. The
2026-07-14 prepared variable-shape follow-up was validated from the complete
working tree rather than inferred from its component tests:

- `git diff --check` — passed.
- `python3 -m py_compile tools/*.py` — passed.
- `python3 tools/hjxl_generate_abi.py --check` — passed.
- `python3 tools/hjxl_host_metadata_smoke.py` — passed.
- `sbt test` — passed: 81 suites completed, 324 tests succeeded, 0
  failed/canceled/ignored/pending, in 13 minutes 21 seconds.
- `HJXL_REPO_ROOT=$PWD ./mill --no-server hjxl.test` — passed the complete same
  81-suite/324-test tree in 780 seconds, confirming the sources and tests
  through the second build definition. Its forked workers reported zero
  failures, aborts, cancellations, ignored tests, or pending tests.
- A final focused rerun of `FramePreparedAcStrategyTraceStageSpec` after the
  review cleanup passed all 7 tests in 61 seconds.
- The adaptive strategy-frame coverage included exact constant, gradient, and
  checkerboard decisions against the independent integer scheduler model;
  exact DCT/horizontal/vertical post-search raw-quant adjustment; aligned
  sideband stability under backpressure; complete 2x2 selection; 24x24
  incomplete-edge behavior; 72x16 tile-boundary behavior; unsupported-distance
  diagnostics; focused RGB/core routing; and packed AXI-stream TLAST. The
  checkerboard RGB case verifies `[4, 5, 5, 4]` becomes `[5, 5, 5, 5]`. The
  nonlinear AQ regression also checks the
  exact reciprocal sideband against the value actually emitted by the
  intentionally approximate RGB erosion path, avoiding a false exact claim at
  that seam.
- Focused sbt execution of the new variable-shape primitive/frame/elaboration
  suites plus the existing ordinary/rectangular transform and prepared-DCT
  quantization/elaboration suites — passed: 6 suites and 20 tests. The new 12
  DCT/16x8/8x16 prepared fixtures cover four pattern/distance/quant/CFL
  combinations, both orientation maps, raw versus shifted nonzero counts,
  stalls, malformed ownership, sticky overflow, and recovery.
- `sbt 'runMain hjxl.ElaboratePreparedVarDctQuantize'` — passed and emitted 5
  SystemVerilog files totaling 13,363 lines. Inspection and an automated
  elaboration assertion find exactly one dynamic quant-bias division; the
  constant CFL `/ 84` expression remains separate. This is structural evidence
  only: three combinational 128-lane AC quantizers remain, and no synthesis,
  timing, or resource report exists for this top.
- Focused sbt execution of the prepared AC-strategy candidate evaluator,
  distance lookup/fallback, extreme-range saturation, eight-candidate
  sequencer, backpressure, and elaboration suites — passed: 2 suites and 6
  tests. Five pattern/distance fixtures cover 40 candidates with exact integer
  oracle results; the separate 30-region float audit records 27 matching
  decisions and the documented 14.62-percent worst cost delta.
- Focused sbt execution of the DCT-8/DCT-16/rectangular transform, exact 2x2
  strategy selector, and rectangular elaboration suites — passed: 5 suites and
  8 tests. The generated signed Q12 oracle covers five cases per transform;
  both rectangular orientations remain within two Q12 units of libjxl-tiny.
- `sbt 'runMain hjxl.ElaboratePreparedAcStrategy'`,
  `sbt 'runMain hjxl.ElaborateAqAcStrategy'`, and
  `sbt 'runMain hjxl.ElaborateCoreAcStrategy'` — passed. They emitted,
  respectively, 14 files/38,581 lines, 44 files/77,614 lines, and 44
  files/77,186 lines of SystemVerilog. The prepared hierarchy contains the
  selector, scorer, tile CFL estimator, and both rectangular transforms; the
  RGB and core hierarchies add one RGB-to-XYB module and one ordinary-DCT
  module instantiated for all three channels. These large structural counts
  reinforce the need for synthesis/resource work; elaboration is not evidence
  of timing closure or fit.
- `sbt 'runMain hjxl.ElaborateAqAdjustedRawQuant'` and
  `sbt 'runMain hjxl.ElaborateCoreRawQuant'` — passed and emitted 47
  files/77,857 lines and 48 files/77,919 lines of SystemVerilog. Both
  hierarchies contain the adaptive strategy scheduler, CFL estimator, ordinary
  and rectangular transforms, and one RGB-to-XYB converter; the focused core
  also contains the cheap fixed override. These large trace-oriented artifacts
  are structural evidence only, not proof that the route will fit or time on
  the KV260.
- Focused sbt execution of the RGB/prepared estimated-CFL map, quantization,
  metadata, token, core, AXI/AXI-Lite elaboration, prepared stream, and KV260
  wrapper suites — passed: 15 suites and 93 tests. The complete sbt and Mill
  runs cover the same changes in the full repository tree.
- `sbt 'runMain hjxl.ElaborateAqFinalMap'` — passed and emitted 26 SystemVerilog
  files totaling 32,804 lines. `sbt 'runMain hjxl.ElaborateAqRawQuant'` also
  passed and emitted 27 files totaling 32,777 lines. Each composed hierarchy
  contains one RGB-to-XYB converter; the new exponent/final-map modules have no
  division operator. These checks prove elaboration and structural intent only;
  they do not establish timing or resource feasibility for the upstream fixed
  erosion divide, minima network, square root, lookup tables, multipliers, or
  register-backed full-frame grids.
- `sbt 'runMain hjxl.ElaborateAqDctOnlyQuantize'`,
  `sbt 'runMain hjxl.ElaborateAqDctOnlyAcMetadataTokens'`, and
  `sbt 'runMain hjxl.ElaborateAqDctOnlyQuantizeTokens'` — passed. They emitted,
  respectively, 37 files/46,043 lines, 28 files/33,198 lines, and 45
  files/51,960 lines of SystemVerilog. The quantization and combined-token
  hierarchies each contain one RGB-to-XYB converter and three DCT instances;
  the metadata-only hierarchy contains one converter and no DCT or reciprocal
  divider. The adaptive reciprocal is a restoring divider and the emitted
  hierarchy contains no division operator. These are elaboration and
  structural checks, not timing or resource results.
- `sbt 'runMain hjxl.ElaborateAqCflMaps'`,
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantize'`,
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyAcMetadataTokens'`, and
  `sbt 'runMain hjxl.ElaborateAqCflDctOnlyQuantizeTokens'` — passed. They
  emitted, respectively, 34 files/46,918 lines, 45 files/67,202 lines, 38
  files/49,346 lines, and 53 files/82,380 lines of SystemVerilog. Each contains
  one RGB-to-XYB instance, three DCT instances, and the CFL tile estimator;
  only the quantization and combined-token hierarchies contain
  `AdaptiveInvQacQ16`.
  These counts prove elaboration and intended module selection, not synthesis
  resource use, timing, or physical feasibility.
- `sbt 'runMain hjxl.ElaborateKv260PreparedDctTop'` followed by
  `tclsh fpga/vivado/synth.tcl --preflight-only` — passed with the expected 19
  generated RTL files. This is a source/constraint preflight, not synthesis.
- Tool availability — Java, sbt, and Verilator 5.048 were found; Vivado, Vitis,
  and Yosys were not found.

No claim is made here about synthesis, timing closure, resource use, bitstream
generation, DMA operation, or KV260 hardware execution.

## Final assessment

The project is better engineered than its completeness level might suggest.
Its scope discipline, prepared-data seams, trace vocabulary, oracle integration,
error-path validation, and refusal to overclaim approximate stages are all
excellent foundations. The team should preserve those qualities.

The next quality milestone should not be another broad layer of wrappers or
host metadata. It should be a narrow, reproducible proof that one chosen
prepared-DCT top synthesizes, fits, meets a stated clock, transfers a frame
through a real KV260 DMA path, and reconstructs the expected codestream from a
captured trace. That experiment will turn the current verification architecture
into evidence about the intended hardware product—and will reveal which of the
current simulation-friendly structures must be redesigned before the remaining
JPEG XL stages are added.
