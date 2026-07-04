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
