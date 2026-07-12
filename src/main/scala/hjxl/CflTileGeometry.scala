// See README.md for license details.

package hjxl

import chisel3._
import chisel3.util._

object CflTileGeometry {
  val BlocksPerTile: Int = HjxlConstants.TileDim / HjxlConstants.BlockDim

  def blockTile(blockOrdinal: Int, xBlocks: Int, xTiles: Int): Int = {
    require(blockOrdinal >= 0, "blockOrdinal must be nonnegative")
    require(xBlocks > 0, "xBlocks must be positive")
    require(xTiles > 0, "xTiles must be positive")
    val blockX = blockOrdinal % xBlocks
    val blockY = blockOrdinal / xBlocks
    (blockY / BlocksPerTile) * xTiles + (blockX / BlocksPerTile)
  }

  def blockTile(blockOrdinal: UInt, xBlocks: UInt, xTiles: UInt): UInt = {
    val xBlocksSafe = Mux(xBlocks === 0.U, 1.U, xBlocks)
    val xTilesSafe = Mux(xTiles === 0.U, 1.U, xTiles)
    val blockX = blockOrdinal - (blockOrdinal / xBlocksSafe) * xBlocksSafe
    val blockY = blockOrdinal / xBlocksSafe
    (blockY / BlocksPerTile.U) * xTilesSafe + (blockX / BlocksPerTile.U)
  }
}
