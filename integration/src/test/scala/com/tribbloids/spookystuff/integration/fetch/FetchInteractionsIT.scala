package com.tribbloids.spookystuff.integration.fetch

import java.net.URLEncoder

import com.tribbloids.spookystuff.actions.{DropDownSelect, Submit, TextInput, _}
import com.tribbloids.spookystuff.dsl._
import com.tribbloids.spookystuff.integration.IntegrationSuite

/**
  * Created by peng on 12/14/14.
  */
class FetchInteractionsIT extends IntegrationSuite{

  import com.tribbloids.spookystuff.utils.Implicits._

  override def doMain(): Unit = {

    val chain = (
      Visit("http://www.wikipedia.org")
        +> TextInput("input#searchInput","深度学习")
        +> DropDownSelect("select#searchLanguage","zh")
        +> Submit("button.pure-button")
      )


    val RDD = spooky
      .fetch(
        chain
      )
      .persist()

    val pageRows = RDD.unsquashedRDD.collect()

    val finishTime = System.currentTimeMillis()
    assert(pageRows.length === 1)
    assert(pageRows(0).pages.size === 1)
    val uri = pageRows(0).pages.head.uri
    assert((uri endsWith "zh.wikipedia.org/wiki/深度学习") || (uri endsWith "zh.wikipedia.org/wiki/"+URLEncoder.encode("深度学习", "UTF-8")))
    assert(pageRows(0).pages.head.name === Snapshot(DocumentFilters.MustHaveTitle).toString)
    val pageTime = pageRows(0).pageLikes.head.timestamp.getTime
    assert(pageTime < finishTime)
    assert(pageTime > finishTime-120000) //long enough even after the second time it is retrieved from s3 cache

    Thread.sleep(10000) //this delay is necessary to circumvent eventual consistency of HDFS-based cache

    val RDD2 = RDD
      .fetch(
        chain
          +> Snapshot() ~ 'b
      )

    val unionRDD = RDD.union(RDD2)
    val unionRows = unionRDD.unsquashedRDD.collect()

    assert(unionRows.length === 2)
    assert(
      unionRows(0).pages.head.copy(timestamp = null, content = null, saved = null)
        === unionRows(1).pages.head.copy(timestamp = null, content = null, saved = null)
    )

    assert(unionRows(0).pages.head.timestamp === unionRows(1).pages.head.timestamp)
    assert(unionRows(0).pages.head.content === unionRows(1).pages.head.content)
    assert(unionRows(0).pages.head.name === Snapshot(DocumentFilters.MustHaveTitle).toString)
    assert(unionRows(1).pages.head.name === "b")
  }

  override def numPages= spooky.conf.defaultFetchOptimizer match {
//    case WebCacheAware => 1
    case _ => 1
  }
}
