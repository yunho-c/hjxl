// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

case class DistanceParamsEntry(
    distanceQ8: Int,
    scaleQ16: Int,
    invQacQ16: Int,
    aqScaleQ24: Int,
    aqDampenQ24: Int,
    aqInvGlobalScaleQ24: Long,
    quantDc: Int,
    invDcFactorQ16: Seq[Int],
    xQmMultiplierQ16: Int,
    epfIters: Int
) {
  require(invDcFactorQ16.length == 3, "expected X/Y/B inverse DC factors")
}

object DistanceParamsLookup {
  val Distance1Q8 = 256

  // Generated from libjxl-tiny python/jxl_tiny/quantization.py and
  // adaptive_quantization.py for common development distances. Unsupported
  // runtime distances currently fall back to distance 1.
  // invQacQ16 is floor(2^32 / (scaleQ16 * rawQuant5)) for the current fixed
  // all-DCT raw quant value. The three aq* fields are the Q24 per-block scale,
  // high-distance damping, and inverse global AC scale used by the final
  // adaptive-quantization map and raw-quant conversion.
  val Entries: Seq[DistanceParamsEntry] = Seq(
    DistanceParamsEntry(
      64,
      29360,
      29257,
      55660092,
      16777216,
      37449308L,
      10,
      Seq(1202585600, 150323200, 75161600),
      81920,
      0
    ),
    DistanceParamsEntry(
      128,
      14680,
      58514,
      27830046,
      16777216,
      74898616L,
      10,
      Seq(601292800, 75161600, 37580800),
      65536,
      0
    ),
    DistanceParamsEntry(
      256,
      7340,
      117029,
      13915023,
      16777216,
      149797232L,
      10,
      Seq(300646400, 37580800, 18790400),
      65536,
      1
    ),
    DistanceParamsEntry(
      512,
      3670,
      234058,
      6957512,
      16777216,
      299594464L,
      10,
      Seq(150323200, 18790400, 9395200),
      81920,
      2
    ),
    DistanceParamsEntry(
      1024,
      2107,
      407685,
      3478756,
      16777216,
      521837504L,
      10,
      Seq(86302720, 10787840, 5393920),
      81920,
      3
    ),
    DistanceParamsEntry(
      2048,
      1310,
      655720,
      1739378,
      14380471,
      839321856L,
      11,
      Seq(59023360, 7377920, 3688960),
      81920,
      3
    )
  )

  val Default: DistanceParamsEntry =
    Entries.find(_.distanceQ8 == Distance1Q8).get
}

class DistanceParamsOutput extends Bundle {
  val scaleQ16 = UInt(16.W)
  val invQacQ16 = UInt(32.W)
  val aqScaleQ24 = UInt(32.W)
  val aqDampenQ24 = UInt(25.W)
  val aqInvGlobalScaleQ24 = UInt(32.W)
  val quantDc = UInt(16.W)
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val epfIters = UInt(2.W)
}

class DistanceParamsLookup extends Module {
  val io = IO(new Bundle {
    val distanceQ8 = Input(UInt(16.W))
    val params = Output(new DistanceParamsOutput)
    val supported = Output(Bool())
  })

  private def entryToBundle(entry: DistanceParamsEntry): DistanceParamsOutput = {
    val wire = Wire(new DistanceParamsOutput)
    wire.scaleQ16 := entry.scaleQ16.U
    wire.invQacQ16 := entry.invQacQ16.U
    wire.aqScaleQ24 := entry.aqScaleQ24.U
    wire.aqDampenQ24 := entry.aqDampenQ24.U
    wire.aqInvGlobalScaleQ24 := entry.aqInvGlobalScaleQ24.U
    wire.quantDc := entry.quantDc.U
    for (channel <- 0 until 3) {
      wire.invDcFactorQ16(channel) := entry.invDcFactorQ16(channel).U
    }
    wire.xQmMultiplierQ16 := entry.xQmMultiplierQ16.U
    wire.epfIters := entry.epfIters.U
    wire
  }

  val defaultParams = entryToBundle(DistanceParamsLookup.Default)
  io.params := MuxLookup(io.distanceQ8, defaultParams)(
    DistanceParamsLookup.Entries.map { entry =>
      entry.distanceQ8.U -> entryToBundle(entry)
    }
  )
  io.supported := DistanceParamsLookup.Entries.map(entry => io.distanceQ8 === entry.distanceQ8.U).reduce(_ || _)
}
