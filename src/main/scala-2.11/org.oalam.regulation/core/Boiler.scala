package org.oalam.regulation.core

import akka.actor.Actor
import akka.event.Logging

import scala.concurrent.duration._


case class BoilerSettings(dureeFonctionnementVis: FiniteDuration = 3 seconds,
                          dureeArretVis: FiniteDuration = 20 seconds,
                          dureePostFonctionnementBruleur: FiniteDuration = 5 seconds,
                          temperatureConsigne: Float = 75.0f,
                          postCirculationCirculateur: FiniteDuration = 4 minutes,
                          postCirculationVentilateur: FiniteDuration = 50 seconds,
                          tempsFonctionnementRalenti: FiniteDuration = 2 minutes,
                          dureeModeRalenti: FiniteDuration = 2 minutes,
                          dureeRepos: FiniteDuration = 5 minutes)


/**
  * La chaudière à granulés
  */
class Boiler(var settings: BoilerSettings) extends Actor {

    val log = Logging(context.system, this)
    var lastStateChangeDate = System.currentTimeMillis()
    val engineCyclesManager = context.actorSelection("../engineCyclesManager")
    val reportHandler = context.actorSelection("../reportHandler")
    val engineDriver = context.actorSelection("../engineDriver")


    override def preStart = {
        log.info("starting : " + context.self.path)
    }

    override def receive = {

        case TemperatureCrossThresholdUp(threshold: Float, currentTemperature: Float) =>
            log.debug(s"got TemperatureCrossThresholdUp(threshold: $threshold, currentTemperature: $currentTemperature )")

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
              * démarrage laddomat si la température dépasse 60°
              */
            if (threshold == 90) {
                reportHandler ! TemperatureTooHigh(currentTemperature)
                engineCyclesManager ! StopBurningCycle
                engineDriver ! StartEngine(BoilerEngine.Laddomat)
                engineDriver ! StartEngine(BoilerEngine.Vanne4V)
            }


        case TemperatureCrossThresholdDown(threshold: Float, currentTemperature: Float) =>
            log.debug(s"got TemperatureCrossThresholdDown(threshold: $threshold, currentTemperature: $currentTemperature )")

            /**
              * arret circulateur si la température repasse en-dessous de 40°
              */
            if (threshold == 40)
                engineDriver ! StopEngine(BoilerEngine.Vanne4V)

            /**
              * seuil de temperature mini de fonctionnement
              * si au-dela de 30' de fonctionnement la temperature descend en dessous de 30 degre
              * la chaudiere se verouille
              */
            if (threshold == 30) {
                log.warning(s"currentTemperature: $currentTemperature < 30°C => verouillage chaudiere")
                reportHandler ! TemperatureTooLow(currentTemperature)
                engineCyclesManager ! StopBurningCycle
                engineDriver ! StopEngine(BoilerEngine.Laddomat)
                engineDriver ! StopEngine(BoilerEngine.Vanne4V)
            }


        case TemperatureCrossConsigneUp(tempConsigne: Float, currentTemperature: Float) =>
            if (currentTemperature < 90) {
                engineCyclesManager ! StartSlowMotionCycle(
                    settings.dureeRepos,
                    settings.dureeModeRalenti,
                    settings.dureeFonctionnementVis,
                    settings.dureeModeRalenti,
                    settings.dureePostFonctionnementBruleur)
            }


        case TemperatureCrossConsigneDown(tempConsigne: Float, currentTemperature: Float) =>
            if (currentTemperature < 90)
                engineCyclesManager ! StartBurningCycle(settings.dureeFonctionnementVis, settings.dureeArretVis, settings.dureePostFonctionnementBruleur)


        case BoilerStart =>
            engineCyclesManager ! StartBurningCycle(settings.dureeFonctionnementVis, settings.dureeArretVis, settings.dureePostFonctionnementBruleur)

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
            reportHandler ! BoilerUpdateSettings(settings)
            self ! BoilerStop
            Thread.sleep(500)
            self ! BoilerStart

    }

}