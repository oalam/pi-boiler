package org.oalam.regulation.api

import akka.actor.Actor
import org.oalam.regulation.core._
import org.slf4j.LoggerFactory
import spray.http.{AllOrigins, HttpHeaders, StatusCodes}
import spray.routing.HttpService

import scala.concurrent.duration._


// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class   RestServiceActor extends Actor with HttpService {


    // the HttpService trait defines only one abstract member, which
    // connects the services environment to the enclosing actor or test
    def actorRefFactory = context

    val boiler = context.actorSelection("../boiler")

    // this actor only runs our route, but you could add
    // other things here, like request stream processing
    // or timeout handling
    def receive = runRoute(myRoute)

    val logger = LoggerFactory.getLogger(this.getClass.toString)


    var lastTemperature = DEFAULT_CONSIGNE_TEMPERATURE
    var lastDelayOff = DEFAULT_ENGINES_REST_DURATION.toSeconds
    var lastRestDuration = DEFAULT_SLOWNDOWN_REST_DURATION.toMinutes

    val queryRoute = {
        get {
            path("action") {
                parameterMap { params =>

                    logger.info(params.mkString(", "))
                    val actionType = params.getOrElse("type", "none")


                    actionType match {
                        case "load" => boiler ! LoadPellets
                        case "start" => boiler ! BoilerStart
                        case "stop" => boiler ! BoilerStop
                        case "shutdown" => boiler ! BoilerShutdown
                        case "set" =>
                            val temperature: Int = Integer.parseInt(params.getOrElse("temperature", lastTemperature.toString))
                            val delayOff: Int = Integer.parseInt(params.getOrElse("delayOff", lastDelayOff.toString))
                            val restDuration: Int = Integer.parseInt(params.getOrElse("restDuration", lastRestDuration.toString))

                            val settings = new BoilerSettings(
                                delayOff = delayOff seconds,
                                restDuration = restDuration minutes,
                                temperatureConsigne = temperature)


                            lastTemperature = temperature
                            lastDelayOff = delayOff
                            lastRestDuration = restDuration


                            boiler ! BoilerUpdateSettings(settings)
                        case _ => logger.info("nothing to do")
                    }
                    // val bucketSize: Int = Integer.parseInt(params.getOrElse("bucketSize", "100"))

                    complete(StatusCodes.Accepted, "ok")

                }
            }
        }
    }

    val myRoute = {
        respondWithHeader(HttpHeaders.`Access-Control-Allow-Origin`(AllOrigins)) {
            pathPrefix("api") {
                pathPrefix("v0.1") {
                    queryRoute
                }
            }
        }
    }
}