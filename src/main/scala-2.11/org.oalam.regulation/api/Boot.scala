package org.oalam.regulation.api

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import org.oalam.regulation.core._
import org.slf4j.LoggerFactory
import spray.can.Http

import scala.concurrent.duration._

/**
  * Spray actor system that starts a REST hhtp service on
  *
  */
object Boot extends App {

    val logger = LoggerFactory.getLogger(this.getClass.toString)

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("pi-boiler")

    /**
      * Démmarrage des acteurs de la régulation
      */
    val reportHandler = system.actorOf(Props(new ReportHandler()), "reportHandler")

    // setup engine cycles
    val engineDriver = system.actorOf(Props(new GPIOEngineDriver()), "engineDriver")
    val engineCyclesManager = system.actorOf(Props(new EngineCyclesManager()), "engineCyclesManager")

    // setup temperature event handler
    val temperatureEventHandler =
        system.actorOf(Props(new TemperatureEventHandler(new TemperatureSensor())), "temperatureEventHandler")


    // setup boiler
    val settings = new BoilerSettings(
        delayOff = DEFAULT_ENGINES_REST_DURATION,
        restDuration = DEFAULT_SLOWNDOWN_REST_DURATION,
        temperatureConsigne = DEFAULT_CONSIGNE_TEMPERATURE)
    val boiler = system.actorOf(Props(new Boiler(settings)), "boiler")


    /**
      * Démmarrage du web service
      */
    val host = "192.168.1.12"
    //"localhost"//
    val port = 9000

    logger.info(s"starting web service on $host:$port")


    // create and start our service actor
    val service = system.actorOf(Props(new RestServiceActor()), name = "restService")

    implicit val timeout = Timeout(30.seconds)

    // start a new HTTP server on port 8080 with our service actor as the handler
    IO(Http) ? Http.Bind(service, host, port)


}