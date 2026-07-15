// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Fixed-point scaled DCT for the rectangular transform shapes used by
  * libjxl-tiny AC-strategy scoring and quantization.
  *
  * The input is raster ordered at `pixelRows` by `pixelColumns`. Both supported
  * shapes emit 128 coefficients in the reference's canonical 8x16 layout:
  * a 16x8 pixel transform is transposed to `(xFrequency, yFrequency)`, while an
  * 8x16 pixel transform remains `(yFrequency, xFrequency)`.
  */
class DctRectangularApprox(
    pixelRows: Int,
    pixelColumns: Int,
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  require(
    (pixelRows == 16 && pixelColumns == 8) ||
      (pixelRows == 8 && pixelColumns == 16),
    "rectangular DCT shape must be 16x8 or 8x16"
  )

  private val sampleCount = pixelRows * pixelColumns

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(sampleCount, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(sampleCount, SInt(c.traceValueBits.W)))
  })

  private def transform(values: Seq[SInt]): Seq[SInt] =
    values.length match {
      case 8  => Dct8Approx.dct8(values, c.traceValueBits, coefficientFractionBits)
      case 16 => Dct16Approx.dct16(values, c.traceValueBits, coefficientFractionBits)
      case length => throw new IllegalArgumentException(s"unsupported DCT dimension: $length")
    }

  private def scale(value: SInt, dimension: Int): SInt =
    Dct8Approx.fit(value >> log2Ceil(dimension), c.traceValueBits)

  private def inputAt(y: Int, x: Int): SInt = io.input.bits(y * pixelColumns + x)

  val columnPass = Seq.tabulate(pixelColumns) { x =>
    transform((0 until pixelRows).map(y => inputAt(y, x))).map(scale(_, pixelRows))
  }
  val rowPass = Seq.tabulate(pixelRows) { yFrequency =>
    transform((0 until pixelColumns).map(x => columnPass(x)(yFrequency)))
      .map(scale(_, pixelColumns))
  }

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  if (pixelRows == 16) {
    for (xFrequency <- 0 until pixelColumns; yFrequency <- 0 until pixelRows) {
      io.output.bits(xFrequency * pixelRows + yFrequency) := rowPass(yFrequency)(xFrequency)
    }
  } else {
    for (yFrequency <- 0 until pixelRows; xFrequency <- 0 until pixelColumns) {
      io.output.bits(yFrequency * pixelColumns + xFrequency) := rowPass(yFrequency)(xFrequency)
    }
  }
}

class Dct16x8Approx(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends DctRectangularApprox(16, 8, c, coefficientFractionBits)

class Dct8x16Approx(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends DctRectangularApprox(8, 16, c, coefficientFractionBits)
