/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.discovery.marathon

import akka.actor.ActorSystem
import akka.discovery._
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import AppList._
import JsonFormat._
import SimpleServiceDiscovery.{ Resolved, ResolvedTarget }

object MarathonApiSimpleServiceDiscovery {

  /**
   * Finds relevant targets given a pod list. Note that this doesn't filter by name as it is the job of the selector
   * to do that.
   */
  private[marathon] def targets(appList: AppList, portName: String): Seq[ResolvedTarget] = {
    def dockerContainerPort(app: App) =
      app.container
        .flatMap(_.docker)
        .flatMap(_.portMappings)
        .getOrElse(Seq.empty)
        .zipWithIndex
        .find(_._1.name.contains(portName))
        .map(_._2)

    def appContainerPort(app: App) =
      app.container
        .flatMap(_.portMappings)
        .getOrElse(Seq.empty)
        .zipWithIndex
        .find(_._1.name.contains(portName))
        .map(_._2)

    def appPort(app: App) =
      app.portDefinitions.getOrElse(Seq.empty).zipWithIndex.find(_._1.name.contains(portName)).map(_._2)

    // Tasks in the API don't have port names, so we have to look to the app to get the position we use
    def portIndex(app: App) =
      dockerContainerPort(app) orElse appContainerPort(app) orElse appPort(app)

    for {
      app <- appList.apps
      task <- app.tasks.getOrElse(Seq.empty)
      portNumber <- portIndex(app)
      taskHost <- task.host
      taskPorts <- task.ports
      taskAkkaManagementPort <- taskPorts.lift(portNumber)
    } yield ResolvedTarget(host = taskHost, port = Some(taskAkkaManagementPort))
  }
}

/**
 * Service discovery that uses the Marathon API.
 */
class MarathonApiSimpleServiceDiscovery(system: ActorSystem) extends SimpleServiceDiscovery {
  import MarathonApiSimpleServiceDiscovery._
  import system.dispatcher

  private val http = Http()(system)

  private val settings = Settings(system)

  private implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    val uri =
      Uri(settings.appApiUrl).withQuery(Uri.Query("embed" -> "apps.tasks", "embed" -> "apps.deployments",
          "label" -> settings.appLabelQuery.format(lookup.serviceName)))

    val request = HttpRequest(uri = uri)

    for {
      response <- http.singleRequest(request)

      entity <- response.entity.toStrict(resolveTimeout)

      appList <- {
        system.log.debug("Marathon API entity: [{}]", entity.data.utf8String)
        val unmarshalled = Unmarshal(entity).to[AppList]

        unmarshalled.failed.foreach { _ =>
          system.log.error("Failed to unmarshal Marathon API response status [{}], entity: [{}], uri: [{}]",
            response.status.value, entity.data.utf8String, uri)
        }
        unmarshalled
      }

    } yield Resolved(lookup.serviceName, targets(appList, settings.appPortName))
  }

}
