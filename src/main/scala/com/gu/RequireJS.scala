package com.gu

import java.io.File
import sbt._
import sbt.Keys._

object RequireJS extends Plugin {

  val requireJsAppDir = SettingKey[File]("require-js-app-dir", "The location of the javascript you want to optimize")
  val requireJsDir = SettingKey[File]("require-js-dir", "The location you want the javascript optimized to")
  val requireJsBaseUrl = SettingKey[String]("require-js-base-url", "The base url of requireJs modules")
  val requireJsOptimize = SettingKey[Boolean]("require-js-optimize", "Let requireJs know whether to optimize files or not")
  val requireJsUglify = SettingKey[Map[String, Boolean]]("require-js-uglify", "Options to pass to uglify process")
  val requireJsWrap = SettingKey[Map[String, String]]("require-js-wrap", "Paths to files to include at begining and end of built file")
  val requireJsModules = SettingKey[Seq[String]]("require-js-modules", "The requireJs entry modules (usually main - for main.js)")
  val requireJsPaths = SettingKey[Map[String, String]]("require-js-paths", "The requireJS paths mapping (Eg, 'bonzo' -> 'vendor/bonzo-v1.0.1'")

  val requireJsCacheDir = SettingKey[File]("require-js-cache-dir", "location to cache require js files to see if they have changed")

  override lazy val settings = Seq(
    requireJsCacheDir <<= (target) { tar =>
      tar / "sbt-requirejs-cache"
    }
  )

  def requireJsCompiler = (requireJsUglify, requireJsOptimize, requireJsAppDir, requireJsDir, requireJsBaseUrl, requireJsPaths, requireJsModules, streams, requireJsCacheDir, requireJsWrap
    ) map {
    (uglify, optimize, appDir, dir, baseUrl, paths, modules, s, cacheDir, wrap) =>
      implicit val log = s.log

      //we cannot write directly to resources dir as the optimizer deletes everything in there
      //and not only these files are in there
      val tmpDir = IO.createTemporaryDirectory

      tmpDir.deleteOnExit()

      val optimizeOpt = if (optimize) None else Some("none")

      if (!cacheDir.exists) {
        cacheDir.mkdirs
        cacheDir.mkdir
      }

      val sourceFileDetails: Map[String, Long] = fileDetails(appDir)

      val cacheFileDetails: Map[String, Long] = fileDetails(cacheDir)


      val config = RequireJsConfig(baseUrl, appDir.getAbsolutePath,
        tmpDir.getAbsolutePath, paths, modules.map(Module(_)), optimizeOpt, wrap, uglify)

      if (canSkipCompile(sourceFileDetails, cacheFileDetails)) {
        log.info("Skipping javascript file optimization")

        //we still need to return the list of expected files for SBT to work
        sourceFileDetails.map{ case (path, _) => (dir / path)}.toSeq

      } else {
        log.info("Optimizing javascript files")

        //clear out both destination directories before we start
        clearCachedFiles(cacheDir, dir)

        val optimizedFiles = RequireJsOptimizer.optimize(config)

        //copy files to resources dir
        IO.copyDirectory(tmpDir, dir, overwrite = true, preserveLastModified = false)

        //copy files to cache dir so we can check against them later for changes
        IO.copyDirectory(appDir, cacheDir, preserveLastModified = false)


        optimizedFiles.flatMap(_.rebase(tmpDir, dir))
      }
  }


  private def clearCachedFiles(cacheDir: Types.Id[File], dir: Types.Id[File]) {
    (cacheDir ** "**").get.foreach {
      fileInCacheDir =>
        fileInCacheDir.relativeTo(cacheDir).foreach {
          path =>
            (dir / path.getPath).delete()
        }
        fileInCacheDir.delete
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



