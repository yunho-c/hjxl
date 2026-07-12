// See README.md for license details.

package hjxl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CflTileGeometrySpec extends AnyFreeSpec with Matchers {
  "CflTileGeometry maps exact 72x72 block ordinals into a 2x2 tile grid" in {
    val xBlocks = 9
    val yBlocks = 9
    val xTiles = 2
    val tiles = Seq.tabulate(xBlocks * yBlocks) { ordinal =>
      CflTileGeometry.blockTile(ordinal, xBlocks, xTiles)
    }

    tiles(0) mustBe 0
    tiles(7) mustBe 0
    tiles(8) mustBe 1
    tiles(63) mustBe 0
    tiles(71) mustBe 1
    tiles(72) mustBe 2
    tiles(79) mustBe 2
    tiles(80) mustBe 3

    tiles.groupBy(identity).view.mapValues(_.size).toMap mustBe
      Map(0 -> 64, 1 -> 8, 2 -> 8, 3 -> 1)
  }

  "CflTileGeometry maps skinny two-tile frames without a spurious x-tile" in {
    val xBlocks = 1
    val yBlocks = 9
    val xTiles = 1
    val tiles = Seq.tabulate(xBlocks * yBlocks) { ordinal =>
      CflTileGeometry.blockTile(ordinal, xBlocks, xTiles)
    }

    tiles.take(8).distinct mustBe Seq(0)
    tiles.last mustBe 1
    tiles.groupBy(identity).view.mapValues(_.size).toMap mustBe Map(0 -> 8, 1 -> 1)
  }
}
