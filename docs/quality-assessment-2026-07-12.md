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
utilization, timing, clock-rate, throughput, power, Vivado-project, bitstream,
or board-run results. The RGB path uses approximate XYB and fixed all-8x8-DCT
behavior. A standalone prepared-AQ boundary exactly converts real libjxl-tiny
AQ maps from Q24 into raw-quant traces, but the image-dependent AQ-map
heuristics and integration remain software-only. Entropy coding and bitstream
assembly also remain software-only, and the most parity-ready hardware
interface starts after the host has already computed prepared DCT blocks.

The current working tree also needs to be treated as an engineering risk in its
own right. At the start of this review, before this report was added, it
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
| Release/reproducibility readiness of this snapshot | **Weak because of the large uncommitted delta** |

As an indicative score, the project is about **6.5/10 overall today**: closer
to 8/10 as a correctness-oriented RTL exploration environment, but closer to
2–3/10 as a demonstrated FPGA JPEG XL encoder. Those numbers deliberately
separate code quality from product completeness.

## Scope and method

This assessment covers the current checkout, including its uncommitted files,
not only commit `06f6886` on `main`. The review included:

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

Snapshot size is approximately:

| Area | Files | Lines |
| --- | ---: | ---: |
| Main HJXL Scala/Chisel | 44 | 7,527 |
| Scala tests | 52 | 17,314 |
| Python host/oracle tools | 14 | 9,675 |
| `README.md` + `docs/architecture.md` + `AGENTS.md` | 3 | 2,958 |

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

4. **Control-plane selection is too implicit.** `HjxlCore` combines compile-time
   `traceRoute` selection with a priority tree derived from runtime feature
   flags. Some metadata routes are intentionally meaningful only in focused
   elaborations. This works, but it is difficult to reason about and easy for a
   host to misconfigure. A single explicit runtime route enum, plus a capability
   register describing the elaborated route, would be clearer.

5. **Configuration lifetime is not enforced.** AXI-Lite registers remain
   writable while a frame is busy, while top-level route selection and some
   stage inputs are driven from live configuration. Several inner schedulers
   latch relevant values, but the public contract does not consistently reject,
   defer, or snapshot mid-frame writes. The host is implicitly expected to
   configure only while idle. That rule should be enforced in RTL or made an
   explicit protocol error.

6. **There is not yet a versioned hardware discovery contract.** Host manifests
   have format strings and extensive metadata, but the hardware register map
   exposes no ABI version, build ID, feature bitmap, maximum dimensions, or
   stream-layout version. Those become important as direct and estimated-CFL
   variants diverge while retaining a compatible 201-word input shape.

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

- The package is flat: 44 HJXL source files and 52 test files all live in one
  namespace/directory. The naming remains navigable today, but the many
  `FrameDctOnly*`, `FramePrepared*`, `FramePreparedCfl*`, stream, controlled-
  stream, and KV260 variants are beginning to form a combinatorial wrapper
  matrix. Subpackages or composition around shared schedulers would reduce the
  cognitive load.
- Frame buffering and traversal logic is repeated across many schedulers. The
  extraction of `CflTileGeometry` is a good example of the refactoring pattern
  that should continue for frame geometry, capacity checking, config latching,
  trace emission, and prepared-block storage.
- `HjxlCore` is almost 300 lines largely because every route repeats wiring and
  mux participation. `Elaborate.scala` is another near-mechanical 293-line
  matrix. These are maintainable only while every new route is changed in all
  required places.
- There are no in-RTL assertions in the main sources for protocol invariants;
  checking is implemented through status signals and external tests. Assertions
  for accepted-beat counts, legal states, stable output under stall, final-beat
  relationships, and index bounds would improve debug and formal-readiness.
- Some combinational expressions use values that are only meaningful in active
  states—for example frame traversal arithmetic before nonzero dimensions have
  been latched. Invalid outputs are normally masked by `valid`, but safe
  denominators and explicit state guards would generate cleaner hardware and
  reduce X-propagation risk.
- There is no style formatter or static-analysis gate for Scala or Python. The
  code is currently consistent by discipline, not automation.

## Verification and correctness

Verification is the project’s strongest quality dimension.

### Strong evidence

- The current tree has roughly 17.3k lines of Scala tests for 7.5k lines of
  RTL, plus extensive validation inside the Python tools.
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
  latency. On this machine, the single `sbt test` run for this assessment took
  21 minutes 17 seconds, with many separate Verilator models built by the
  simulator-backed suites.
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

- Python tooling is about 9.7k lines with no `pyproject.toml`, package layout,
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

- The README is 1,007 lines, the architecture document 963 lines, and the agent
  guide 988 lines. All three repeat long inventories of nearly every module,
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
- The history contains 23 commits from July 3–4, 2026, then a very large
  uncommitted development wave. The project has no tags or releases.
- CI has no formatter, lint, warnings-as-errors, coverage, generated-contract
  drift gate, synthesis smoke, or artifact publication.
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
| RGB to XYB | Approximate Q8 to Q12 implementation | Directed tolerance tests and frame traces | Accuracy tuning, range analysis, synthesis feasibility, end-to-end parity |
| 8x8 DCT | Approximate fixed-point implementation | Primitive and frame tests | Timing/resource architecture and rectangular transforms |
| Adaptive quantization | Final prepared Q24 AQ-map to raw-quant conversion implemented; image heuristics remain software-only | Exact multi-pattern/distance oracle, clamp, ordering, backpressure, control-lifetime, and elaboration tests | Contrast/erosion/HF/color/gamma pipeline, RGB integration, and downstream map plumbing |
| Chroma from luma | Implemented substantially for **prepared DCT** estimated-CFL paths | Primitive, multi-tile, quantization, metadata, stream, and wrapper tests | RGB-path integration, physical implementation quality, broader fixtures |
| AC strategy | Fixed ordinary 8x8 DCT only | Exact map shape/order tests | 16x8/8x16 search, first-block semantics across strategies, scheduling |
| Distance parameters | Six Q8 lookup points plus explicit fallback | Exact RTL/host lockstep tests | General supported range or a clearly frozen discrete API |
| Quantized AC/DC/nonzero | Strong prepared-DCT all-DCT implementation; RGB path remains approximate/fixed | Exact prepared-block oracle comparisons | AQ/strategy integration and broader image coverage |
| DC/AC metadata/AC logical tokens | Strong for prepared all-DCT paths | Exact context/value/order tests and codestream reconstruction | Full adaptive RGB path and non-DCT strategies |
| Entropy optimization/coding | Host-only through `libjxl-tiny` | Byte-parity assembly tests | Native host library or RTL implementation, depending final partition |
| JXL frame/codestream assembly | Host-only | Exact bytes for constrained fixtures | Standalone production integration and broader decoder validation |
| AXI-stream/AXI-Lite shells | Implemented and simulated with transactional config and discovery registers | Port, handshake, register, framing, error, discovery-readback, and mid-frame shadow-write tests | AXI protocol checker and physical integration |
| KV260 top-level shape | Generated and simulated as a flat wrapper | SystemVerilog surface and functional wrapper tests | Vivado project/Tcl/XDC, PS/DMA integration, clock/reset proof |
| Synthesis/timing/utilization | Not demonstrated | None | Synthesis, reports, timing closure, resource budget |
| Bitstream and board execution | Not demonstrated | None | XSA/bitstream, driver, DMA capture, decoded-image validation |
| Performance/power | Direct prepared path characterized in simulation for sparse and maximum-density multi-tile frames | Exact phase/stall/stage-count cycle regression and 200 MHz projections | Synthesized clock, DMA overlap, end-to-end fps, and power |

The practical conclusion is that the project has demonstrated a credible
**logical token accelerator boundary** for host-prepared all-DCT data. It has
not demonstrated a complete RGB-to-JXL FPGA encoder.

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

The following local checks were run against the current working tree:

- `git diff --check` — passed.
- `python3 -m py_compile tools/*.py` — passed.
- `python3 tools/hjxl_generate_abi.py --check` — passed.
- `python3 tools/hjxl_host_metadata_smoke.py` — passed.
- `sbt test` — passed: 56 suites completed, 199 tests succeeded, 0
  failed/canceled/ignored/pending, in 15 minutes 47 seconds.
- `HJXL_REPO_ROOT=$PWD ./mill hjxl.compile` and focused Mill execution of
  `AdaptiveQuantizationSpec`/`AdaptiveQuantizationElaborationSpec` — passed,
  confirming the new source and tests through the second build definition.
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
