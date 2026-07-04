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
