package com.tribbloids.spookystuff.uav.spatial

import org.apache.spark.sql.catalyst.trees.TreeNode

import scala.language.implicitConversions

object SpatialRelation {

  implicit def fromTuple(tuple: (Coordinate, Anchor)) = SpatialRelation(tuple._1, tuple._2)
}

/**
  * Created by peng on 15/02/17.
  */
case class SpatialRelation(
                            coordinate: Coordinate,
                            from: Anchor
                          ) extends TreeNode[SpatialRelation] {

  /**
    * @param from2 origin of the new coordinate system
    * @param system2 type of the new coordinate system
    * @return new coordinate
    */
  def project(from2: Anchor, system2: CoordinateSystem, ic: SearchHistory): Option[system2.V] = {
    coordinate.project(from, from2, system2, ic)//.map(_.asInstanceOf[system2.V])
  }

  override def simpleString: String = {
    from match {
      case Location(_) =>
        coordinate.toString
      case _ =>
        s"${coordinate.toString} +- $from"
    }
  }

  override def children: Seq[SpatialRelation] = {
    from match {
      case Location(seq) =>
        seq
      case _ =>
        Nil
    }
  }
}