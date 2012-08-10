package com.gu

import java.io.File
import sbt._
import sbt.Keys._

object RequireJS extends Plugin {

  val requireJsAppDir = SettingKey[File]("require-js-app-dir", "The location of the javascript you want to optimize")
  val requireJsDir = SettingKey[File]("require-js-dir", "The location you want the javascript optimized to")
  val requireJsBaseUrl = SettingKey[String]("require-js-base-url", "The base url of requireJs modules")
  val requireJsOptimize = SettingKey[Boolean]("require-js-optimize", "Let requireJs know whether to optimize files or not")

  val requireJsModules = SettingKey[Seq[String]]("require-js-modules", "The requireJs entry modules (usually main - for main.js)")
  val requireJsPaths = SettingKey[Map[String, String]]("require-js-paths", "The requireJS paths mapping (Eg, 'bonzo' -> 'vendor/bonzo-v1.0.1'")

  val requireJsCacheDir = SettingKey[File]("require-js-cache-dir", "location to cache require js files to see if they have changed")

  override lazy val settings = Seq(
    requireJsCacheDir <<= (target) { tar =>
      tar / "sbt-requirejs-cache"
    }
  )

  def requireJsCompiler = (requireJsOptimize, requireJsAppDir, requireJsDir, requireJsBaseUrl, requireJsPaths, requireJsModules, streams, requireJsCacheDir) map {
    (optimize, appDir, dir, baseUrl, paths, modules, s, cacheDir) =>
      implicit val log = s.log

      val optimizeOpt = if (optimize) None else Some("none")

      if (!cacheDir.exists) {
        cacheDir.mkdirs
        cacheDir.mkdir
      }

      val sourceFileDetails: Map[String, Long] = fileDetails(appDir)

      val cacheFileDetails: Map[String, Long] = fileDetails(cacheDir)


      val config = RequireJsConfig(baseUrl, appDir.getAbsolutePath,
        dir.getAbsolutePath, paths, modules.map(Module(_)), optimizeOpt)

      if (canSkipCompile(sourceFileDetails, cacheFileDetails)) {
        log.info("Skipping javascript file optimization")

        //we still need to return the list of expected files for SBT to work
        sourceFileDetails.map{ case (path, _) => path}.map(dir / _).toSeq
      } else {
        log.info("Optimizing javascript files")

        //clear out both destination directories
        // before we start
        (cacheDir ** "**").get.foreach(_.delete)
        (dir ** "*.js").get.foreach(_.delete)

        val optimizedFiles = RequireJsOptimizer.optimize(config)

        //copy files to cache dir so we can check against them later for changes
        IO.copyDirectory(appDir, cacheDir, preserveLastModified = false)

        optimizedFiles
      }
  }

  private def fileDetails(dir: File) = {
      (dir ** "**").get.filterNot(_.isDirectory).map{f =>
        f.absolutePath.replace(dir.getAbsolutePath, "") -> f.lastModified
      }.toMap
  }

  private def canSkipCompile(sourceFileDetails: Map[String, Long], cacheFileDetails: Map[String, Long]): Boolean = {
    val sourceKeys = sourceFileDetails.keySet
    val cachedKeys = cacheFileDetails.keySet

    val sameNumberOfFiles = cacheFileDetails.size == sourceFileDetails.size

    val sameFileNames = cachedKeys.forall(sourceKeys contains)

    val cachedFilesNewerThanSourceFiles = cacheFileDetails.forall {
      case (fileName, cacheFileModified) => sourceFileDetails(fileName) < cacheFileModified
    }

    sameNumberOfFiles && sameFileNames && cachedFilesNewerThanSourceFiles
  }
}



