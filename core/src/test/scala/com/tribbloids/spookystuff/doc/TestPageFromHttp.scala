package com.tribbloids.spookystuff.doc

import com.tribbloids.spookystuff.SpookyEnvFixture
import com.tribbloids.spookystuff.actions.Wget
import com.tribbloids.spookystuff.utils.CommonUtils
import org.apache.commons.csv.CSVFormat

class TestPageFromHttp extends SpookyEnvFixture {

  it("wget html, save and load") {

    val results = CommonUtils.retry(5){Wget(HTML_URL).fetch(spooky)}

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc]

    assert(page.mimeType == "text/html")
    assert(page.charset.map(_.toLowerCase).get == "utf-8")
    assert(page.findAll("title").texts.head startsWith "Wikipedia")

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }

  it("wget json, save and load") {

    val results = Wget(JSON_URL).fetch(spooky)

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc]

    assert(page.mimeType == "application/json")
    assert(page.charset.map(_.toLowerCase).get == "utf-8")
    assert(page.\("html_url").texts == "https://github.com/tribbloid" :: Nil)
    assert(page.\\("html_url").texts == "https://github.com/tribbloid" :: Nil)

    assert(page.\("notexist").isEmpty)
    assert(page.\\("notexist").isEmpty)

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }

  it("wget image, save and load") {

    val results = Wget(PNG_URL).fetch(spooky)

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc]

    assert(page.mimeType == "image/png")
    assert(page.charset.map(_.toLowerCase).get == "utf-8")
    assert(page.findAll("title").text.get == "")

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }

  it("wget pdf, save and load") {

    val results = Wget(PDF_URL).fetch(spooky)

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc]

    assert(page.mimeType == "application/pdf")
    assert(page.charset.map(_.toLowerCase).get == "utf-8")
    assert(page.findAll("title").text.get == "Microsoft Word - Document1")

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }

  it("childrenWithSiblings") {
    val page = CommonUtils.retry(5){Wget(HTML_URL).fetch(spooky)}.head.asInstanceOf[Doc]

    val ranges = page.findAllWithSiblings("a.link-box em", -2 to 1)
    assert(ranges.size === 10)
    val first = ranges.head
    assert(first.size === 4)
    assert(first.head.code.get.startsWith("<strong"))
    assert(first(1).code.get.startsWith("<br"))
    assert(first(2).code.get.startsWith("<em"))
    assert(first(3).code.get.startsWith("<br"))
  }

  it("childrenWithSiblings with overlapping elimiation") {
    val page = CommonUtils.retry(5){Wget(HTML_URL).fetch(spooky)}.head.asInstanceOf[Doc]

    val ranges = page.findAllWithSiblings("div.central-featured-lang[lang^=e]", -2 to 2)
    assert(ranges.size === 2)
    val first = ranges.head
    val second = ranges.last
    assert(first.size === 2)
    assert(first.head.attr("class").get === "central-featured-logo")
    assert(first(1).attr("lang").get === "en")
    assert(second.size === 3)
    assert(second.head.attr("class").get.contains("lang"))
    assert(second(1).attr("class").get.contains("lang"))
    assert(second(2).attr("class").get.contains("lang"))
  }

  it("wget xml, save and load") {

    val results = Wget(XML_URL).fetch(spooky)

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc]

    assert(page.mimeType == "application/xml")
    assert(page.charset.map(_.toLowerCase).get == "utf-8")
    assert(page.findAll("title").texts.isEmpty)

    assert(page.findAll("profiles > profile").size == 5)

//        page.findAll("*").flatMap(_.breadcrumb).map(_.mkString("/")).distinct.foreach(println)

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }

  it("wget csv, save and load") {

    val results = Wget(CSV_URL).fetch(spooky)

    assert(results.length === 1)
    val page = results.head.asInstanceOf[Doc].set("csvFormat" -> CSVFormat.newFormat('\t').withQuote('"').withHeader())

    assert(page.mimeType == "text/csv")
    assert(Set("iso-8859-1", "utf-8") contains page.charset.map(_.toLowerCase).get) //the file is just using ASCII chars
    assert(page.findAll("title").texts.isEmpty)

    assert(page.findAll("Name").size == 14)

    //    page.findAll("*").flatMap(_.breadcrumb).map(_.mkString("/")).distinct.foreach(println)

    page.autoSave(spooky,overwrite = true)

    val loadedContent = DocUtils.load(page.saved.head)(spooky)

    assert(loadedContent === page.raw)
  }
}