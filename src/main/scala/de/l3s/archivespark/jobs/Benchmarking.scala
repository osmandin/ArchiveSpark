/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Helge Holzmann (L3S) and Vinay Goel (Internet Archive)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.l3s.archivespark.jobs

import de.l3s.archivespark.benchmarking.warcbase.WarcBase
import de.l3s.archivespark.benchmarking.{Benchmark, BenchmarkLogger}
import de.l3s.archivespark.enrich.functions._
import de.l3s.archivespark.implicits._
import de.l3s.archivespark.utils.HttpArchiveRecord
import de.l3s.archivespark.{ArchiveSpark, ResolvedArchiveRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.warcbase.data.UrlUtils
import org.warcbase.spark.matchbox.RecordTransformers
import org.warcbase.spark.rdd.RecordRDD._

object Benchmarking {
  val times = 5
  val logFile = "benchmarks.txt"
  val logValues = true

  val hbaseId = "HBase"
  val sparkId = "Spark"
  val archiveSparkId = "ArchiveSpark"

  val warcPath = "/data/archiveit/2950_occupy_movement"
  val cdxPath = s"${warcPath}_cdx"
  val hbaseTable = "warcbase_2950_occupy_movement"

  def main(args: Array[String]): Unit = {
    val appName = "ArchiveSpark.Benchmarking"

    val conf = new SparkConf().setAppName(appName)
    implicit val sc = new SparkContext(conf)
    implicit val logger = new BenchmarkLogger(logFile)

    runOneUrl
    runOneDomain
  }

  def archiveSpark(implicit sc: SparkContext) = ArchiveSpark.hdfs(s"$cdxPath/*.cdx", warcPath)

  def warcBase(implicit sc: SparkContext) = WarcBase.loadWarc(s"$warcPath/*.warc.gz")

  def hbase(conf: Configuration => Unit)(implicit sc: SparkContext) = WarcBase.hbase(hbaseTable) { c => conf(c) }

  def rowKey(url: String) = UrlUtils.urlToKey(url)

  def benchmarkArchiveSpark(name: String)(rdd: => RDD[ResolvedArchiveRecord])(implicit sc: SparkContext, logger: BenchmarkLogger) = {
    Benchmark.time(name, archiveSparkId, times) {
      val contentLength = rdd.mapEnrich[String, Int](StringContent, "length") { content => content.length }
      contentLength.filterExists("payload.string.length").map(r => r.get[Int]("payload.string.length").get).sum()
    }.log(logValues)
  }

  def benchmarkSpark(name: String)(rdd: => RDD[RecordTransformers.WARecord])(implicit sc: SparkContext, logger: BenchmarkLogger) = {
    Benchmark.time(name, sparkId, times) {
      rdd.map(r => r.getContentString.length).sum()
    }.log(logValues)
  }

  def benchmarkHbase(name: String)(rdd: => RDD[(Long, String, String, HttpArchiveRecord)])(implicit sc: SparkContext, logger: BenchmarkLogger) = {
    Benchmark.time(name, sparkId, times) {
      rdd.map{case (timestamp, url, mime, record) => record.stringContent.length}.sum()
    }.log(logValues)
  }

  def runOneUrl(implicit sc: SparkContext, logger: BenchmarkLogger) = {
    val name = "one url"
    val url = "http://map.15october.net/reports/view/590/"

    benchmarkArchiveSpark(name) {
      archiveSpark.filter(r => r.originalUrl == url)
    }

    benchmarkSpark(name) {
      warcBase.filter(r => r.getUrl == url)
    }

    benchmarkHbase(name) {
      hbase { c =>
        c.set(TableInputFormat.SCAN_ROW_START, rowKey(url))
        c.set(TableInputFormat.SCAN_ROW_STOP, rowKey(url))
      }
    }
  }

  def runOneDomain(implicit sc: SparkContext, logger: BenchmarkLogger) = {
    val name = "one domain (text/html)"
    val domain = "15october.net"
    val surt = domain.split("\\.").reverse.mkString(",")
    val reverse = domain.split("\\.").reverse.mkString(".")
    val next = reverse.substring(0, reverse.length - 1) + (reverse.charAt(reverse.length - 1) + 1).asInstanceOf[Char]

    benchmarkArchiveSpark(name) {
      archiveSpark
        .filter(r => r.mime == "text/html" && r.surtUrl.matches(s"^$surt[\\,\\)].*"))
    }

    benchmarkSpark(name) {
      warcBase
        .keepMimeTypes(Set("text/html"))
        .filter(r => r.getDomain.matches(s"(^|\\.)${domain + "$"}"))
    }

    benchmarkHbase(name) {
      hbase { c =>
        c.set(TableInputFormat.SCAN_COLUMNS, "text/html")
        c.set(TableInputFormat.SCAN_ROW_START, rowKey(reverse))
        c.set(TableInputFormat.SCAN_ROW_STOP, rowKey(next))
      }.filter{case (time, url, mime, record) => url.matches(s"^$reverse[\\.\\/].*")}
    }
  }
 }