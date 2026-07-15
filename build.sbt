// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "io.github.yunho_c"

// FIRTool/Verilator suites elaborate large, distinct tops. Unbounded suite
// parallelism can push otherwise-correct full runs into swap thrash.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 2)

val chiselVersion = "7.13.0"

lazy val root = (project in file("."))
  .settings(
    name := "hjxl",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
