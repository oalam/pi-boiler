package org.oalam.regulation.core

import akka.actor.Actor
import akka.event.Logging
import org.oalam.regulation.util._

import scala.concurrent.duration._


/**
  * Ce composant récupère tous les évènements système afin de les consigner dans elasticsearch
  *
  */
class ReportHandler extends Actor {


    val log = Logging(context.system, this)

    import context._

    val boiler = context.actorSelection("../boiler")

    var lastDate = System.currentTimeMillis()
    val cancellableLoop = context.system.scheduler.schedule(
        0 milliseconds,
        2000 milliseconds,
        self,
        "tick")

    var lastTemperature = 0.0f
    var cyclesCount = 0L
    var slowCyclesCount = 0L

    override def preStart = {
        println("my path is: " + context.self.path)
    }


    override def receive = {

        case TemperatureReport(lastValue: Float, currentValue: Float) =>
            if (lastValue != currentValue) {
                log.info(s"TemperatureReport(lastValue: $lastValue, currentValue: $currentValue)")
                ElasticsearchAgent.dumpTemperatureReport(currentValue)
                lastTemperature = currentValue
            }


        case StartSlowMotionCycle(slowMotionCycleDuration: FiniteDuration, initialIdleDelay: FiniteDuration, delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration) =>
            ElasticsearchAgent.dumpStartSlowMotionCycle(
                slowMotionCycleDuration,
                initialIdleDelay,
                delayOn,
                delayOff,
                additionnalDelay)
            slowCyclesCount += 1
            log.info(s"StartSlowMotionCycle(slowMotionCycleDuration, :$slowMotionCycleDuration, " +
                s"initialIdleDelay: $initialIdleDelay, " +
                s"delayOn: $delayOn, " +
                s"delayOff: $delayOff, " +
                s"additionnalDelay: $additionnalDelay) " +
                s"total : $slowCyclesCount")


        case StartBurningCycle(delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration, remainingCycles: Int) =>
            ElasticsearchAgent.dumpStartBurningCycle(delayOn, delayOff, additionnalDelay, remainingCycles)
            cyclesCount += 1
            log.info(s"StartBurningCycle( delayOn: $delayOn, " +
                s"delayOff: $delayOff, " +
                s"additionnalDelay: $additionnalDelay, " +
                s"remainingCycles : $remainingCycles) " +
                s"total : $cyclesCount")


        case TemperatureCrossConsigneUp(tempConsigne: Float, currentTemperature: Float) =>
            ElasticsearchAgent.dumpTemperatureEvent("TemperatureCrossConsigneUp", tempConsigne, currentTemperature)
            log.info(s"TemperatureCrossConsigneUp(tempConsigne: $tempConsigne, currentTemperature: $currentTemperature )")


        case TemperatureCrossConsigneDown(tempConsigne: Float, currentTemperature: Float) =>
            ElasticsearchAgent.dumpTemperatureEvent("TemperatureCrossConsigneDown", tempConsigne, currentTemperature)
            log.info(s"TemperatureCrossConsigneDown(tempConsigne: $tempConsigne, currentTemperature: $currentTemperature )")


        case TemperatureTooHigh(currentTemperature: Float) =>
            ElasticsearchAgent.dumpTemperatureEvent("TemperatureTooHigh", currentTemperature = currentTemperature)
            log.warning(s"TemperatureTooHigh : $currentTemperature => arret des vis sans fin et allumage circulateurs")
            SmsAgent.sendMessage(s"TemperatureTooHigh : $currentTemperature")
            MailAgent.sendMessage(
                to = "bailet.thomas@gmail.com",
                from = "pi-boiler@oalam.org",
                subject = "boiler too high",
                content = s"TemperatureTooHigh : $currentTemperature => arret des vis sans fin et allumage circulateurs")


        case TemperatureTooLow(currentTemperature: Float) =>
            ElasticsearchAgent.dumpTemperatureEvent("TemperatureTooLow", currentTemperature = currentTemperature)
            log.warning(s"TemperatureTooLow : $currentTemperature => arret des vis sans fin et allumage circulateurs")
            // send an sms
            SmsAgent.sendMessage(s"TemperatureTooLow : $currentTemperature")
            MailAgent.sendMessage(
                to = "bailet.thomas@gmail.com",
                from = "pi-boiler@oalam.org",
                subject = "boiler too low",
                content = s"TemperatureTooLow : $currentTemperature => arret des vis sans fin et allumage circulateurs")


        case BoilerUpdateSettings(settings: BoilerSettings) =>
            ElasticsearchAgent.dumpBoilerUpdateSettings(settings)
            log.info(s"BoilerUpdateSettings( settings :$settings)")


        case BoilerShutdown =>
            ElasticsearchAgent.shutdown
    }

}
