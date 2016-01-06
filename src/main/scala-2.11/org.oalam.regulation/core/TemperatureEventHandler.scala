package org.oalam.regulation.core

import akka.actor.Actor
import akka.event.Logging

import scala.concurrent.duration._

/**
  * Acteur Akka en charge de la lecture de la température via une sonde 1-Wire
  * il lance des évènements lorsque certains seuils sont franchis
  *
  * @param sensor
  * @param tempConsigne
  */
class TemperatureEventHandler(sensor: TemperatureSensor,
                              tempConsigne: Float = 70.0f) extends Actor {
    val log = Logging(context.system, this)

    import context._

    val boiler = context.actorSelection("../boiler")
    val reportHandler = context.actorSelection("../reportHandler")

    var lastDate = System.currentTimeMillis()
    val cancellableLoop = context.system.scheduler.schedule(
        0 milliseconds,
        2000 milliseconds,
        self,
        "tick")

    var lastTemperature = 0.0f
    var lastTemperatureOverConsigneTime = 0L

    override def preStart = {
        println("my path is: " + context.self.path)
    }


    override def receive = {

        case "tick" =>
            val temp = sensor.measure("temperatureEauSortie")
            if (temp.isDefined) {
                val currentTemperature = temp.get
                val currentTime = System.currentTimeMillis()

                /**
                  * relever les seuils montants et descendants
                  */
                if (lastTemperature > 30 && currentTemperature <= 30)
                    boiler ! TemperatureCrossThresholdDown(30, currentTemperature)

                if (lastTemperature < 40 && currentTemperature >= 40)
                    boiler ! TemperatureCrossThresholdUp(40, currentTemperature)

                if (lastTemperature > 40 && currentTemperature <= 40)
                    boiler ! TemperatureCrossThresholdDown(40, currentTemperature)

                if (lastTemperature < 60 && currentTemperature >= 60)
                    boiler ! TemperatureCrossThresholdUp(60, currentTemperature)

                if (lastTemperature > 60 && currentTemperature <= 60)
                    boiler ! TemperatureCrossThresholdDown(60, currentTemperature)

                if (lastTemperature < 90 && currentTemperature >= 90) {
                    boiler ! TemperatureCrossThresholdUp(90, currentTemperature)
                }


                if (lastTemperature > 90 && currentTemperature <= 90)
                    boiler ! TemperatureCrossThresholdDown(90, currentTemperature)


                /**
                  * la temperature de l'eau dépasse la température de consigne
                  * on doit signaler l'entrée en mode ralenti
                  */
                if (currentTemperature >= tempConsigne && lastTemperature < tempConsigne) {
                    lastTemperatureOverConsigneTime = currentTime
                    boiler ! TemperatureCrossConsigneUp(tempConsigne, currentTemperature)
                    reportHandler ! TemperatureCrossConsigneUp(tempConsigne, currentTemperature)
                }

                /**
                  * la température de l'eau revient 3 degrés en-dessous de la température
                  * de consigne, on doit signaler la sortie du mode ralenti
                  */
                if (currentTemperature <= 61 /*tempConsigne - 3*/ && lastTemperatureOverConsigneTime != 0) {
                    boiler ! TemperatureCrossConsigneDown(tempConsigne, currentTemperature)
                    reportHandler ! TemperatureCrossConsigneDown(tempConsigne, currentTemperature)
                    // reset du mode ralenti
                    lastTemperatureOverConsigneTime = 0L
                }


                // send a report sometimes
                if (currentTime % 3 == 0)
                    reportHandler ! TemperatureReport(lastTemperature, currentTemperature)

                lastTemperature = currentTemperature
                lastDate = System.currentTimeMillis()
            }
    }
}