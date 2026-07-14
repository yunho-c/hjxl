// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object DcTokenize {
  val GradRangeMin = 0
  val GradRangeMid = 512
  val GradRangeMax = 1023

  def packSigned(value: SInt, width: Int): SInt = {
    val magnitude = Mux(value < 0.S, -value, value)
    val packed = Mux(value < 0.S, (magnitude << 1) - 1.S, magnitude << 1)
    packed.asSInt.pad(width)
  }

  def clampedGradient(north: SInt, west: SInt, northwest: SInt): SInt = {
    val minValue = Mux(north < west, north, west)
    val maxValue = Mux(north > west, north, west)
    val gradient = north + west - northwest
    Mux(northwest < minValue, maxValue, Mux(northwest > maxValue, minValue, gradient)).asSInt
  }

  def gradientContext(gradProp: UInt): UInt =
    MuxCase(
      11.U,
      Seq(
        (gradProp <= 12.U) -> 44.U,
        (gradProp <= 120.U) -> 43.U,
        (gradProp <= 257.U) -> 40.U,
        (gradProp <= 321.U) -> 39.U,
        (gradProp <= 385.U) -> 38.U,
        (gradProp <= 417.U) -> 37.U,
        (gradProp <= 449.U) -> 36.U,
        (gradProp <= 465.U) -> 35.U,
        (gradProp <= 481.U) -> 34.U,
        (gradProp <= 489.U) -> 33.U,
        (gradProp <= 497.U) -> 32.U,
        (gradProp <= 501.U) -> 31.U,
        (gradProp <= 505.U) -> 30.U,
        (gradProp <= 508.U) -> 29.U,
        (gradProp <= 509.U) -> 28.U,
        (gradProp <= 511.U) -> 27.U,
        (gradProp <= 512.U) -> 26.U,
        (gradProp <= 513.U) -> 42.U,
        (gradProp <= 515.U) -> 41.U,
        (gradProp <= 517.U) -> 25.U,
        (gradProp <= 519.U) -> 24.U,
        (gradProp <= 523.U) -> 23.U,
        (gradProp <= 527.U) -> 22.U,
        (gradProp <= 535.U) -> 21.U,
        (gradProp <= 543.U) -> 20.U,
        (gradProp <= 559.U) -> 19.U,
        (gradProp <= 575.U) -> 18.U,
        (gradProp <= 607.U) -> 17.U,
        (gradProp <= 639.U) -> 16.U,
        (gradProp <= 703.U) -> 15.U,
        (gradProp <= 767.U) -> 14.U,
        (gradProp <= 904.U) -> 13.U,
        (gradProp <= 1012.U) -> 12.U
      )
    )
}

object Tokenize {
  val DctStrategyCode = 0
  val Dct16x8StrategyCode = 6
  val Dct8x16StrategyCode = 7
  val DefaultBlockMetadata = 4
  val NumBlockContexts = 4
  val NonzeroBuckets = 37
  val ZeroDensityContextCount = 458
  val DctBlockContextByChannel: Seq[Int] = Seq(2, 0, 2)
  val RectangularBlockContextByChannel: Seq[Int] = Seq(3, 1, 3)
  val DctCoeffOrder: Seq[Int] = Seq(
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
  )
  val RectangularCoeffOrder: Seq[Int] = Seq(
    0, 1, 16, 2, 3, 17, 32, 18,
    4, 5, 19, 33, 48, 34, 20, 6,
    7, 21, 35, 49, 64, 50, 36, 22,
    8, 9, 23, 37, 51, 65, 80, 66,
    52, 38, 24, 10, 11, 25, 39, 53,
    67, 81, 96, 82, 68, 54, 40, 26,
    12, 13, 27, 41, 55, 69, 83, 97,
    112, 98, 84, 70, 56, 42, 28, 14,
    15, 29, 43, 57, 71, 85, 99, 113,
    114, 100, 86, 72, 58, 44, 30, 31,
    45, 59, 73, 87, 101, 115, 116, 102,
    88, 74, 60, 46, 47, 61, 75, 89,
    103, 117, 118, 104, 90, 76, 62, 63,
    77, 91, 105, 119, 120, 106, 92, 78,
    79, 93, 107, 121, 122, 108, 94, 95,
    109, 123, 124, 110, 111, 125, 126, 127
  )
  val CoeffFreqContext: Seq[Int] = Seq(
    2989, 0, 1, 2, 3, 4, 5, 6,
    7, 8, 9, 10, 11, 12, 13, 14,
    15, 15, 16, 16, 17, 17, 18, 18,
    19, 19, 20, 20, 21, 21, 22, 22,
    23, 23, 23, 23, 24, 24, 24, 24,
    25, 25, 25, 25, 26, 26, 26, 26,
    27, 27, 27, 27, 28, 28, 28, 28,
    29, 29, 29, 29, 30, 30, 30, 30
  )
  val CoeffNumNonzeroContext: Seq[Int] = Seq(
    2989, 0, 31, 62, 62, 93, 93, 93,
    93, 123, 123, 123, 123, 152, 152, 152,
    152, 152, 152, 152, 152, 180, 180, 180,
    180, 180, 180, 180, 180, 180, 180, 180,
    180, 206, 206, 206, 206, 206, 206, 206,
    206, 206, 206, 206, 206, 206, 206, 206,
    206, 206, 206, 206, 206, 206, 206, 206,
    206, 206, 206, 206, 206, 206, 206, 206
  )

  def packSigned(value: SInt, width: Int): SInt =
    DcTokenize.packSigned(value, width)

  def strategyContext(left: UInt): UInt =
    Mux(left > 11.U, 7.U, Mux(left > 5.U, 8.U, Mux(left > 3.U, 9.U, 10.U)))

  def quantFieldContext(left: UInt): UInt =
    Mux(left > 11.U, 3.U, Mux(left > 5.U, 4.U, Mux(left > 3.U, 5.U, 6.U)))

  def nonzeroContext(nonzeros: UInt, blockContext: UInt): UInt = {
    val bucket = Mux(nonzeros < 8.U, nonzeros, Mux(nonzeros >= 64.U, 36.U, 4.U + nonzeros / 2.U))
    bucket * NumBlockContexts.U + blockContext
  }

  def zeroDensityContextsOffset(blockContext: UInt): UInt =
    (NumBlockContexts * NonzeroBuckets).U + ZeroDensityContextCount.U * blockContext

  def strategyCode(rawStrategy: UInt): UInt =
    MuxLookup(rawStrategy, DctStrategyCode.U)(
      Seq(
        AcStrategyCode.Dct.U -> DctStrategyCode.U,
        AcStrategyCode.Dct16x8.U -> Dct16x8StrategyCode.U,
        AcStrategyCode.Dct8x16.U -> Dct8x16StrategyCode.U
      )
    )

  def blockContext(channel: UInt, rawStrategy: UInt): UInt = {
    val dct = VecInit(DctBlockContextByChannel.map(_.U(2.W)))(channel)
    val rectangular = VecInit(RectangularBlockContextByChannel.map(_.U(2.W)))(channel)
    Mux(rawStrategy === AcStrategyCode.Dct.U, dct, rectangular)
  }

  def zeroDensityContext(nonzerosLeft: UInt, coefficientIndex: UInt, prev: Bool): UInt = {
    zeroDensityContext(nonzerosLeft, coefficientIndex, 1.U, 0.U, prev)
  }

  def zeroDensityContext(
      nonzerosLeft: UInt,
      coefficientIndex: UInt,
      coveredBlocks: UInt,
      log2CoveredBlocks: UInt,
      prev: Bool
  ): UInt = {
    val nonzeroTable = VecInit(CoeffNumNonzeroContext.map(_.U(12.W)))
    val freqTable = VecInit(CoeffFreqContext.map(_.U(12.W)))
    val scaledNonzeros = (nonzerosLeft +& coveredBlocks - 1.U) >> log2CoveredBlocks
    val scaledCoefficient = coefficientIndex >> log2CoveredBlocks
    val nonzerosIndex = scaledNonzeros(log2Ceil(CoeffNumNonzeroContext.length) - 1, 0)
    val coefficientIndexSafe = scaledCoefficient(log2Ceil(CoeffFreqContext.length) - 1, 0)
    ((nonzeroTable(nonzerosIndex) + freqTable(coefficientIndexSafe)) << 1) + prev.asUInt
  }
}
