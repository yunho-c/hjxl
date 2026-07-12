# HJXL ABI source

`hjxl_abi.json` is authoritative for stable host/RTL constants shared by Scala
and Python:

- AXI-Lite address/data geometry, register offsets, and status/control bits;
- read-only identity/version/build, capability profiles, capacity, and route
  discovery semantics;
- packed trace field widths, shifts, stages, and KV260 capture word size;
- token-route selection values;
- direct prepared-DCT scalar offsets and the 201-word block layout.

Regenerate the checked-in language bindings after editing the schema:

```sh
python3 tools/hjxl_generate_abi.py
```

This updates `src/main/scala/hjxl/HjxlAbiGenerated.scala` and
`tools/hjxl_abi_generated.py`. Never edit those files directly. Verify a
checkout or staged change with:

```sh
python3 tools/hjxl_generate_abi.py --check
```

CI runs the drift check. Existing Scala objects such as
`HjxlAxiLiteRegister`, `HjxlStatusControlBit`, `TraceStage`,
`TokenTraceSelect`, and `PreparedDctStreamLayout` remain public compatibility
aliases over generated values.

The ABI version is packed as `major << 16 | minor`. Increment the major version
for an incompatible host contract and the minor version for a backward-compatible
extension. `build_id` identifies the checked contract release and must change
when observable register/stream semantics change without an ABI-version bump;
it is not a Git commit hash. Capability profiles define the minimum bits a host
must observe for the RGB, direct prepared-DCT, and estimated-CFL targets.

Not every project constant belongs in this ABI. Parameterized RTL limits,
fixture-specific values, distance lookup tables, target-variant descriptions,
and evolving algorithm constants remain in their owning code until they need a
stable cross-language contract. Generated C headers continue to be
manifest-specific products of `tools/hjxl_manifest_header.py`; that tool now
consumes the generated Python ABI constants.
