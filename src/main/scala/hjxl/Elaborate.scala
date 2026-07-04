// See README.md for license details.

package hjxl

import _root_.circt.stage.ChiselStage

object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlCore(),
    args = Array("--target-dir", "generated"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlAcTokenCore(),
    args = Array("--target-dir", "generated-ac-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateCoreAcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlCore(traceRoute = TraceStage.AcTokens),
    args = Array("--target-dir", "generated-core-ac-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAxiStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlAxiStreamCore(),
    args = Array("--target-dir", "generated-axi-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAxiStreamCoreAcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlAxiStreamCore(traceRoute = TraceStage.AcTokens),
    args = Array("--target-dir", "generated-axi-stream-core-ac-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAxiLiteStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlAxiLiteStreamCore(),
    args = Array("--target-dir", "generated-axi-lite-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAxiLiteStreamCoreAcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlAxiLiteStreamCore(traceRoute = TraceStage.AcTokens),
    args = Array("--target-dir", "generated-axi-lite-stream-core-ac-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedDcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedDcTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-dc-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedDctOnlyQuantize extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedDctOnlyQuantizeTraceStage(),
    args = Array("--target-dir", "generated-prepared-dct-only-quantize"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedDctOnlyQuantizeTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedDctOnlyQuantizeTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-dct-only-quantize-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedDctAxiStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlPreparedDctAxiStreamCore(),
    args = Array("--target-dir", "generated-prepared-dct-axi-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedDctAxiLiteStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlPreparedDctAxiLiteStreamCore(),
    args = Array("--target-dir", "generated-prepared-dct-axi-lite-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateKv260PreparedDctTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlKv260PreparedDctTop(),
    args = Array("--target-dir", "generated-kv260-prepared-dct-top"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedAcMetadataTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedAcMetadataTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-ac-metadata-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedAcTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedAcTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-ac-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}
