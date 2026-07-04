// See README.md for license details.

package hjxl

import java.nio.file.Files
import java.nio.file.Path

object TestPaths {
  private def ancestors(path: Path): Seq[Path] = {
    val paths = Vector.newBuilder[Path]
    var current: Path = path
    while (current != null) {
      paths += current
      current = current.getParent
    }
    paths.result()
  }

  val repoRoot: Path = {
    val roots = Seq(
      sys.props.get("chisel.project.root"),
      sys.env.get("HJXL_REPO_ROOT"),
      Some(sys.props("user.dir"))
    ).flatten.map(Path.of(_).toAbsolutePath.normalize)
    val candidates = roots.flatMap(ancestors).distinct

    candidates
      .find(path => Files.isRegularFile(path.resolve("build.sbt")) && Files.isDirectory(path.resolve("tools")))
      .getOrElse {
        val rendered = candidates.mkString(", ")
        throw new IllegalStateException(s"could not locate hjxl repository root from: $rendered")
      }
  }
}
