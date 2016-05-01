package com.tribbloids.spookystuff.actions

import com.tribbloids.spookystuff.SpookyContext
import com.tribbloids.spookystuff.row.PageRow

abstract class Actions(override val children: Trace) extends ActionLike {

  final def outputNames = {
    val names = children.map(_.outputNames)
    names.reduceLeftOption(_ ++ _).getOrElse(Set())
  }

  final protected def trunkSeq: Trace = children.flatMap(_.trunk)

  final protected def doInterpolateSeq(pr: PageRow, context: SpookyContext): Trace = Actions.doInterppolateSeq(children, pr, context: SpookyContext)

  //names are not encoded in PageUID and are injected after being read from cache
  override def injectFrom(same: ActionLike): Unit = {
    super.injectFrom(same)
    val zipped = this.children.zip(same.asInstanceOf[Actions].children)

    for (tuple <- zipped) {
      tuple._1.injectFrom(tuple._2.asInstanceOf[tuple._1.type ]) //recursive
    }
  }
}

object Actions {

  def doInterppolateSeq(self: Trace, pr: PageRow, context: SpookyContext): Trace = {
    val seq = self.map(_.doInterpolate(pr, context))

    if (seq.contains(None)) Nil
    else seq.flatMap(option => option)
  }
}