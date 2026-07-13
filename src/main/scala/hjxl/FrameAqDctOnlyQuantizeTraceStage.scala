// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object AdaptiveInvQacFixedPoint {
  val Dividend: BigInt = BigInt(1) << 32
  val MaximumQ16: BigInt = Dividend - 1

  /** Nearest unsigned-Q16 representation of `1 / (scale * quant)`.
    *
    * `scaleQ16` represents the floating AC scale in Q16, so the fixed result is
    * `round(2^32 / (scaleQ16 * rawQuant))`. A zero divisor saturates instead of
    * producing an undefined hardware result.
    */
  def reciprocalQ16(scaleQ16: Int, rawQuant: Int): BigInt = {
    require(scaleQ16 >= 0 && scaleQ16 < (1 << 16), "AC scale must fit uint16")
    require(rawQuant >= 0 && rawQuant < (1 << 8), "raw quant must fit uint8")
    val denominator = BigInt(scaleQ16) * BigInt(rawQuant)
    if (denominator == 0) MaximumQ16
    else ((Dividend + denominator / 2) / denominator).min(MaximumQ16)
  }
}

class AdaptiveInvQacInput extends Bundle {
  val scaleQ16 = UInt(16.W)
  val rawQuant = UInt(8.W)
}

/** A 33-cycle restoring divider for the per-block inverse AC quant scale. */
class AdaptiveInvQacQ16 extends Module {
  import AdaptiveInvQacFixedPoint._

  private val dividendBits = 33
  private val denominatorBits = 24
  private val iterationBits = log2Ceil(dividendBits + 1)

  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new AdaptiveInvQacInput))
    val output = Decoupled(UInt(32.W))
    val busy = Output(Bool())
  })

  val running = RegInit(false.B)
  val iterationsRemaining = RegInit(0.U(iterationBits.W))
  val dividend = RegInit(0.U(dividendBits.W))
  val divisor = RegInit(0.U(denominatorBits.W))
  val remainder = RegInit(0.U(dividendBits.W))
  val quotient = RegInit(0.U(dividendBits.W))
  val outputValid = RegInit(false.B)
  val outputValue = RegInit(0.U(32.W))

  io.input.ready := !running && !outputValid
  io.output.valid := outputValid
  io.output.bits := outputValue
  io.busy := running || outputValid

  when(io.output.fire) {
    outputValid := false.B
  }

  when(io.input.fire) {
    val inputDivisor = io.input.bits.scaleQ16 * io.input.bits.rawQuant
    when(inputDivisor === 0.U) {
      outputValue := MaximumQ16.U
      outputValid := true.B
    }.otherwise {
      running := true.B
      iterationsRemaining := dividendBits.U
      dividend := Dividend.U(dividendBits.W)
      divisor := inputDivisor
      remainder := 0.U
      quotient := 0.U
    }
  }

  when(running) {
    val shiftedRemainder = Cat(remainder(denominatorBits - 1, 0), dividend(dividendBits - 1))
    val extendedDivisor = divisor.pad(dividendBits)
    val subtract = shiftedRemainder >= extendedDivisor
    val nextRemainder = Mux(subtract, shiftedRemainder - extendedDivisor, shiftedRemainder)
    val nextQuotient = Cat(quotient(dividendBits - 2, 0), subtract)

    dividend := Cat(dividend(dividendBits - 2, 0), 0.U(1.W))
    remainder := nextRemainder
    quotient := nextQuotient
    iterationsRemaining := iterationsRemaining - 1.U

    when(iterationsRemaining === 1.U) {
      val doubledRemainder = nextRemainder << 1
      val roundUp = doubledRemainder >= extendedDivisor.pad(dividendBits + 1)
      val roundedQuotient = nextQuotient +& roundUp
      outputValue := Mux(
        roundedQuotient > MaximumQ16.U,
        MaximumQ16.U,
        roundedQuotient(31, 0)
      )
      outputValid := true.B
      running := false.B
      iterationsRemaining := 0.U
    }
  }
}

class AqDctOnlyBlockOutput(c: HjxlConfig) extends Bundle {
  val quantize = new DctOnlyQuantizeBlockInput(c)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
}

/** Converts the completed RGB AQ stream into prepared all-DCT block inputs.
  *
  * The source reuses the X/Y/B block retained by `FrameAqFinalMapPipeline`, so
  * RGB-to-XYB conversion and frame storage occur only once. Adaptive blocks use
  * a dynamically computed reciprocal matching their raw-quant byte. A nonzero
  * `fixedRawQuant` preserves the explicit fixed byte and caller-supplied
  * `fixedInvQacQ16` experiment contract.
  */
class FrameAqDctOnlyBlockStage(c: HjxlConfig = HjxlConfig()) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new AqDctOnlyBlockOutput(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val aq = Module(new FrameAqFinalMapPipeline(c))
  aq.io.config := io.config
  aq.io.input <> io.input

  val rawQuant = Module(new AqMapToRawQuant)
  rawQuant.io.aqMapQ := aq.io.output.bits.aqMapQ24
  rawQuant.io.invGlobalScaleQ := aq.io.output.bits.invGlobalScaleQ24
  val adaptiveRawQuant = aq.io.output.bits.fixedRawQuant === 0.U
  val selectedRawQuant = Mux(
    adaptiveRawQuant,
    rawQuant.io.rawQuant,
    aq.io.output.bits.fixedRawQuant
  )

  val reciprocal = Module(new AdaptiveInvQacQ16)
  val reciprocalStarted = RegInit(false.B)
  reciprocal.io.input.bits.scaleQ16 := aq.io.output.bits.scaleQ16
  reciprocal.io.input.bits.rawQuant := selectedRawQuant
  reciprocal.io.input.valid := aq.io.output.valid && adaptiveRawQuant && !reciprocalStarted
  when(reciprocal.io.input.fire) {
    reciprocalStarted := true.B
  }

  val dctX = Module(new Dct8x8Approx(c))
  val dctY = Module(new Dct8x8Approx(c))
  val dctB = Module(new Dct8x8Approx(c))
  val dcts = Seq(dctX, dctY, dctB)
  for (dct <- dcts) {
    dct.io.input.valid := aq.io.output.valid
  }
  for (coefficient <- 0 until blockSize) {
    dctX.io.input.bits(coefficient) := aq.io.output.bits.xybXQ12(coefficient)
    dctY.io.input.bits(coefficient) := aq.io.output.bits.xybYQ12(coefficient)
    dctB.io.input.bits(coefficient) := aq.io.output.bits.xybBQ12(coefficient)
  }

  val reciprocalAvailable = !adaptiveRawQuant || (reciprocalStarted && reciprocal.io.output.valid)
  val allDctValid = dcts.map(_.io.output.valid).reduce(_ && _)
  io.output.valid := aq.io.output.valid && allDctValid && reciprocalAvailable
  io.output.bits.quantize.quant := selectedRawQuant
  io.output.bits.quantize.scaleQ16 := aq.io.output.bits.scaleQ16
  io.output.bits.quantize.invQacQ16 := Mux(
    adaptiveRawQuant,
    reciprocal.io.output.bits,
    aq.io.output.bits.fixedInvQacQ16
  )
  io.output.bits.quantize.invDcFactorQ16 := aq.io.output.bits.invDcFactorQ16
  io.output.bits.quantize.xQmMultiplierQ16 := aq.io.output.bits.xQmMultiplierQ16
  io.output.bits.quantize.ytox := aq.io.output.bits.fixedYtox
  io.output.bits.quantize.ytob := aq.io.output.bits.fixedYtob
  io.output.bits.blockIndex := aq.io.output.bits.blockIndex
  io.output.bits.blockLast := aq.io.output.bits.blockLast
  for (coefficient <- 0 until blockSize) {
    io.output.bits.quantize.coefficients(0)(coefficient) := dctX.io.output.bits(coefficient)
    io.output.bits.quantize.coefficients(1)(coefficient) := dctY.io.output.bits(coefficient)
    io.output.bits.quantize.coefficients(2)(coefficient) := dctB.io.output.bits(coefficient)
  }

  val consumeBlock = io.output.ready && allDctValid && reciprocalAvailable
  aq.io.output.ready := consumeBlock
  for (dct <- dcts) {
    dct.io.output.ready := io.output.ready && aq.io.output.valid && reciprocalAvailable
  }
  reciprocal.io.output.ready :=
    io.output.ready && aq.io.output.valid && allDctValid && adaptiveRawQuant && reciprocalStarted

  when(io.output.fire) {
    reciprocalStarted := false.B
  }
  assert(
    !reciprocalStarted || (aq.io.output.valid && adaptiveRawQuant),
    "adaptive inverse-QAC context outlived its AQ block"
  )

  io.busy := aq.io.busy || reciprocal.io.busy || reciprocalStarted
  io.overflow := aq.io.overflow
}

/** RGB-connected adaptive all-DCT quantized AC/DC/nonzero traces. */
class FrameAqDctOnlyQuantizeTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctOnlyBlockStage(c))
  val quantized = Module(
    new FramePreparedDctOnlyQuantizeTraceStage(c, Some(Dct8Approx.FractionBits))
  )
  val draining = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  quantized.io.config := io.config
  quantized.io.input.valid := blocks.io.output.valid
  quantized.io.input.bits := blocks.io.output.bits.quantize
  blocks.io.output.ready := quantized.io.input.ready

  io.trace.valid := quantized.io.trace.valid
  io.trace.bits := quantized.io.trace.bits
  quantized.io.trace.ready := io.trace.ready
  io.traceLast := quantized.io.traceLast
  io.busy := draining || blocks.io.busy || quantized.io.busy
  io.overflow := blocks.io.overflow || quantized.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}

/** RGB-connected AC metadata using the same adaptive per-block quant field. */
class FrameAqDctOnlyAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val aq = Module(new FrameAqFinalMapPipeline(c))
  val rawQuant = Module(new AqMapToRawQuant)
  val metadata = Module(new FramePreparedAcMetadataTokenTraceStage(c))
  val draining = RegInit(false.B)

  aq.io.config := io.config
  aq.io.input.bits := io.input.bits
  aq.io.input.valid := io.input.valid && !draining
  io.input.ready := aq.io.input.ready && !draining

  rawQuant.io.aqMapQ := aq.io.output.bits.aqMapQ24
  rawQuant.io.invGlobalScaleQ := aq.io.output.bits.invGlobalScaleQ24
  val selectedRawQuant = Mux(
    aq.io.output.bits.fixedRawQuant === 0.U,
    rawQuant.io.rawQuant,
    aq.io.output.bits.fixedRawQuant
  )

  metadata.io.config := io.config
  metadata.io.input.valid := aq.io.output.valid
  metadata.io.input.bits.rawQuant := selectedRawQuant
  metadata.io.input.bits.ytox := aq.io.output.bits.fixedYtox
  metadata.io.input.bits.ytob := aq.io.output.bits.fixedYtob
  aq.io.output.ready := metadata.io.input.ready

  io.trace.valid := metadata.io.trace.valid
  io.trace.bits := metadata.io.trace.bits
  metadata.io.trace.ready := io.trace.ready
  io.traceLast := metadata.io.traceLast
  io.busy := draining || aq.io.busy || metadata.io.busy
  io.overflow := aq.io.overflow || metadata.io.overflow

  when(aq.io.output.fire && aq.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}

/** RGB-to-logical-token path with adaptive raw quant and fixed scalar CFL.
  *
  * It reuses the exact prepared quantize-to-token scheduler after producing the
  * same structured all-DCT block records in RTL. The trace stream contains DC,
  * strategy, AC-metadata, and AC token stages in host-assembly order.
  */
class FrameAqDctOnlyQuantizeTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctOnlyBlockStage(c))
  val tokens = Module(
    new FramePreparedDctOnlyQuantizeTokenTraceStage(c, Some(Dct8Approx.FractionBits))
  )
  val draining = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  tokens.io.config := io.config
  tokens.io.input.valid := blocks.io.output.valid
  tokens.io.input.bits := blocks.io.output.bits.quantize
  blocks.io.output.ready := tokens.io.input.ready

  io.trace.valid := tokens.io.trace.valid
  io.trace.bits := tokens.io.trace.bits
  tokens.io.trace.ready := io.trace.ready
  io.traceLast := tokens.io.traceLast
  io.busy := draining || blocks.io.busy || tokens.io.busy
  io.overflow := blocks.io.overflow || tokens.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}
