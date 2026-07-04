// See README.md for license details.

package hjxl

import chisel3._

class AcQuantScaleParams extends Bundle {
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
  val rawQuant = UInt(8.W)
}

/** Selects AC quantization scale parameters for fixed DCT-only frame paths.
  *
  * A zero `fixedPointScale` means use libjxl-tiny distance-derived parameters.
  * A nonzero `fixedPointScale` is a trace-experiment override, and the caller
  * must provide the matching reciprocal in `fixedInvQacQ16`. A zero
  * `fixedRawQuant` keeps the current fixed-path adjusted raw quant value 5; a
  * nonzero value overrides it and also requires `fixedInvQacQ16`.
  */
class AcQuantScaleSelector(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val distance = Input(new DistanceParamsOutput)
    val params = Output(new AcQuantScaleParams)
  })

  val useDistanceScale = io.config.fixedPointScale === 0.U
  val useDefaultRawQuant = io.config.fixedRawQuant === 0.U
  val useDistanceReciprocal = useDistanceScale && useDefaultRawQuant

  io.params.scaleQ16 := Mux(useDistanceScale, io.distance.scaleQ16, io.config.fixedPointScale)
  io.params.invQacQ16 := Mux(useDistanceReciprocal, io.distance.invQacQ16, io.config.fixedInvQacQ16)
  io.params.rawQuant := Mux(useDefaultRawQuant, QuantizeDct8x8Block.DefaultRawQuant.U, io.config.fixedRawQuant)
}
