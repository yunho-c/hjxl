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

class AqDctBlockOutput(
    c: HjxlConfig,
    includeQuantizationPrecision: Boolean = false
) extends Bundle {
  val coefficients = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
  val xyb = Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W)))
  val quantizationCoefficientsQ16 =
    if (includeQuantizationPrecision)
      Some(Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))))
    else None
  val quantizationXybQ16 =
    if (includeQuantizationPrecision)
      Some(Vec(3, Vec(HjxlConstants.BlockDim * HjxlConstants.BlockDim, SInt(c.traceValueBits.W))))
    else None
  val aqMapQ24 = UInt(AqFinalModulationFixedPoint.ValueBits.W)
  val strategyMaskQ16 = UInt(AqStrategyMaskFixedPoint.ValueBits.W)
  val distanceQ8 = UInt(16.W)
  val quant = UInt(8.W)
  val scaleQ16 = UInt(16.W)
  val fixedInvQacQ16 = UInt(32.W)
  val adaptiveRawQuant = Bool()
  val invDcFactorQ16 = Vec(3, UInt(32.W))
  val xQmMultiplierQ16 = UInt(32.W)
  val fixedYtox = SInt(8.W)
  val fixedYtob = SInt(8.W)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
}

class AqDctOnlyBlockOutput(c: HjxlConfig) extends Bundle {
  val quantize = new DctOnlyQuantizeBlockInput(c)
  val blockIndex = UInt(32.W)
  val blockLast = Bool()
}

private object AqDctBlockWiring {
  def connectPrepared(
      target: DctOnlyQuantizeBlockInput,
      source: AqDctBlockOutput,
      invQacQ16: UInt,
      ytox: SInt,
      ytob: SInt
  ): Unit = {
    target.coefficients := source.coefficients
    target.quant := source.quant
    target.scaleQ16 := source.scaleQ16
    target.invQacQ16 := invQacQ16
    target.invDcFactorQ16 := source.invDcFactorQ16
    target.xQmMultiplierQ16 := source.xQmMultiplierQ16
    target.ytox := ytox
    target.ytob := ytob
  }
}

/** Converts the completed RGB AQ stream into native-Q12 all-DCT blocks.
  *
  * The default source reuses the X/Y/B block retained by
  * `FrameAqFinalMapPipeline`, so it adds no downstream RGB conversion or frame
  * store. The focused VarDCT build asks that pipeline to retain one additional
  * Q16 frame from the same conversion for selected-owner CFL fitting and
  * quantization. Reciprocal AC-scale generation remains downstream so CFL-map
  * and metadata-only routes do not elaborate arithmetic they never consume.
  */
class FrameAqDctBlockStage(
    c: HjxlConfig = HjxlConfig(),
    includeQuantizationPrecision: Boolean = false
) extends Module {
  private val blockSize = HjxlConstants.BlockDim * HjxlConstants.BlockDim
  private val quantizationFractionBits = 16

  require(
    quantizationFractionBits > RgbToXybApprox.OutputFractionBits,
    "quantization sideband must retain more precision than Q12"
  )

  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new AqDctBlockOutput(c, includeQuantizationPrecision))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val aq = Module(new FrameAqFinalMapPipeline(c, includeQuantizationPrecision))
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

  val precisionDcts = if (includeQuantizationPrecision) {
    Some(Seq.fill(3)(Module(new Dct8x8Approx(c))))
  } else {
    None
  }
  precisionDcts.foreach { dctsQ16 =>
    for (dct <- dctsQ16) {
      dct.io.input.valid := aq.io.output.valid
    }
    for (sample <- 0 until blockSize) {
      for (channel <- 0 until 3) {
        dctsQ16(channel).io.input.bits(sample) :=
          aq.io.output.bits.quantizationXybQ16.get(channel)(sample)
      }
    }
  }

  val allDctValid =
    dcts.map(_.io.output.valid).reduce(_ && _) &&
      precisionDcts.map(_.map(_.io.output.valid).reduce(_ && _)).getOrElse(true.B)
  io.output.valid := aq.io.output.valid && allDctValid
  io.output.bits.aqMapQ24 := aq.io.output.bits.aqMapQ24
  io.output.bits.strategyMaskQ16 := aq.io.output.bits.strategyMaskQ16
  io.output.bits.distanceQ8 := aq.io.output.bits.distanceQ8
  io.output.bits.quant := selectedRawQuant
  io.output.bits.scaleQ16 := aq.io.output.bits.scaleQ16
  io.output.bits.fixedInvQacQ16 := aq.io.output.bits.fixedInvQacQ16
  io.output.bits.adaptiveRawQuant := adaptiveRawQuant
  io.output.bits.invDcFactorQ16 := aq.io.output.bits.invDcFactorQ16
  io.output.bits.xQmMultiplierQ16 := aq.io.output.bits.xQmMultiplierQ16
  io.output.bits.fixedYtox := aq.io.output.bits.fixedYtox
  io.output.bits.fixedYtob := aq.io.output.bits.fixedYtob
  io.output.bits.blockIndex := aq.io.output.bits.blockIndex
  io.output.bits.blockLast := aq.io.output.bits.blockLast
  for (coefficient <- 0 until blockSize) {
    io.output.bits.coefficients(0)(coefficient) := dctX.io.output.bits(coefficient)
    io.output.bits.coefficients(1)(coefficient) := dctY.io.output.bits(coefficient)
    io.output.bits.coefficients(2)(coefficient) := dctB.io.output.bits(coefficient)
    io.output.bits.xyb(0)(coefficient) := aq.io.output.bits.xybXQ12(coefficient)
    io.output.bits.xyb(1)(coefficient) := aq.io.output.bits.xybYQ12(coefficient)
    io.output.bits.xyb(2)(coefficient) := aq.io.output.bits.xybBQ12(coefficient)
    for (channel <- 0 until 3) {
      io.output.bits.quantizationCoefficientsQ16.foreach(
        _(channel)(coefficient) := precisionDcts.get(channel).io.output.bits(coefficient)
      )
      io.output.bits.quantizationXybQ16.foreach(
        _(channel)(coefficient) := aq.io.output.bits.quantizationXybQ16.get(channel)(coefficient)
      )
    }
  }

  val consumeBlock = io.output.ready && allDctValid
  aq.io.output.ready := consumeBlock
  for (dct <- dcts) {
    dct.io.output.ready := io.output.ready && aq.io.output.valid
  }
  precisionDcts.foreach(_.foreach { dct =>
    dct.io.output.ready := io.output.ready && aq.io.output.valid
  })

  io.busy := aq.io.busy
  io.overflow := aq.io.overflow
}

/** Adds the reciprocal required by the prepared all-DCT quantizer contract.
  *
  * Adaptive blocks compute a reciprocal matching their raw-quant byte. A
  * nonzero `fixedRawQuant` preserves the explicit fixed byte and the caller's
  * `fixedInvQacQ16` experiment contract.
  */
class FrameAqDctOnlyBlockStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val output = Decoupled(new AqDctOnlyBlockOutput(c))
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctBlockStage(c))
  val reciprocal = Module(new AdaptiveInvQacQ16)
  val reciprocalStarted = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input <> io.input

  reciprocal.io.input.bits.scaleQ16 := blocks.io.output.bits.scaleQ16
  reciprocal.io.input.bits.rawQuant := blocks.io.output.bits.quant
  reciprocal.io.input.valid :=
    blocks.io.output.valid && blocks.io.output.bits.adaptiveRawQuant && !reciprocalStarted
  when(reciprocal.io.input.fire) {
    reciprocalStarted := true.B
  }

  val reciprocalAvailable =
    !blocks.io.output.bits.adaptiveRawQuant || (reciprocalStarted && reciprocal.io.output.valid)
  val selectedInvQacQ16 = Mux(
    blocks.io.output.bits.adaptiveRawQuant,
    reciprocal.io.output.bits,
    blocks.io.output.bits.fixedInvQacQ16
  )
  io.output.valid := blocks.io.output.valid && reciprocalAvailable
  AqDctBlockWiring.connectPrepared(
    io.output.bits.quantize,
    blocks.io.output.bits,
    selectedInvQacQ16,
    blocks.io.output.bits.fixedYtox,
    blocks.io.output.bits.fixedYtob
  )
  io.output.bits.blockIndex := blocks.io.output.bits.blockIndex
  io.output.bits.blockLast := blocks.io.output.bits.blockLast

  blocks.io.output.ready := io.output.ready && reciprocalAvailable
  reciprocal.io.output.ready :=
    io.output.ready && blocks.io.output.valid && blocks.io.output.bits.adaptiveRawQuant && reciprocalStarted

  when(io.output.fire) {
    reciprocalStarted := false.B
  }
  assert(
    !reciprocalStarted || (blocks.io.output.valid && blocks.io.output.bits.adaptiveRawQuant),
    "adaptive inverse-QAC context outlived its AQ block"
  )

  io.busy := blocks.io.busy || reciprocal.io.busy || reciprocalStarted
  io.overflow := blocks.io.overflow
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

/** RGB-derived CFL map traces from the native-Q12 adaptive DCT block stream. */
class FrameAqCflMapTraceStage(
    c: HjxlConfig = HjxlConfig(),
    traceStageFilter: Option[Int] = None
) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctBlockStage(c))
  val maps = Module(
    new FramePreparedCflMapTraceStage(
      c,
      coefficientFractionBitsOverride = Some(Dct8Approx.FractionBits),
      traceStageFilter = traceStageFilter
    )
  )
  val draining = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  maps.io.config := io.config
  maps.io.input.valid := blocks.io.output.valid
  AqDctBlockWiring.connectPrepared(
    maps.io.input.bits,
    blocks.io.output.bits,
    0.U,
    0.S,
    0.S
  )
  blocks.io.output.ready := maps.io.input.ready

  io.trace.valid := maps.io.trace.valid
  io.trace.bits := maps.io.trace.bits
  maps.io.trace.ready := io.trace.ready
  io.traceLast := maps.io.traceLast
  io.busy := draining || blocks.io.busy || maps.io.busy
  io.overflow := blocks.io.overflow || maps.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}

/** RGB adaptive quantization with tile CFL estimated from the same Q12 DCTs. */
class FrameAqCflDctOnlyQuantizeTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
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
    new FramePreparedCflDctOnlyQuantizeTraceStage(c, Some(Dct8Approx.FractionBits))
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

/** RGB AC metadata with adaptive raw quant and internally estimated tile CFL. */
class FrameAqCflDctOnlyAcMetadataTokenTraceStage(c: HjxlConfig = HjxlConfig()) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
  })

  val blocks = Module(new FrameAqDctBlockStage(c))
  val metadata = Module(
    new FramePreparedCflAcMetadataTokenTraceStage(c, Some(Dct8Approx.FractionBits))
  )
  val draining = RegInit(false.B)

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  metadata.io.config := io.config
  metadata.io.input.valid := blocks.io.output.valid
  AqDctBlockWiring.connectPrepared(
    metadata.io.input.bits,
    blocks.io.output.bits,
    0.U,
    0.S,
    0.S
  )
  blocks.io.output.ready := metadata.io.input.ready

  io.trace.valid := metadata.io.trace.valid
  io.trace.bits := metadata.io.trace.bits
  metadata.io.trace.ready := io.trace.ready
  io.traceLast := metadata.io.traceLast
  io.busy := draining || blocks.io.busy || metadata.io.busy
  io.overflow := blocks.io.overflow || metadata.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}

/** Combined RGB logical tokens with adaptive quant and estimated tile CFL. */
class FrameAqCflDctOnlyQuantizeTokenTraceStage(
    c: HjxlConfig = HjxlConfig(),
    acTokensOnly: Boolean = false
) extends Module {
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
    new FramePreparedCflDctOnlyQuantizeTokenTraceStage(c, Some(Dct8Approx.FractionBits))
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

  val selectedTrace =
    if (acTokensOnly) tokens.io.trace.bits.stage === TraceStage.AcTokens.U else true.B
  io.trace.valid := tokens.io.trace.valid && selectedTrace
  io.trace.bits := tokens.io.trace.bits
  tokens.io.trace.ready := Mux(selectedTrace, io.trace.ready, true.B)
  io.traceLast := io.trace.valid && tokens.io.traceLast
  io.busy := draining || blocks.io.busy || tokens.io.busy
  io.overflow := blocks.io.overflow || tokens.io.overflow

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}
