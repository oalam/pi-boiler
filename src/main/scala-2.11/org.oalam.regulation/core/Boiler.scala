package org.oalam.regulation.core

import akka.actor.Actor
import akka.event.Logging

import scala.concurrent.duration._


case class BoilerSettings(delayOff: FiniteDuration = 20 seconds,
                          temperatureConsigne: Float = 75.0f,
                          restDuration: FiniteDuration = 20 minutes)


/**
  * La chaudière à granulés
  */
class Boiler(var settings: BoilerSettings) extends Actor {






    val log = Logging(context.system, this)
    var lastStateChangeDate = System.currentTimeMillis()
    val engineCyclesManager = context.actorSelection("../engineCyclesManager")
    val reportHandler = context.actorSelection("../reportHandler")
    val engineDriver = context.actorSelection("../engineDriver")
    val temperatureEventHandler = context.actorSelection("../temperatureEventHandler")


    private def overHeating(currentTemperature: Float) = {
        reportHandler ! TemperatureTooHigh(currentTemperature)
        engineCyclesManager ! StopBurningCycle
        engineDriver ! StartEngine(BoilerEngine.Laddomat)
        engineDriver ! StartEngine(BoilerEngine.Vanne4V)
    }

    override def preStart = {
        log.info("starting : " + context.self.path)
    }

    override def receive = {

        case TemperatureCrossThresholdUp(threshold: Float, currentTemperature: Float) =>

            /**
              * démarrage circulateur si la température dépasse 40°
              */
            if (threshold == 40)
                engineDriver ! StartEngine(BoilerEngine.Vanne4V)


            /**
              * démarrage laddomat si la température dépasse 60°
              */
            if (threshold == 60) {
                engineDriver ! StartEngine(BoilerEngine.Laddomat)
            }


            /**
              * démarrage laddomat si la température dépasse 90°
              */
            if (threshold == 90) {
                overHeating(currentTemperature)
            }



        /**
          * seuil de temperature mini de fonctionnement
          *
          * 1. arret circulateur si la température repasse en-dessous de 40°
          * 2. si au-dela de 30' de fonctionnement la temperature descend en dessous de 30°
          * la chaudiere se verouille
          */
        case TemperatureCrossThresholdDown(threshold: Float, currentTemperature: Float) =>
            log.debug(s"got TemperatureCrossThresholdDown(threshold: $threshold, currentTemperature: $currentTemperature )")


          /*  if (threshold == 40)
                engineDriver ! StopEngine(BoilerEngine.Vanne4V)*/


            if (threshold == 30) {
                log.warning(s"currentTemperature: $currentTemperature < 30°C => verouillage chaudiere")
                reportHandler ! TemperatureTooLow(currentTemperature)
                engineCyclesManager ! StopBurningCycle
                engineDriver ! StopEngine(BoilerEngine.Laddomat)
           //     engineDriver ! StopEngine(BoilerEngine.Vanne4V)
            }

        /**
          * La zone haute de température de consigne est atteinte
          * il faut passer en mode ralenti
          */
        case TemperatureCrossConsigneUp(tempConsigne: Float, currentTemperature: Float) =>
            if (currentTemperature < 90) {
                engineCyclesManager ! StartSlowMotionCycle(settings.restDuration)
            }else{
                overHeating(currentTemperature)
            }


        /**
          * La zone basse de température de consigne est atteinte
          * il faut relancer des cycles de chauffe
          */
        case TemperatureCrossConsigneDown(tempConsigne: Float, currentTemperature: Float) =>
            if (currentTemperature < 90)
                engineCyclesManager ! StartBurningCycle(
                    HEATING_MODE_ENGINES_RUNNING_DURATION,
                    settings.delayOff,
                    BURNER_ENGINE_RUNNING_POST_DURATION)
            else{
                overHeating(currentTemperature)
            }


        case BoilerStart =>
            engineDriver ! StartEngine(BoilerEngine.Vanne4V)
            engineCyclesManager ! StartBurningCycle(
                HEATING_MODE_ENGINES_RUNNING_DURATION,
                settings.delayOff,
                BURNER_ENGINE_RUNNING_POST_DURATION)

        case BoilerStop =>
            engineCyclesManager ! StopBurningCycle


        case BoilerShutdown =>
            reportHandler ! BoilerShutdown
            engineCyclesManager ! BoilerShutdown
            engineDriver ! BoilerShutdown
            Thread.sleep(500)
            context.system.terminate()


        case BoilerUpdateSettings(newSettings: BoilerSettings) =>
            settings = newSettings
            temperatureEventHandler ! BoilerUpdateSettings(settings)
            reportHandler ! BoilerUpdateSettings(settings)


        case LoadPellets =>
            engineCyclesManager ! LoadPellets

    }

}