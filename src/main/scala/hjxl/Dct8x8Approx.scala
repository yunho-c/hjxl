// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Fixed-point 8x8 scaled DCT block primitive.
  *
  * The layout follows libjxl-tiny's `scaled_dct_8x8`: transform columns with a
  * 1/8 scale, transform rows of that intermediate with another 1/8 scale, and
  * emit the transposed canonical coefficient layout used by quantization.
  */
class Dct8x8Approx(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(Vec(blockSize, SInt(c.traceValueBits.W))))
    val output = Decoupled(Vec(blockSize, SInt(c.traceValueBits.W)))
  })

  private def scaleByBlock(value: SInt): SInt =
    Dct8Approx.fit(value >> log2Ceil(blockDim), c.traceValueBits)

  private def at(values: Seq[SInt], y: Int, x: Int): SInt = values(y * blockDim + x)

  val inputValues = (0 until blockSize).map(i => io.input.bits(i))

  val columnDct = Seq.tabulate(blockDim) { x =>
    Dct8Approx.dct8((0 until blockDim).map(y => at(inputValues, y, x)), c.traceValueBits)
      .map(scaleByBlock)
  }
  val columnMajorIntermediate = Seq.tabulate(blockSize) { i =>
    val y = i / blockDim
    val x = i % blockDim
    columnDct(x)(y)
  }

  val rowDct = Seq.tabulate(blockDim) { y =>
    Dct8Approx.dct8((0 until blockDim).map(x => at(columnMajorIntermediate, y, x)), c.traceValueBits)
      .map(scaleByBlock)
  }

  io.input.ready := io.output.ready
  io.output.valid := io.input.valid
  for (coefficientX <- 0 until blockDim) {
    for (sourceY <- 0 until blockDim) {
      io.output.bits(coefficientX * blockDim + sourceY) := rowDct(sourceY)(coefficientX)
    }
  }
}
