// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

/** Converts one selected raster strategy cell into a first-block VarDCT owner.
  *
  * Continuation cells are consumed without producing an owner. First cells
  * reuse the stored ordinary DCT coefficients or recompute the selected
  * rectangular transform from the two covered XYB blocks. The live RGB route
  * supplies Q16 quantization sidebands while keeping Q12 strategy scoring;
  * prepared Q12 users retain the original compact interface. Adaptive raw
  * quantization uses a reciprocal matched to the post-strategy maximum.
  */
class AcStrategySelectedCellToVarDctOwnerStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
)
    extends Module {
  private val blockDim = HjxlConstants.BlockDim
  private val blockSize = blockDim * blockDim
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients

  val io = IO(new Bundle {
    val input = Flipped(
      Decoupled(new PreparedAcStrategySelectedCell(c, coefficientFractionBits))
    )
    val output = Decoupled(new PreparedVarDctFrameBlock(c))
    val busy = Output(Bool())
  })

  val encodedStrategy = io.input.bits.encodedStrategy
  val isFirst = encodedStrategy(0)
  val strategy = encodedStrategy(2, 1)
  val isDct = strategy === AcStrategyCode.Dct.U
  val isVertical = strategy === AcStrategyCode.Dct16x8.U
  val isHorizontal = strategy === AcStrategyCode.Dct8x16.U
  val supportedStrategy = isDct || isVertical || isHorizontal

  val verticalDct = Seq.fill(3)(
    Module(new Dct16x8Approx(c, coefficientFractionBits))
  )
  val horizontalDct = Seq.fill(3)(
    Module(new Dct8x16Approx(c, coefficientFractionBits))
  )
  for (channel <- 0 until 3) {
    verticalDct(channel).io.input.valid := io.input.valid && isFirst && isVertical
    horizontalDct(channel).io.input.valid := io.input.valid && isFirst && isHorizontal
    for (sample <- 0 until maxCoefficients) {
      val verticalBlock = if (sample / blockDim < blockDim) 0 else 1
      val verticalSample = (sample % blockSize)
      verticalDct(channel).io.input.bits(sample) :=
        io.input.bits.quantizationCoveredXyb
          .map(_(verticalBlock)(channel)(verticalSample))
          .getOrElse(io.input.bits.coveredXyb(verticalBlock)(channel)(verticalSample))

      val horizontalX = sample % (2 * blockDim)
      val horizontalY = sample / (2 * blockDim)
      val horizontalBlock = if (horizontalX < blockDim) 0 else 1
      val horizontalSample = horizontalY * blockDim + (horizontalX % blockDim)
      horizontalDct(channel).io.input.bits(sample) :=
        io.input.bits.quantizationCoveredXyb
          .map(_(horizontalBlock)(channel)(horizontalSample))
          .getOrElse(io.input.bits.coveredXyb(horizontalBlock)(channel)(horizontalSample))
    }
  }

  val reciprocal = Module(new AdaptiveInvQacQ16)
  val reciprocalStarted = RegInit(false.B)
  reciprocal.io.input.bits.scaleQ16 := io.input.bits.scaleQ16
  reciprocal.io.input.bits.rawQuant := io.input.bits.adjustedRawQuant
  reciprocal.io.input.valid :=
    io.input.valid && isFirst && supportedStrategy &&
      io.input.bits.adaptiveRawQuant && !reciprocalStarted
  when(reciprocal.io.input.fire) {
    reciprocalStarted := true.B
  }

  val rectangularValid = Mux(
    isVertical,
    verticalDct.map(_.io.output.valid).reduce(_ && _),
    horizontalDct.map(_.io.output.valid).reduce(_ && _)
  )
  val coefficientsValid = supportedStrategy && (isDct || rectangularValid)
  val reciprocalAvailable =
    !io.input.bits.adaptiveRawQuant || (reciprocalStarted && reciprocal.io.output.valid)

  io.output.valid :=
    io.input.valid && isFirst && supportedStrategy && coefficientsValid && reciprocalAvailable
  io.output.bits.blockX := io.input.bits.blockX
  io.output.bits.blockY := io.input.bits.blockY
  io.output.bits.last := io.input.bits.ownerLast
  io.output.bits.quantize.strategy := strategy
  io.output.bits.quantize.quant := io.input.bits.adjustedRawQuant
  io.output.bits.quantize.scaleQ16 := io.input.bits.scaleQ16
  io.output.bits.quantize.invQacQ16 := Mux(
    io.input.bits.adaptiveRawQuant,
    reciprocal.io.output.bits,
    io.input.bits.fixedInvQacQ16
  )
  io.output.bits.quantize.invDcFactorQ16 := io.input.bits.invDcFactorQ16
  io.output.bits.quantize.xQmMultiplierQ16 := io.input.bits.xQmMultiplierQ16
  io.output.bits.quantize.ytox := io.input.bits.ytox
  io.output.bits.quantize.ytob := io.input.bits.ytob
  for (channel <- 0 until 3; coefficient <- 0 until maxCoefficients) {
    val dctValue =
      if (coefficient < blockSize)
        io.input.bits.quantizationDct8x8
          .map(_(channel)(coefficient))
          .getOrElse(io.input.bits.dct8x8(channel)(coefficient))
      else 0.S(c.traceValueBits.W)
    io.output.bits.quantize.coefficients(channel)(coefficient) := Mux(
      isDct,
      dctValue,
      Mux(
        isVertical,
        verticalDct(channel).io.output.bits(coefficient),
        horizontalDct(channel).io.output.bits(coefficient)
      )
    )
  }

  io.input.ready := Mux(
    isFirst && supportedStrategy,
    io.output.ready && coefficientsValid && reciprocalAvailable,
    true.B
  )
  for (dct <- verticalDct) {
    dct.io.output.ready := io.output.ready && reciprocalAvailable && isVertical
  }
  for (dct <- horizontalDct) {
    dct.io.output.ready := io.output.ready && reciprocalAvailable && isHorizontal
  }
  reciprocal.io.output.ready :=
    io.output.fire && io.input.bits.adaptiveRawQuant && reciprocalStarted

  when(io.input.fire) {
    assert(
      strategy <= AcStrategyCode.Dct8x16.U,
      "selected AC-strategy cell has an unsupported strategy"
    )
  }
  when(io.output.fire) {
    reciprocalStarted := false.B
  }
  assert(
    !reciprocalStarted || (io.input.valid && isFirst && io.input.bits.adaptiveRawQuant),
    "adaptive VarDCT reciprocal outlived its selected owner"
  )

  io.busy :=
    reciprocal.io.busy || reciprocalStarted ||
      (io.input.valid && isFirst && supportedStrategy)
}

/** Prepared AQ/strategy search directly composed with VarDCT logical tokens. */
class FramePreparedAcStrategyVarDctQuantizeTokenTraceStage(
    c: HjxlConfig = HjxlConfig(),
    coefficientFractionBits: Int = Dct8Approx.FractionBits
) extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(
      Decoupled(new PreparedAcStrategyFrameBlock(c, coefficientFractionBits))
    )
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
    val unsupportedDistance = Output(Bool())
  })

  val strategy = Module(new FramePreparedAcStrategyTraceStage(c, coefficientFractionBits))
  val owners = Module(new AcStrategySelectedCellToVarDctOwnerStage(c, coefficientFractionBits))
  val tokens = Module(
    new FramePreparedVarDctQuantizeTokenTraceStage(
      c,
      coefficientFractionBits = coefficientFractionBits
    )
  )

  strategy.io.config := io.config
  strategy.io.input <> io.input
  owners.io.input.valid := strategy.io.trace.valid
  owners.io.input.bits := strategy.io.selected
  strategy.io.trace.ready := owners.io.input.ready

  tokens.io.config := io.config
  tokens.io.input <> owners.io.output
  io.trace.valid := tokens.io.trace.valid
  io.trace.bits := tokens.io.trace.bits
  tokens.io.trace.ready := io.trace.ready
  io.traceLast := tokens.io.traceLast

  val savedOverflow = RegInit(false.B)
  val savedUnsupportedDistance = RegInit(false.B)
  when(strategy.io.overflow || tokens.io.overflow) {
    savedOverflow := true.B
  }
  when(strategy.io.unsupportedDistance) {
    savedUnsupportedDistance := true.B
  }
  when(io.trace.fire && io.traceLast) {
    savedOverflow := false.B
    savedUnsupportedDistance := false.B
  }

  io.busy := strategy.io.busy || owners.io.busy || tokens.io.busy
  io.overflow := savedOverflow || strategy.io.overflow || tokens.io.overflow
  io.unsupportedDistance := savedUnsupportedDistance || strategy.io.unsupportedDistance
}

/** Complete RGB-to-logical-token route with adaptive DCT/16x8/8x16 search.
  *
  * The RGB AQ/DCT source runs once. Its prepared blocks feed the shared
  * strategy scheduler, whose selected-cell sideband becomes first-block VarDCT
  * owners for quantization and DC/strategy/metadata/AC tokenization.
  */
class FrameAqVarDctQuantizeTokenTraceStage(c: HjxlConfig = HjxlConfig())
    extends Module {
  val io = IO(new Bundle {
    val config = Input(new FrameConfig(c))
    val input = Flipped(Decoupled(new RgbPixel(c)))
    val trace = Decoupled(new StageTrace(c))
    val traceLast = Output(Bool())
    val busy = Output(Bool())
    val overflow = Output(Bool())
    val unsupportedDistance = Output(Bool())
  })

  private val quantizationCoefficientFractionBits = 16

  val blocks = Module(new FrameAqDctBlockStage(c, includeQuantizationPrecision = true))
  val composed = Module(
    new FramePreparedAcStrategyVarDctQuantizeTokenTraceStage(
      c,
      coefficientFractionBits = quantizationCoefficientFractionBits
    )
  )
  val distanceStatus = Module(new DistanceParamsLookup)
  val draining = RegInit(false.B)

  distanceStatus.io.distanceQ8 := io.config.distanceQ8

  blocks.io.config := io.config
  blocks.io.input.bits := io.input.bits
  blocks.io.input.valid := io.input.valid && !draining
  io.input.ready := blocks.io.input.ready && !draining

  composed.io.config := io.config
  composed.io.input.valid := blocks.io.output.valid
  composed.io.input.bits.xyb := blocks.io.output.bits.xyb
  composed.io.input.bits.dct8x8 := blocks.io.output.bits.coefficients
  composed.io.input.bits.quantizationXyb.get :=
    blocks.io.output.bits.quantizationXybQ16.get
  composed.io.input.bits.quantizationDct8x8.get :=
    blocks.io.output.bits.quantizationCoefficientsQ16.get
  composed.io.input.bits.aqMapQ24 := blocks.io.output.bits.aqMapQ24
  composed.io.input.bits.strategyMaskQ16 := blocks.io.output.bits.strategyMaskQ16
  composed.io.input.bits.rawQuant := blocks.io.output.bits.quant
  composed.io.input.bits.distanceQ8 := blocks.io.output.bits.distanceQ8
  composed.io.input.bits.scaleQ16 := blocks.io.output.bits.scaleQ16
  composed.io.input.bits.fixedInvQacQ16 := blocks.io.output.bits.fixedInvQacQ16
  composed.io.input.bits.adaptiveRawQuant := blocks.io.output.bits.adaptiveRawQuant
  composed.io.input.bits.invDcFactorQ16 := blocks.io.output.bits.invDcFactorQ16
  composed.io.input.bits.xQmMultiplierQ16 := blocks.io.output.bits.xQmMultiplierQ16
  composed.io.input.bits.last := blocks.io.output.bits.blockLast
  blocks.io.output.ready := composed.io.input.ready

  io.trace.valid := composed.io.trace.valid
  io.trace.bits := composed.io.trace.bits
  composed.io.trace.ready := io.trace.ready
  io.traceLast := composed.io.traceLast
  io.busy := draining || blocks.io.busy || composed.io.busy
  io.overflow := blocks.io.overflow || composed.io.overflow
  io.unsupportedDistance := !distanceStatus.io.supported || composed.io.unsupportedDistance

  when(blocks.io.output.fire && blocks.io.output.bits.blockLast) {
    draining := true.B
  }
  when(io.trace.fire && io.traceLast) {
    draining := false.B
  }
}
