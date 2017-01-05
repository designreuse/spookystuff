package com.tribbloids.spookystuff.mav.telemetry

import com.tribbloids.spookystuff.mav.actions._
import com.tribbloids.spookystuff.mav.dsl.{LinkFactories, LinkFactory}
import com.tribbloids.spookystuff.mav.{MAVConf, ReinforcementDepletedException}
import com.tribbloids.spookystuff.session.python._
import com.tribbloids.spookystuff.session.{LocalCleanable, Session}
import com.tribbloids.spookystuff.utils.{SpookyUtils, TreeException}
import com.tribbloids.spookystuff.{PyInterpreterException, SpookyContext, caching}
import org.slf4j.LoggerFactory

import scala.util.Try

case class Drone(
                  // remember, one drone can have several telemetry
                  // endpoints: 1 primary and several backups (e.g. text message-based)
                  // TODO: implement telemetry backup mechanism, can use MAVproxy's multiple master feature
                  connStrs: Seq[String], // [protocol]:ip:port;[baudRate]
                  baudRate: Int = MAVConf.DEFAULT_BAUDRATE,
                  ssid: Int = MAVConf.EXECUTOR_SSID,
                  frame: Option[String] = None,
                  name: String = "DRONE"
                ) {

  def nativeEndpoint = Endpoint(
    connStrs.head,
    baudRate,
    ssid,
    frame
  )

  override def toString = s"$name:$frame@${connStrs.head}"
}

case class Endpoint(
                     connStr: String, // [protocol]:ip:port;[baudRate]
                     baudRate: Int = MAVConf.DEFAULT_BAUDRATE,
                     ssid: Int = MAVConf.EXECUTOR_SSID,
                     frame: Option[String] = None
                   ) extends CaseInstanceRef with SingletonRef with LocalCleanable {


}

object Link {

  // max 1 per task/thread.
  val driverLocal: caching.ConcurrentMap[PythonDriver, Link] = caching.ConcurrentMap()

  // connStr -> (link, isBusy)
  // only 1 allowed per connStr, how to enforce?
  val existing: caching.ConcurrentMap[Drone, LinkWithContext] = caching.ConcurrentMap()

  // won't be used to create any link before its status being recovered by ping daemon.
  val blacklist: caching.ConcurrentSet[Drone] = caching.ConcurrentSet()

  def getOrInitialize(
                       candidates: Seq[Drone],
                       factory: LinkFactory,
                       session: Session,
                       locationOpt: Option[Location] = None
                     ): Link = {
    session.initializeDriverIfMissing {
      getOrCreate(candidates, factory, session, locationOpt)
    }
  }

  /**
    * create a telemetry link based on the following order:
    * if one is already created in the same task, reuse it
    * if one is created in a previous task and not busy, use it. The busy status is controlled by whether it has an active python driver.
    *   - if its generated by an obsolete ProxyFactory, terminate the link and immediately recreate a new one with the new ProxyFactory,
    *     being created means the drone is already in the air, and can be deployed much faster
    * * if multiple are created by previous tasks and not busy, use the one that is closest to the first waypoint * (not implemented yet)
    * If none of the above exists, create one from candidates from scratch
    * remember: once the link is created its proxy is bind to it until death.
    */
  def getOrCreate(
                   candidates: Seq[Drone],
                   factory: LinkFactory,
                   session: Session,
                   locationOpt: Option[Location] = None
                 ): Link = {

    val local = driverLocal
      .get(session.pythonDriver)

    local.foreach {
      link =>
        LoggerFactory.getLogger(this.getClass).info(
          s"Using existing Link ${link.drone} with the same driver"
        )
    }

    val result = local
      .getOrElse {
        val newLink = recommissionIdle(candidates, factory, session, locationOpt)
          .getOrElse {
            selectAndCreate(candidates, factory, session)
          }
        try {
          newLink.link.Py(session)
        }
        catch {
          case e: Throwable =>
            newLink.clean()
            throw e
        }

        newLink.link
      }
    result
  }

  // CAUTION: this will refit the telemetry link with new Proxy and clean the old one if ProxyFactory is different.
  def recommissionIdle(
                        candidates: Seq[Drone],
                        factory: LinkFactory,
                        session: Session,
                        locationOpt: Option[Location] = None
                      ): Option[LinkWithContext] = {

    val result = this.synchronized {
      val existingCandidates: Seq[LinkWithContext] = candidates.collect {
        Function.unlift {
          endpoint =>
            existing.get(endpoint)
        }
      }

      val idleLinks = existingCandidates.filter {
        link =>
          link.link.isIdle
      }

      //TODO: find the closest one!
      val idleLinkOpt = idleLinks.headOption

      idleLinkOpt match {
        case Some(idleLink) =>
          val recommissioned = {
            if (LinkFactories.canCreate(factory, idleLink)) {
              idleLink.link.onHold = true
              LoggerFactory.getLogger(this.getClass).info {
                s"Recommissioning telemetry Link for ${idleLink.link.drone} with old proxy"
              }
              idleLink
            }
            else {
              idleLink.clean()
              // recreate proxy
              val link = factory.apply(idleLink.link.drone)
              link.onHold = true
              LoggerFactory.getLogger(this.getClass).info {
                s"Recommissioning telemetry Link for ${link.drone} with new proxy"
              }
              link.wContext(
                session.spooky,
                factory
              )
            }
          }

          Some(recommissioned)
        case None =>
          val info = if (existingCandidates.isEmpty) {
            val msg = s"No existing telemetry Link for ${candidates.mkString("[", ", ", "]")}, existing links are:"
            val hint = Link.existing.keys.toList.mkString("[", ", ", "]")
            msg + "\n" + hint
          }
          else {
            existingCandidates.map {
              link =>
                assert(!link.link.isIdle)
                if (link.link.onHold) s"${link.link.drone} is on hold"
                else s"${link.link.drone} is busy"
            }
              .mkString("\n")
          }
          LoggerFactory.getLogger(this.getClass).info{info}
          None
      }
    }

    result
  }

  def selectAndCreate(
                       candidates: Seq[Drone],
                       factory: LinkFactory,
                       session: Session
                     ): LinkWithContext = {

    val newLink = this.synchronized {
      val endpointOpt = candidates.find {
        v =>
          !existing.contains(v) &&
            !blacklist.contains(v)
      }
      val endpoint = endpointOpt
        .getOrElse(
          throw new ReinforcementDepletedException(
            candidates.map {
              candidate =>
                if (blacklist.contains(candidate)) s"$candidate is unreachable"
                else s"$candidate is busy"
            }
              .mkString(", ")
          )
        )

      create(endpoint, factory, session.spooky)
    }
    newLink
  }

  def create(
              endpoint: Drone,
              factory: LinkFactory,
              spooky: SpookyContext
            ): LinkWithContext = {

    val link = factory.apply(endpoint)
    link.onHold = true
    link.wContext(
      spooky,
      factory
    )
  }

  class PyBindingImpl(
                       override val ref: Link,
                       override val driver: PythonDriver,
                       override val spookyOpt: Option[SpookyContext]
                     ) extends com.tribbloids.spookystuff.session.python.PyBinding(ref, driver, spookyOpt) {

    $Helpers.autoStart()
    Link.driverLocal += driver -> ref

    override def cleanImpl(): Unit = {
      super.cleanImpl()
      val localOpt = Link.driverLocal.get(driver)
      localOpt.foreach {
        v =>
          if (v eq this.ref)
            Link.driverLocal -= driver
      }
    }

    object $Helpers {
      var isStarted: Boolean = false

      def _startDaemons(): Unit = {
        if (!isStarted) {
          ref.proxyOpt.foreach {
            _.PY.start()
          }
          ref.primaryEndpoint._Py(driver, spookyOpt).start()
        }
        isStarted = true
      }

      def stopDaemons(): Unit = {
        ref.primaryEndpoint._Py(driver, spookyOpt).stop()
        ref.proxyOpt.foreach {
          _.PY.stop()
        }
        isStarted = false
      }

      def withDaemonsUp[T](fn: => T) = {
        try {
          _startDaemons()
          fn
        }
        catch {
          case e: Throwable =>
            stopDaemons()
            throw e
        }
      }

      // will retry 6 times, try twice for Vehicle.connect() in python, if failed, will restart proxy and try again (3 times).
      // after all attempts failed will stop proxy and add endpoint into blacklist.
      def autoStart(): Unit = try {
        val retries = spookyOpt.map(
          spooky =>
            spooky.conf.submodules.get[MAVConf]().connectionRetries
        )
          .getOrElse(MAVConf.CONNECTION_RETRIES)
        SpookyUtils.retry(retries) {
          withDaemonsUp(Unit)
        }
      }
      catch {
        case e: PyInterpreterException => //this indicates a possible port conflict
          //TODO: enable after ping daemon

          try {
            ref.detectPortConflicts(Option(e.cause).toSeq)
          }
          catch {
            case ee: Throwable =>
              throw e.copy(
                cause = ee
              )
          }
          throw e.copy(code = e.code + "\n\n\t### No port conflict detected ###")
      }
    }
  }
}

/**
to keep a drone in the air, a python daemon process D has to be constantly running to
supervise task-irrelevant path planning (e.g. RTL/Position Hold/Avoidance).
This process outlives each task. Who launches D? how to ensure smooth transitioning
of control during Partition1 => D => Partition2 ? Can they share the same
Connection / Endpoint / Proxy ? Do you have to make them picklable ?

GCS:UDP:xxx ------------------------> Proxy:TCP:xxx -> Drone
                                   /
TaskProcess -> Connection:UDP:xx -/
            /
DaemonProcess   (can this be delayed to be implemented later? completely surrender control to GCS after Altitude Hold)
  is Vehicle picklable? if yes then that changes a lot of things.
  but if not ...
    how to ensure that an interpreter can takeover and get the same vehicle?
  */
case class Link(
                 drone: Drone,
                 executorOuts: Seq[String] = Nil, // cannot have duplicates
                 gcsOuts: Seq[String] = Nil
               ) extends NoneRef with SingletonRef with LocalCleanable {

  {
    if (executorOuts.isEmpty) assert(gcsOuts.isEmpty, "No endpoint for executor")
  }

  val outs: Seq[String] = executorOuts ++ gcsOuts
  val allURI = (drone.connStrs ++ outs).distinct

  val nativeEndpoint: Endpoint = drone.nativeEndpoint
  val endpointsForExecutor = if (executorOuts.isEmpty) {
    Seq(nativeEndpoint)
  }
  else {
    executorOuts.map {
      out =>
        nativeEndpoint.copy(connStr = out)
    }
  }
  //always initialized in Python when created from companion object
  val primaryEndpoint: Endpoint = endpointsForExecutor.head

  val endpointsForGCS = {
    gcsOuts.map {
      out =>
        nativeEndpoint.copy(connStr = out)
    }
  }

  val allEndpoints: Seq[Endpoint] = (Seq(nativeEndpoint) ++ endpointsForExecutor ++ endpointsForGCS).distinct

  override type Binding = Link.PyBindingImpl

  /**
    * set true to block being used by another thread before its driver is created
    */
  @volatile var onHold: Boolean = true
  def isIdle: Boolean = {
    !onHold && validDriverToBindings.isEmpty
  }

  //mnemonic
  @volatile private var _proxyOpt: Option[Proxy] = None
  def proxyOpt: Option[Proxy] = _proxyOpt.orElse {
    this.synchronized {
      _proxyOpt = if (outs.isEmpty) None
      else {
        val proxy = Proxy(
          nativeEndpoint.connStr,
          outs,
          nativeEndpoint.baudRate,
          name = drone.name
        )
        Some(proxy)
      }
      _proxyOpt
    }
  }

  var home: Option[LocationGlobal] = None
  var currentLocation: Option[LocationGlobal] = None

  def wContext(
                spooky: SpookyContext,
                factory: LinkFactory
              ) = LinkWithContext(
    this,
    spooky,
    factory
  )

  //  def sublink(index: Int, ssid: Int): Sublink = {
  //    val result = new Sublink(this,index, ssid)
  //    result.detectPortConflicts()
  //    result
  //  }

  override protected def newPy(driver: PythonDriver, spookyOpt: Option[SpookyContext]): Link.PyBindingImpl = {
    val result = new Link.PyBindingImpl(this, driver, spookyOpt)
    onHold = false
    result
  }

  def _distinctEndpoint: Boolean = true

  def detectPortConflicts(causes: Seq[Throwable] = Nil): Unit = {
    val existing = Link.existing.values.toList.map(_.link) // remember to clean up the old one to create a new one
    val notThis = existing.filterNot(_ eq this)
    val includeThis: Seq[Link] = notThis ++ Seq(this)
    val s1 = if (_distinctEndpoint){
      val ss1 = Seq(
        Try(assert(Link.existing.get(drone).forall(_.link eq this), s"Conflict: endpoint index ${nativeEndpoint.connStr} is already used")),
        Try(assert(!notThis.exists(_.nativeEndpoint.connStr == nativeEndpoint.connStr), s"Conflict: endpoint ${nativeEndpoint.connStr} is already used"))
      )
      val allConnStrs: Map[String, Int] = includeThis.flatMap(_.drone.connStrs)
        .groupBy(identity)
        .mapValues(_.size)
      val ss2 = allConnStrs.toSeq.map {
        tuple =>
          Try(assert(tuple._2 == 1, s"${tuple._2} endpoints has identical uri ${tuple._1}"))
      }
      ss1 ++ ss2
    }
    else {
      Nil
    }
    val allExecutorOuts: Map[String, Int] = includeThis.flatMap(_.executorOuts)
      .groupBy(identity)
      .mapValues(_.size)
    val s = s1 ++ allExecutorOuts.toSeq.map {
      tuple =>
        Try(assert(tuple._2 == 1, s"${tuple._2} executor out has identical uri ${tuple._1}"))
    }

    TreeException.&&&(s, extra = causes)
  }

  def getCurrentLocation: LocationGlobal = {

    val locations = primaryEndpoint.PY.vehicle.location
    val global = locations.global_frame.$MSG.get.cast[LocationGlobal]
    //      val globalRelative = locations.global_relative_frame.$MSG.get.cast[LocationGlobalRelative]
    //      val local = locations.local_frame.$MSG.get.cast[LocationLocal]

    //      val result = LocationBundle(
    //        global,
    //        globalRelative,
    //        local
    //      )
    this.currentLocation = Some(global)
    global
  }

  var isDryrun = false
  //finalizer may kick in and invoke it even if its in Link.existing
  override protected def cleanImpl(): Unit = {

    allEndpoints.foreach(
      v =>
        v.clean()
    )
    _proxyOpt.foreach(_.clean())
    super.cleanImpl()
    //TODO: move to LinkWithContext?
    val existingOpt = Link.existing.get(drone)
    existingOpt.foreach {
      v =>
        if (v.link eq this)
          Link.existing -= drone
        else {
          if (!isDryrun) throw new AssertionError("THIS IS NOT A DRYRUN OBJECT! SO ITS CREATED ILLEGALLY!")
        }
    }
    //otherwise its a zombie Link created by LinkFactories.canCreate
  }
}

case class LinkWithContext(
                            link: Link,
                            spooky: SpookyContext,
                            factory: LinkFactory
                          ) extends LocalCleanable {
  try {
    link.detectPortConflicts()

    Link.existing += link.drone -> this
    spooky.metrics.linkCreated += 1
  }
  catch {
    case e: Throwable =>
      this.clean()
      throw e
  }

  protected override def cleanImpl(): Unit = {

    link.clean()
    spooky.metrics.linkDestroyed += 1
  }
}