package org.tribbloid.spookystuff.integration

import java.util.Properties

import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import org.tribbloid.spookystuff.SpookyContext

/**
 * Created by peng on 12/2/14.
 */
abstract class IntegrationSuite extends FunSuite with BeforeAndAfterAll {

  import scala.concurrent.duration._

  @transient var sc: SparkContext = _
  @transient var sql: SQLContext = _

  override def beforeAll() {
    val conf: SparkConf = new SparkConf().setAppName("integration")
      .setMaster("local[*]")

    val prop = new Properties()
    prop.load(ClassLoader.getSystemResourceAsStream("rootkey.csv"))
    val AWSAccessKeyId = prop.getProperty("AWSAccessKeyId")
    val AWSSecretKey = prop.getProperty("AWSSecretKey")

    sc = new SparkContext(conf)
    sc.hadoopConfiguration
      .set("fs.s3n.awsAccessKeyId", AWSAccessKeyId)
    sc.hadoopConfiguration
      .set("fs.s3n.awsSecretAccessKey", AWSSecretKey)

    sql = new SQLContext(sc)

    super.beforeAll()
  }

  override def afterAll() {
    if (sc != null) {
      sc.stop()
    }
    super.afterAll()
  }

  lazy val localCacheWriteOnlyEnv = {
    val spooky: SpookyContext = new SpookyContext(sql)
    spooky.setRoot("file://"+System.getProperty("user.home")+"/spooky-integration/")
    spooky.pageExpireAfter = 0.seconds
    spooky
  }

  lazy val localCacheEnv = {
    val spooky: SpookyContext = new SpookyContext(sql)
    spooky.setRoot("file://"+System.getProperty("user.home")+"/spooky-integration/")
    spooky.pageExpireAfter = 10.minutes
    spooky
  }

  lazy val s3CacheWriteOnlyEnv = {
    val spooky: SpookyContext = new SpookyContext(sql)
    spooky.setRoot("s3n://spooky-integration/")
    spooky.pageExpireAfter = 0.seconds
    spooky
  }

  lazy val s3CacheEnv = {
    val spooky: SpookyContext = new SpookyContext(sql)
    spooky.setRoot("s3n://spooky-integration/")
    spooky.pageExpireAfter = 10.minutes
    spooky
  }

  test("local cache") {

    doMain(localCacheWriteOnlyEnv)

    assert(localCacheWriteOnlyEnv.metrics.pagesFetched.value === expectedPages)
    assert(localCacheWriteOnlyEnv.metrics.pagesFetchedFromCache.value === 0)

    doMain(localCacheEnv)

    assert(localCacheEnv.metrics.pagesFetched.value === expectedPages)
    assert(localCacheEnv.metrics.pagesFetchedFromCache.value === expectedPages)
    assert(localCacheEnv.metrics.driverInitialized.value === 0)
    assert(localCacheEnv.metrics.DFSReadSuccess.value > 0)
    assert(localCacheEnv.metrics.DFSReadFail.value === 0)
  }

  test("s3 cache") {

    doMain(s3CacheWriteOnlyEnv)

    assert(s3CacheWriteOnlyEnv.metrics.pagesFetched.value === expectedPages)
    assert(s3CacheWriteOnlyEnv.metrics.pagesFetchedFromCache.value === 0)

    doMain(s3CacheEnv)

    assert(s3CacheEnv.metrics.pagesFetched.value === expectedPages)
    assert(s3CacheEnv.metrics.pagesFetchedFromCache.value === expectedPages)
    assert(s3CacheEnv.metrics.driverInitialized.value === 0)
    assert(s3CacheEnv.metrics.DFSReadSuccess.value > 0)
    assert(s3CacheEnv.metrics.DFSReadFail.value === 0)
  }

  def doMain(spooky: SpookyContext): Unit

  def expectedPages: Int
}