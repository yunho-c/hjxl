// See README.md for license details.

package hjxl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.io.Source
import scala.jdk.CollectionConverters._

class FramePreparedVarDctTokenTraceStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val config = HjxlConfig(maxFrameWidth = 32, maxFrameHeight = 16)
  private val maxCoefficients = QuantizeVarDctBlock.MaxCoefficients

  private case class Owner(
      blockX: Int,
      blockY: Int,
      blockOrdinal: Int,
      last: Boolean,
      strategy: Int,
      coefficientCount: Int,
      coveredBlocks: Int,
      rawQuant: Int,
      ytox: Int,
      ytob: Int,
      quantizedAc: Vector[Vector[Int]],
      quantizedDc: Vector[Vector[Int]],
      numNonzeros: Vector[Int],
      shiftedNumNonzeros: Vector[Int]
  )
  private case class Expected(stage: Int, group: Int, index: Int, value: Int, last: Boolean)

  private lazy val fixtureDirectory = {
    val directory = Files.createTempDirectory("hjxl-var-dct-token-fixture-")
    val command = Seq(
      "python3",
      TestPaths.repoRoot.resolve("tools/hjxl_reference.py").toString,
      "--width",
      "32",
      "--height",
      "16",
      "--pattern",
      "checkerboard",
      "--distance",
      "1",
      "--var-dct-token-fixture-dir",
      directory.toString
    )
    val process = new ProcessBuilder(command: _*)
      .directory(TestPaths.repoRoot.toFile)
      .redirectErrorStream(true)
      .start()
    val output = Source.fromInputStream(process.getInputStream).mkString
    val exitCode = process.waitFor()
    withClue(s"oracle command output:\n$output") {
      exitCode mustBe 0
    }
    directory
  }

  private def parseInts(value: String): Vector[Int] =
    if (value.isEmpty) Vector.empty else value.split(";", -1).toVector.map(_.toInt)

  private lazy val owners: Vector[Owner] = {
    val source = Source.fromFile(fixtureDirectory.resolve("owners.csv").toFile)
    try {
      source.getLines().drop(1).map { line =>
        val columns = line.split(",", -1).toVector
        Owner(
          blockX = columns(0).toInt,
          blockY = columns(1).toInt,
          blockOrdinal = columns(2).toInt,
          last = columns(3) == "1",
          strategy = columns(4).toInt,
          coefficientCount = columns(5).toInt,
          coveredBlocks = columns(6).toInt,
          rawQuant = columns(7).toInt,
          ytox = columns(8).toInt,
          ytob = columns(9).toInt,
          quantizedAc = Vector(parseInts(columns(10)), parseInts(columns(11)), parseInts(columns(12))),
          quantizedDc = Vector(parseInts(columns(13)), parseInts(columns(14)), parseInts(columns(15))),
          numNonzeros = parseInts(columns(16)),
          shiftedNumNonzeros = parseInts(columns(17))
        )
      }.toVector
    } finally {
      source.close()
    }
  }

  private lazy val expected: Vector[Expected] = {
    val stages = Map(
      "dc" -> TraceStage.DcTokens,
      "strategy" -> TraceStage.AcStrategy,
      "metadata" -> TraceStage.AcMetadataTokens,
      "ac" -> TraceStage.AcTokens
    )
    val source = Source.fromFile(fixtureDirectory.resolve("trace.csv").toFile)
    try {
      source.getLines().drop(1).map { line =>
        val columns = line.split(",", -1)
        Expected(
          stage = stages(columns(0)),
          group = columns(1).toInt,
          index = columns(2).toInt,
          value = columns(3).toInt,
          last = columns(4) == "1"
        )
      }.toVector
    } finally {
      source.close()
    }
  }

  private def pokeConfig(dut: FramePreparedVarDctTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(32.U)
    dut.io.config.ysize.poke(16.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
  }

  private def pokeOwner(dut: FramePreparedVarDctTokenTraceStage, owner: Owner): Unit = {
    dut.io.input.bits.blockX.poke(owner.blockX.U)
    dut.io.input.bits.blockY.poke(owner.blockY.U)
    dut.io.input.bits.blockOrdinal.poke(owner.blockOrdinal.U)
    dut.io.input.bits.last.poke(owner.last.B)
    dut.io.input.bits.rawQuant.poke(owner.rawQuant.U)
    dut.io.input.bits.ytox.poke(owner.ytox.S)
    dut.io.input.bits.ytob.poke(owner.ytob.S)
    dut.io.input.bits.result.strategy.poke(owner.strategy.U)
    dut.io.input.bits.result.coefficientCount.poke(owner.coefficientCount.U)
    dut.io.input.bits.result.coveredBlocks.poke(owner.coveredBlocks.U)
    for (channel <- 0 until 3) {
      dut.io.input.bits.result.numNonzeros(channel).poke(owner.numNonzeros(channel).U)
      dut.io.input.bits.result.shiftedNumNonzeros(channel).poke(owner.shiftedNumNonzeros(channel).U)
      for (coefficient <- 0 until maxCoefficients) {
        val value = if (coefficient < owner.coefficientCount) owner.quantizedAc(channel)(coefficient) else 0
        dut.io.input.bits.result.quantizedAc(channel)(coefficient).poke(value.S)
      }
      for (covered <- 0 until QuantizeVarDctBlock.MaxCoveredBlocks) {
        val value = if (covered < owner.coveredBlocks) owner.quantizedDc(channel)(covered) else 0
        dut.io.input.bits.result.quantizedDc(channel)(covered).poke(value.S)
      }
    }
  }

  "the mixed-shape frame boundary matches libjxl-tiny DC, metadata, and AC tokens exactly" in {
    owners.map(_.strategy).toSet mustBe
      Set(AcStrategyCode.Dct, AcStrategyCode.Dct16x8, AcStrategyCode.Dct8x16)
    expected.count(_.stage == TraceStage.AcStrategy) mustBe 8
    expected.last.last mustBe true

    simulate(new FramePreparedVarDctTokenTraceStage(config)) { dut =>
      pokeConfig(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      owners.foreach { owner =>
        pokeOwner(dut, owner)
        dut.io.input.valid.poke(true.B)
        var waitCycles = 0
        while (dut.io.input.ready.peekValue().asBigInt == 0 && waitCycles < 132) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"owner ${owner.blockOrdinal} input drain") {
          waitCycles must be < 132
        }
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
      }

      expected.zipWithIndex.foreach { case (row, ordinal) =>
        var waitCycles = 0
        while (dut.io.trace.valid.peekValue().asBigInt == 0 && waitCycles < 160) {
          dut.clock.step()
          waitCycles += 1
        }
        withClue(s"trace $ordinal of ${expected.length} became valid") {
          waitCycles must be < 160
        }

        val stall = ordinal % 37 == 11
        dut.io.trace.ready.poke((!stall).B)
        withClue(s"trace $ordinal expected $row: ") {
          dut.io.trace.bits.stage.expect(row.stage.U)
          dut.io.trace.bits.group.expect(row.group.U)
          dut.io.trace.bits.index.expect(row.index.U)
          dut.io.trace.bits.value.peekValue().asBigInt.toInt mustBe row.value
          dut.io.traceLast.expect(row.last.B)
        }
        if (stall) {
          dut.clock.step(2)
          dut.io.trace.valid.expect(true.B)
          dut.io.trace.bits.stage.expect(row.stage.U)
          dut.io.trace.bits.group.expect(row.group.U)
          dut.io.trace.bits.index.expect(row.index.U)
          dut.io.trace.bits.value.expect(row.value.S)
          dut.io.traceLast.expect(row.last.B)
          dut.io.trace.ready.poke(true.B)
        }
        dut.clock.step()
      }

      dut.io.trace.valid.expect(false.B)
      dut.io.traceLast.expect(false.B)
      dut.io.overflow.expect(false.B)
      var drainCycles = 0
      while (dut.io.busy.peekValue().asBigInt != 0 && drainCycles < 4) {
        dut.clock.step()
        drainCycles += 1
      }
      dut.io.busy.expect(false.B)
    }
  }

  "the independent owner stores reject a raster gap instead of inventing continuation state" in {
    val owner = owners.find(_.blockOrdinal == 1).get

    simulate(new FramePreparedVarDctAcTokenTraceStage(config)) { dut =>
      pokeConfigForAc(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      pokeAcOwner(dut, owner)
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.overflow.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.trace.valid.expect(false.B)
    }

    simulate(new FramePreparedVarDctAcMetadataTokenTraceStage(config)) { dut =>
      pokeConfigForMetadata(dut)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      pokeMetadataOwner(dut, owner)
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.overflow.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.trace.valid.expect(false.B)
    }
  }

  "the VarDCT metadata owner path preserves two-tile CFL prediction and per-cell literals" in {
    val tileConfig = HjxlConfig(maxFrameWidth = 72, maxFrameHeight = 8)
    val expectedMetadata =
      Vector((2, 13), (2, 36), (1, 6), (1, 15)) ++
        Vector.fill(9)((10, 0)) ++
        Vector((6, 8)) ++ Vector.fill(8)((5, 0)) ++
        Vector.fill(9)((0, 8))

    simulate(new FramePreparedVarDctAcMetadataTokenTraceStage(tileConfig)) { dut =>
      pokeMetadataConfig(dut, width = 72, height = 8)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(false.B)
      dut.clock.step()

      for (blockX <- 0 until 9) {
        dut.io.input.bits.blockX.poke(blockX.U)
        dut.io.input.bits.blockY.poke(0.U)
        dut.io.input.bits.blockOrdinal.poke(blockX.U)
        dut.io.input.bits.last.poke((blockX == 8).B)
        dut.io.input.bits.strategy.poke(AcStrategyCode.Dct.U)
        dut.io.input.bits.rawQuant.poke(5.U)
        dut.io.input.bits.ytox.poke((if (blockX < 8) -7 else 11).S)
        dut.io.input.bits.ytob.poke((if (blockX < 8) 3 else -5).S)
        dut.io.input.valid.poke(true.B)
        dut.io.input.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)

      expectedMetadata.zipWithIndex.foreach { case ((context, value), ordinal) =>
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.stage.expect(TraceStage.AcMetadataTokens.U)
        dut.io.trace.bits.group.expect(ordinal.U)
        dut.io.trace.bits.index.expect(context.U)
        dut.io.trace.bits.value.expect(value.S)
        dut.io.traceLast.expect((ordinal == expectedMetadata.length - 1).B)
        dut.clock.step()
      }
      dut.io.trace.valid.expect(false.B)
      dut.io.busy.expect(false.B)
      dut.io.overflow.expect(false.B)
    }
  }

  "the direct quantize-to-token composition carries one horizontal owner through every phase" in {
    val directConfig = HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 8)
    simulate(new FramePreparedVarDctQuantizeTokenTraceStage(directConfig, coefficientFractionBits = 16)) { dut =>
      dut.io.config.xsize.poke(16.U)
      dut.io.config.ysize.poke(8.U)
      dut.io.config.distanceQ8.poke(256.U)
      dut.io.config.fixedPointScale.poke(0.U)
      dut.io.config.fixedInvQacQ16.poke(0.U)
      dut.io.config.fixedRawQuant.poke(0.U)
      dut.io.config.fixedYtox.poke(0.S)
      dut.io.config.fixedYtob.poke(0.S)
      dut.io.config.enableXyb.poke(false.B)
      dut.io.config.enableDct.poke(true.B)
      dut.io.config.enableQuant.poke(true.B)
      dut.io.config.enableTokenize.poke(true.B)
      dut.io.config.tokenSelect.poke(TokenTraceSelect.AcTokens.U)
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.bits.blockX.poke(0.U)
      dut.io.input.bits.blockY.poke(0.U)
      dut.io.input.bits.last.poke(true.B)
      dut.io.input.bits.quantize.strategy.poke(AcStrategyCode.Dct8x16.U)
      dut.io.input.bits.quantize.quant.poke(5.U)
      dut.io.input.bits.quantize.scaleQ16.poke(QuantizeDct8x8Block.DefaultScaleQ16.U)
      dut.io.input.bits.quantize.invQacQ16.poke(QuantizeDct8x8Block.DefaultInvQacQ16.U)
      dut.io.input.bits.quantize.xQmMultiplierQ16.poke((1L << 16).U)
      dut.io.input.bits.quantize.ytox.poke(0.S)
      dut.io.input.bits.quantize.ytob.poke(0.S)
      for (channel <- 0 until 3) {
        dut.io.input.bits.quantize.invDcFactorQ16(channel).poke((1L << 16).U)
        for (coefficient <- 0 until maxCoefficients) {
          dut.io.input.bits.quantize.coefficients(channel)(coefficient).poke(0.S)
        }
      }
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)

      val rows = Vector.newBuilder[(Int, Int, Int, Int, Boolean)]
      var cycles = 0
      var done = false
      while (!done && cycles < 700) {
        if (dut.io.trace.valid.peekValue().asBigInt != 0) {
          val row = (
            dut.io.trace.bits.stage.peekValue().asBigInt.toInt,
            dut.io.trace.bits.group.peekValue().asBigInt.toInt,
            dut.io.trace.bits.index.peekValue().asBigInt.toInt,
            dut.io.trace.bits.value.peekValue().asBigInt.toInt,
            dut.io.traceLast.peekValue().asBigInt != 0
          )
          rows += row
          done = row._5
        }
        dut.clock.step()
        cycles += 1
      }
      withClue("direct VarDCT trace completion") {
        done mustBe true
      }
      val result = rows.result()
      result.count(_._1 == TraceStage.DcTokens) mustBe 6
      result.filter(_._1 == TraceStage.AcStrategy).map(_._4) mustBe Vector(5, 4)
      result.count(_._1 == TraceStage.AcMetadataTokens) mustBe 6
      result.count(_._1 == TraceStage.AcTokens) mustBe 3
      result.last._5 mustBe true
      dut.io.overflow.expect(false.B)
    }
  }

  "the rectangular coefficient scanner reaches coefficient 127 without wrapping" in {
    simulate(new VarDctAcBlockTokenTraceStage(config)) { dut =>
      dut.io.input.valid.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.clock.step()

      dut.io.input.bits.group.poke(0.U)
      dut.io.input.bits.channel.poke(1.U)
      dut.io.input.bits.strategy.poke(AcStrategyCode.Dct8x16.U)
      dut.io.input.bits.coefficientCount.poke(128.U)
      dut.io.input.bits.coveredBlocks.poke(2.U)
      dut.io.input.bits.predictedNonzeros.poke(32.U)
      dut.io.input.bits.numNonzeros.poke(1.U)
      for (coefficient <- 0 until maxCoefficients) {
        dut.io.input.bits.quantized(coefficient).poke((if (coefficient == 127) 1 else 0).S)
      }
      dut.io.input.valid.poke(true.B)
      dut.io.input.ready.expect(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)

      var emitted = 0
      var done = false
      while (!done && emitted < 128) {
        dut.io.trace.valid.expect(true.B)
        dut.io.trace.bits.group.expect(emitted.U)
        if (emitted == 0) {
          dut.io.trace.bits.value.expect(1.S)
          dut.io.traceLast.expect(false.B)
        } else if (emitted == 126) {
          dut.io.trace.bits.value.expect(2.S)
          dut.io.traceLast.expect(true.B)
          done = true
        } else {
          dut.io.trace.bits.value.expect(0.S)
          dut.io.traceLast.expect(false.B)
        }
        dut.clock.step()
        emitted += 1
      }
      emitted mustBe 127
      done mustBe true
      dut.io.trace.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.busy.expect(false.B)
    }
  }

  private def pokeConfigForAc(dut: FramePreparedVarDctAcTokenTraceStage): Unit = {
    dut.io.config.xsize.poke(32.U)
    dut.io.config.ysize.poke(16.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(0.U)
  }

  private def pokeAcOwner(dut: FramePreparedVarDctAcTokenTraceStage, owner: Owner): Unit = {
    dut.io.input.bits.blockX.poke(owner.blockX.U)
    dut.io.input.bits.blockY.poke(owner.blockY.U)
    dut.io.input.bits.blockOrdinal.poke(owner.blockOrdinal.U)
    dut.io.input.bits.last.poke(owner.last.B)
    dut.io.input.bits.rawQuant.poke(owner.rawQuant.U)
    dut.io.input.bits.ytox.poke(owner.ytox.S)
    dut.io.input.bits.ytob.poke(owner.ytob.S)
    dut.io.input.bits.result.strategy.poke(owner.strategy.U)
    dut.io.input.bits.result.coefficientCount.poke(owner.coefficientCount.U)
    dut.io.input.bits.result.coveredBlocks.poke(owner.coveredBlocks.U)
    for (channel <- 0 until 3) {
      dut.io.input.bits.result.numNonzeros(channel).poke(owner.numNonzeros(channel).U)
      dut.io.input.bits.result.shiftedNumNonzeros(channel).poke(owner.shiftedNumNonzeros(channel).U)
      for (coefficient <- 0 until maxCoefficients) {
        val value = if (coefficient < owner.coefficientCount) owner.quantizedAc(channel)(coefficient) else 0
        dut.io.input.bits.result.quantizedAc(channel)(coefficient).poke(value.S)
      }
      for (covered <- 0 until 2) {
        val value = if (covered < owner.coveredBlocks) owner.quantizedDc(channel)(covered) else 0
        dut.io.input.bits.result.quantizedDc(channel)(covered).poke(value.S)
      }
    }
  }

  private def pokeConfigForMetadata(dut: FramePreparedVarDctAcMetadataTokenTraceStage): Unit = {
    pokeMetadataConfig(dut, width = 32, height = 16)
  }

  private def pokeMetadataConfig(
      dut: FramePreparedVarDctAcMetadataTokenTraceStage,
      width: Int,
      height: Int
  ): Unit = {
    dut.io.config.xsize.poke(width.U)
    dut.io.config.ysize.poke(height.U)
    dut.io.config.distanceQ8.poke(256.U)
    dut.io.config.fixedPointScale.poke(0.U)
    dut.io.config.fixedInvQacQ16.poke(0.U)
    dut.io.config.fixedRawQuant.poke(0.U)
    dut.io.config.fixedYtox.poke(0.S)
    dut.io.config.fixedYtob.poke(0.S)
    dut.io.config.enableXyb.poke(false.B)
    dut.io.config.enableDct.poke(true.B)
    dut.io.config.enableQuant.poke(true.B)
    dut.io.config.enableTokenize.poke(true.B)
    dut.io.config.tokenSelect.poke(0.U)
  }

  private def pokeMetadataOwner(dut: FramePreparedVarDctAcMetadataTokenTraceStage, owner: Owner): Unit = {
    dut.io.input.bits.blockX.poke(owner.blockX.U)
    dut.io.input.bits.blockY.poke(owner.blockY.U)
    dut.io.input.bits.blockOrdinal.poke(owner.blockOrdinal.U)
    dut.io.input.bits.last.poke(owner.last.B)
    dut.io.input.bits.strategy.poke(owner.strategy.U)
    dut.io.input.bits.rawQuant.poke(owner.rawQuant.U)
    dut.io.input.bits.ytox.poke(owner.ytox.S)
    dut.io.input.bits.ytob.poke(owner.ytob.S)
  }
}

class FramePreparedVarDctTokenElaborationSpec extends AnyFreeSpec with Matchers {
  "the direct prepared VarDCT quantize-to-token hierarchy elaborates with narrow coefficient memory" in {
    val target = Files.createTempDirectory("hjxl-var-dct-token-elaborate-")
    ChiselStage.emitSystemVerilogFile(
      new FramePreparedVarDctQuantizeTokenTraceStage(
        HjxlConfig(maxFrameWidth = 16, maxFrameHeight = 16)
      ),
      args = Array("--target-dir", target.toString),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
    val files = Files.walk(target)
    val text = try {
      files.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sv"))
        .map(path => Files.readString(path, StandardCharsets.UTF_8))
        .mkString("\n")
    } finally {
      files.close()
    }

    text must include("module FramePreparedVarDctQuantizeTokenTraceStage")
    text must include("module FramePreparedVarDctQuantizeStage")
    text must include("module FramePreparedVarDctTokenTraceStage")
    text must include("module FramePreparedVarDctAcMetadataTokenTraceStage")
    text must include("module FramePreparedVarDctAcTokenTraceStage")
    text must include("module VarDctAcCoefficientFrameStore")
    text must include("module VarDctAcCoefficientTokenTraceStage")
    text must include("io_input_bits_quantize_coefficients_2_127")
    text must include("io_trace_bits_value")
    text must include("io_traceLast")
    text must include("io_overflow")
  }
}
