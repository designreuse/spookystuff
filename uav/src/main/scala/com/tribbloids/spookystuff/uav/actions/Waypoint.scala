package com.tribbloids.spookystuff.uav.actions

import com.tribbloids.spookystuff.extractors.Col
import com.tribbloids.spookystuff.extractors.impl.Lit
import com.tribbloids.spookystuff.row.{DataRowSchema, FetchedRow}
import com.tribbloids.spookystuff.session.Session
import com.tribbloids.spookystuff.uav.actions.mixin.HasExactLocation
import com.tribbloids.spookystuff.uav.spatial.{Anchors, Location}
import com.tribbloids.spookystuff.uav.{UAVConf, UAVConst}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration

/**
  * Created by peng on 18/12/16.
  */
trait WaypointLike extends UAVNavigation with HasExactLocation {

  val to: Col[Location]
  lazy val _start = to.value

  override def getSessionView(session: Session) = new this.SessionView(session)

  class SessionView(session: Session) extends super.SessionView(session) {

    override def engage(): Unit = {
      LoggerFactory.getLogger(this.getClass).info(s"moving to $to")
      link.synch.goto(_start)
    }
  }
}

object WP {
  def apply = Waypoint.apply _
}

// How to accommodate camera & gimbal control? Right now do not refactor! Simplicity first.
case class Waypoint(
                     override val to: Col[Location],
                     override val delay: Duration = UAVConst.UAVNavigation.delayMin
                   ) extends WaypointLike {

  override def doInterpolate(
                              pageRow: FetchedRow,
                              schema: DataRowSchema
                            ): Option[this.type] = {

    val vOpt: Option[Any] = to.resolve(schema).lift
      .apply(pageRow)

    val uavConf = schema.spooky.getConf[UAVConf]

    vOpt.map {
      v =>
        val p = Location.parse(v)
          .replaceAnchors {
            case Anchors.Home =>
              uavConf.home
          }
        this.copy(
          to = Lit(p)
        )
          .asInstanceOf[this.type]
    }
  }
}