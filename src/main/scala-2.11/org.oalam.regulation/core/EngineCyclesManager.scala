package org.oalam.regulation.core

import akka.actor.{Actor, Cancellable}
import akka.event.Logging
import org.oalam.regulation._
import BoilerEngine._


import scala.concurrent.duration._




/**
  * un cycle de motorisation est décomposé en 3 évènements :
  * 1. Les moteurs sont ON
  * 2. Le moteur tremie est OFF au bout de 3"
  * 3. Le moteur bruleur est OFF au bout de 8"
  *
  * ce cycle dure X secondes en fonction de la puissance
  */
class EngineCyclesManager extends Actor {
    import context._

    val log = Logging(context.system, this)

    val lastDate = System.currentTimeMillis()
    var stopTremieScheduler: Cancellable = null
    var stopBruleurScheduler: Cancellable = null
    var startEnginesScheduler: Cancellable = null
    var startSlowMotionScheduler: Cancellable = null

    val reportHandler = context.actorSelection("../reportHandler")
    val engineDriver = context.actorSelection("../engineDriver")


    def cleanupSchedulers() = {
        if (stopTremieScheduler != null) stopTremieScheduler.cancel()
        if (stopBruleurScheduler != null) stopBruleurScheduler.cancel()
        if (startEnginesScheduler != null) startEnginesScheduler.cancel()
        if(startSlowMotionScheduler != null) startSlowMotionScheduler.cancel()

        stopTremieScheduler = null
        stopBruleurScheduler = null
        startEnginesScheduler = null
        startSlowMotionScheduler = null
        engineDriver ! StopEngine(BoilerEngine.Bruleur)
        engineDriver ! StopEngine(BoilerEngine.Tremie)
        engineDriver ! StopEngine(BoilerEngine.Ventilo)
    }

    override def receive = {


        /**
          * démarre un cycle de ralenti
          */
        case StartSlowMotionCycle(restDuration: FiniteDuration, initialIdleDelay: FiniteDuration, delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration) =>
            reportHandler ! StartSlowMotionCycle(restDuration, initialIdleDelay, delayOn, delayOff, additionnalDelay)
            cleanupSchedulers()

            // planifie le démarrage d'un cycle nouveau cycle de ralenti
            startEnginesScheduler =
                context.system.scheduler.scheduleOnce(
                    restDuration,
                    self,
                    StartBurningCycle(delayOn, delayOff, additionnalDelay, -1))

            // TODO add here a number of max cycles


        /**
          * démarre un cycle de combustion
          */
        case StartBurningCycle(delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration, remainingCycles:Int) =>
            reportHandler ! StartBurningCycle(delayOn, delayOff, additionnalDelay, remainingCycles)
            cleanupSchedulers()
            val elapsedTime = (System.currentTimeMillis() - lastDate) / 1000

            // démarrage des 2 vis sans fin + ventilo
            engineDriver ! StartEngine(BoilerEngine.Bruleur)
            engineDriver ! StartEngine(BoilerEngine.Tremie)
            engineDriver ! StartEngine(BoilerEngine.Ventilo)

            // planifie l'arret de la trémie
            stopTremieScheduler =
                context.system.scheduler.scheduleOnce(
                    delayOn,
                    self,
                    StopCycleTremie(delayOn, delayOff, additionnalDelay, remainingCycles -1))


        /**
          * interrompt le cycle de combustion en cours
          */
        case StopBurningCycle =>
            log.info("stopping engine cycle ")
            cleanupSchedulers()


        /**
          * interrompt le cycle de la trémie
          */
        case StopCycleTremie(delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration, remainingCycles:Int) =>
            val elapsedTime = (System.currentTimeMillis() - lastDate) / 1000
            log.debug(s"stopping tremie $elapsedTime")
            engineDriver ! StopEngine(BoilerEngine.Tremie)

            // planifie l'arret du bruleur apres un delai de post fonctionnement
            stopBruleurScheduler =
                context.system.scheduler.scheduleOnce(
                    additionnalDelay,
                    self,
                    StopCycleBruleur)

            // planifie le démarrage d'un nouveau cycle
            if(remainingCycles !=0){
                startEnginesScheduler =
                    context.system.scheduler.scheduleOnce(
                        delayOff,
                        self,
                        StartBurningCycle(delayOn, delayOff, additionnalDelay, remainingCycles))
            }


        /**
          * interrompt le cycle du bruleur
          */
        case StopCycleBruleur =>
            val elapsedTime = (System.currentTimeMillis() - lastDate) / 1000
            engineDriver ! StopEngine(BoilerEngine.Bruleur)


    }
}



