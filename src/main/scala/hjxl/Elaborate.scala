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

object ElaboratePreparedAqRawQuant extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedAqRawQuantTraceStage(),
    args = Array("--target-dir", "generated-prepared-aq-raw-quant"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedAqFinalModulation extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedAqFinalModulationTraceStage(),
    args = Array("--target-dir", "generated-prepared-aq-final-modulation"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqContrast extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqContrastTraceStage(),
    args = Array("--target-dir", "generated-aq-contrast"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqFuzzyErosion extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqFuzzyErosionTraceStage(),
    args = Array("--target-dir", "generated-aq-fuzzy-erosion"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqStrategyMask extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqStrategyMaskTraceStage(),
    args = Array("--target-dir", "generated-aq-strategy-mask"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqNonlinearMask extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqNonlinearMaskTraceStage(),
    args = Array("--target-dir", "generated-aq-nonlinear-mask"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqHfModulation extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqHfModulationTraceStage(),
    args = Array("--target-dir", "generated-aq-hf-modulation"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqColorModulation extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqColorModulationTraceStage(),
    args = Array("--target-dir", "generated-aq-color-modulation"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqGammaModulation extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqGammaModulationTraceStage(),
    args = Array("--target-dir", "generated-aq-gamma-modulation"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqFinalMap extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqFinalMapTraceStage(),
    args = Array("--target-dir", "generated-aq-final-map"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqRawQuant extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqRawQuantTraceStage(),
    args = Array("--target-dir", "generated-aq-raw-quant"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqDctOnlyQuantize extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqDctOnlyQuantizeTraceStage(),
    args = Array("--target-dir", "generated-aq-dct-only-quantize"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqDctOnlyAcMetadataTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqDctOnlyAcMetadataTokenTraceStage(),
    args = Array("--target-dir", "generated-aq-dct-only-ac-metadata-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateAqDctOnlyQuantizeTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrameAqDctOnlyQuantizeTokenTraceStage(),
    args = Array("--target-dir", "generated-aq-dct-only-quantize-tokens"),
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

object ElaborateKv260PreparedCflDctTop extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlKv260PreparedCflDctTop(),
    args = Array("--target-dir", "generated-kv260-prepared-cfl-dct-top"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaborateCflTileCoefficientTrace extends App {
  ChiselStage.emitSystemVerilogFile(
    new CflTileCoefficientTraceStage(),
    args = Array("--target-dir", "generated-cfl-tile-coefficient-trace"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflMapTrace extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedCflMapTraceStage(),
    args = Array("--target-dir", "generated-prepared-cfl-map-trace"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflDctOnlyQuantize extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedCflDctOnlyQuantizeTraceStage(),
    args = Array("--target-dir", "generated-prepared-cfl-dct-only-quantize"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflDctOnlyQuantizeTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedCflDctOnlyQuantizeTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-cfl-dct-only-quantize-tokens"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflDctAxiStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlPreparedCflDctAxiStreamCore(),
    args = Array("--target-dir", "generated-prepared-cfl-dct-axi-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflDctAxiLiteStream extends App {
  ChiselStage.emitSystemVerilogFile(
    new HjxlPreparedCflDctAxiLiteStreamCore(),
    args = Array("--target-dir", "generated-prepared-cfl-dct-axi-lite-stream"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}

object ElaboratePreparedCflAcMetadataTokens extends App {
  ChiselStage.emitSystemVerilogFile(
    new FramePreparedCflAcMetadataTokenTraceStage(),
    args = Array("--target-dir", "generated-prepared-cfl-ac-metadata-tokens"),
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
