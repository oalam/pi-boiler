package org.oalam.regulation.core

import akka.actor.{Actor, Cancellable}
import akka.event.Logging
import akka.util.Timeout

import scala.concurrent.Await
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

    var lastDate = 0L
    var stopTremieScheduler: Cancellable = null
    var stopBruleurScheduler: Cancellable = null
    var stopVentiloScheduler: Cancellable = null
    var mainCycleScheduler: Cancellable = null



    implicit val resolveTimeout = Timeout(5 seconds)
    val reportHandler = Await.result(context.actorSelection("../reportHandler").resolveOne(), resolveTimeout.duration)
    val engineDriver = Await.result(context.actorSelection("../engineDriver").resolveOne(), resolveTimeout.duration)
    val boiler = Await.result(context.actorSelection("../boiler").resolveOne(), resolveTimeout.duration)


    def cleanupSchedulers() = {
        if (stopTremieScheduler != null) stopTremieScheduler.cancel()
        if (stopBruleurScheduler != null) stopBruleurScheduler.cancel()
        if (stopVentiloScheduler != null) stopVentiloScheduler.cancel()
        if (mainCycleScheduler != null) mainCycleScheduler.cancel()

        stopTremieScheduler = null
        stopBruleurScheduler = null
        mainCycleScheduler = null
        stopVentiloScheduler = null
        engineDriver ! StopEngine(BoilerEngine.Bruleur)
        engineDriver ! StopEngine(BoilerEngine.Tremie)
        engineDriver ! StopEngine(BoilerEngine.Ventilo)

        lastDate == 0
    }

    override def receive = {


        /**
          * démarre un cycle de ralenti
          */
        case StartSlowMotionCycle(restDuration: FiniteDuration) =>
            if(lastDate == 0 ||  System.currentTimeMillis() - lastDate > restDuration.toMillis){
                reportHandler ! StartSlowMotionCycle(restDuration)
                cleanupSchedulers()

                // démarrage des 2 vis sans fin + ventilo
                engineDriver ! StartEngine(BoilerEngine.Bruleur)
                engineDriver ! StartEngine(BoilerEngine.Tremie)
                engineDriver ! StartEngine(BoilerEngine.Ventilo)

                // planifie l'arret des moteurs
                stopTremieScheduler = context.system.scheduler.scheduleOnce(
                    SLOWDOWN_MODE_ENGINES_RUNNING_DURATION,
                    engineDriver,
                    StopEngine(BoilerEngine.Tremie))

                stopBruleurScheduler = context.system.scheduler.scheduleOnce(
                    SLOWDOWN_MODE_ENGINES_RUNNING_DURATION + BURNER_ENGINE_RUNNING_POST_DURATION,
                    engineDriver,
                    StopEngine(BoilerEngine.Bruleur))

                stopVentiloScheduler = context.system.scheduler.scheduleOnce(
                    SLOWDOWN_MODE_FAN_RUNNING_DURATION,
                    engineDriver,
                    StopEngine(BoilerEngine.Ventilo))

                // planifie le démarrage d'un cycle nouveau cycle de ralenti
                mainCycleScheduler =
                    context.system.scheduler.scheduleOnce(
                        restDuration,
                        self,
                        StartSlowMotionCycle(restDuration))

                lastDate = System.currentTimeMillis()
            }else{
                log.debug(s"won't start StartSlowMotionCycle because lastDate=$lastDate or restDuration=$restDuration and " +
                    s"System.currentTimeMillis() - lastDate = ${System.currentTimeMillis() - lastDate}")
            }



        /**
          * démarre un cycle de combustion
          */
        case StartBurningCycle(delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration) =>
            reportHandler ! StartBurningCycle(delayOn, delayOff, additionnalDelay)
            cleanupSchedulers()

            // démarrage des 2 vis sans fin + ventilo
            engineDriver ! StartEngine(BoilerEngine.Bruleur)
            engineDriver ! StartEngine(BoilerEngine.Tremie)
            engineDriver ! StartEngine(BoilerEngine.Ventilo)

            // planifie l'arret de la trémie
            stopTremieScheduler =
                context.system.scheduler.scheduleOnce(
                    delayOn,
                    engineDriver,
                    StopEngine(BoilerEngine.Tremie))

            // planifie l'arret du bruleur apres un delai de post fonctionnement
            stopBruleurScheduler =
                context.system.scheduler.scheduleOnce(
                    delayOn + additionnalDelay,
                    engineDriver,
                    StopEngine(BoilerEngine.Bruleur))

            // planifie le démarrage d'un nouveau cycle

           // val settings = boiler.asInstanceOf[Boiler].settings
            mainCycleScheduler =
                context.system.scheduler.scheduleOnce(
                    delayOn + delayOff,
                    self,
                    StartBurningCycle(delayOn, delayOff, additionnalDelay))


        /**
          * rempli le bruleur de grains pour l'allumage
          */
        case LoadPellets =>
            reportHandler ! LoadPellets
            cleanupSchedulers()
            // démarrage des 2 vis sans fin + ventilo
            engineDriver ! StartEngine(BoilerEngine.Bruleur)
            engineDriver ! StartEngine(BoilerEngine.Tremie)
            engineDriver ! StopEngine(BoilerEngine.Ventilo)

            // planifie l'arret de la trémie
            stopTremieScheduler =
                context.system.scheduler.scheduleOnce(
                    PELLETS_LOADING_DURATION,
                    engineDriver,
                    StopEngine(BoilerEngine.Tremie))

            // planifie l'arret du bruleur apres un delai de post fonctionnement
            stopBruleurScheduler =
                context.system.scheduler.scheduleOnce(
                    PELLETS_LOADING_DURATION + BURNER_ENGINE_RUNNING_POST_DURATION,
                    engineDriver,
                    StopEngine(BoilerEngine.Bruleur))

        /**
          * interrompt le cycle de combustion en cours
          */
        case StopBurningCycle =>
            log.info("stopping engine cycle ")
            cleanupSchedulers()


        case BourrageTremie =>
            cleanupSchedulers()

        case BourrageBruleur =>
            cleanupSchedulers()
    }
}



