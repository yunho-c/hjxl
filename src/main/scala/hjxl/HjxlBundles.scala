// See README.md for license details.

package hjxl

import chisel3._

class FrameConfig(c: HjxlConfig) extends Bundle {
  val xsize = UInt(c.coordBits.W)
  val ysize = UInt(c.coordBits.W)
  val distanceQ8 = UInt(16.W)
  val fixedPointScale = UInt(16.W)
  val enableXyb = Bool()
  val enableDct = Bool()
  val enableQuant = Bool()
  val enableTokenize = Bool()
}

class RgbPixel(c: HjxlConfig) extends Bundle {
  val x = UInt(c.coordBits.W)
  val y = UInt(c.coordBits.W)
  val r = SInt(c.pixelBits.W)
  val g = SInt(c.pixelBits.W)
  val b = SInt(c.pixelBits.W)
}

class StageTrace(c: HjxlConfig) extends Bundle {
  val stage = UInt(8.W)
  val group = UInt(c.groupBits.W)
  val index = UInt(32.W)
  val value = SInt(c.traceValueBits.W)
}
