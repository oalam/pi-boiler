package org.oalam.regulation.core

import java.net.InetAddress
import java.util.Date

import akka.actor.Actor
import akka.event.Logging
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.common.xcontent.XContentFactory._
import org.oalam.regulation.util._

import scala.concurrent.duration._



/**
  * Ce composant récupère tous les évènements système afin de les consigner dans elasticsearch
  *
  */
class ReportHandler extends Actor {





    val settings = Settings.settingsBuilder()
        .put("client.transport.ping_timeout", "15s")
        .put("client.transport.sniff", false)
        .put("client.transport.nodes_sampler_interval", "15s")
        .put("cluster.name", "boiler")
        .build()


    val client = TransportClient.builder().settings(settings).build()
        .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("192.168.1.67"), 9300))


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
                try {
                    client.prepareIndex("boiler", "event")
                        .setSource(jsonBuilder()
                            .startObject()
                            .field("type", "TemperatureReport")
                            .field("date", new Date())
                            .field("value", currentValue)
                            .endObject()
                        )
                        .get()
                } catch {
                    case e: Exception => log.error("unable to publish event to Elasticsearch")
                } finally {
                    log.info(s"TemperatureReport(lastValue: $lastValue, currentValue: $currentValue)")
                    lastTemperature = currentValue
                }
            }


        case StartSlowMotionCycle(slowMotionCycleDuration: FiniteDuration, initialIdleDelay: FiniteDuration, delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "StartSlowMotionCycle")
                        .field("date", new Date())
                        .field("slowMotionCycleDuration", slowMotionCycleDuration.toSeconds)
                        .field("initialIdleDelay", initialIdleDelay.toSeconds)
                        .field("delayOn", delayOn.toSeconds)
                        .field("delayOff", delayOff.toSeconds)
                        .field("additionnalDelay", additionnalDelay.toSeconds)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                slowCyclesCount += 1
                log.info(s"StartSlowMotionCycle(slowMotionCycleDuration, :$slowMotionCycleDuration, initialIdleDelay: $initialIdleDelay, delayOn: $delayOn, delayOff: $delayOff, additionnalDelay: $additionnalDelay) total : $slowCyclesCount")
            }


        case StartBurningCycle(delayOn: FiniteDuration, delayOff: FiniteDuration, additionnalDelay: FiniteDuration, remainingCycles: Int) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "StartBurningCycle")
                        .field("date", new Date())
                        .field("remainingCycles", remainingCycles)
                        .field("delayOn", delayOn.toSeconds)
                        .field("delayOff", delayOff.toSeconds)
                        .field("additionnalDelay", additionnalDelay.toSeconds)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                cyclesCount += 1
                log.info(s"StartBurningCycle( delayOn: $delayOn, delayOff: $delayOff, additionnalDelay: $additionnalDelay, remainingCycles : $remainingCycles) total : $cyclesCount")
            }


        case TemperatureCrossConsigneUp(tempConsigne: Float, currentTemperature: Float) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "TemperatureCrossConsigneUp")
                        .field("date", new Date())
                        .field("tempConsigne", tempConsigne)
                        .field("currentTemperature", currentTemperature)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                log.info(s"TemperatureCrossConsigneUp(tempConsigne: $tempConsigne, currentTemperature: $currentTemperature )")
            }


        case TemperatureCrossConsigneDown(tempConsigne: Float, currentTemperature: Float) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "TemperatureCrossConsigneDown")
                        .field("date", new Date())
                        .field("tempConsigne", tempConsigne)
                        .field("currentTemperature", currentTemperature)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                log.info(s"TemperatureCrossConsigneDown(tempConsigne: $tempConsigne, currentTemperature: $currentTemperature )")
            }

        case TemperatureTooHigh(currentTemperature: Float) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "TemperatureTooHigh")
                        .field("date", new Date())
                        .field("currentTemperature", currentTemperature)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                log.warning(s"TemperatureTooHigh : $currentTemperature => arret des vis sans fin et allumage circulateurs")
                // send an sms
                SmsAgent.sendMessage(s"TemperatureTooHigh : $currentTemperature")
            }

            try{
                val mail = new MailAgent(to = "bailet.thomas@gmail.com", from = "pi-boiler@oalam.org", subject = "boiler too high", content = s"TemperatureTooHigh : $currentTemperature => arret des vis sans fin et allumage circulateurs")
                mail.sendMessage
            }catch {
                case e: Exception => log.error("unable to send email")
            }

        case TemperatureTooLow(currentTemperature: Float) =>
            try {
                client.prepareIndex("boiler", "event")
                    .setSource(jsonBuilder()
                        .startObject()
                        .field("type", "TemperatureTooLow")
                        .field("date", new Date())
                        .field("currentTemperature", currentTemperature)
                        .endObject()
                    )
                    .get()
            } catch {
                case e: Exception => log.error("unable to publish event to Elasticsearch")
            } finally {
                log.warning(s"TemperatureTooLow : $currentTemperature => arret des vis sans fin et allumage circulateurs")
                // send an sms
                sendSms(s"TemperatureTooLow : $currentTemperature")

            }
            try{
                val mail = new MailAgent(to = "bailet.thomas@gmail.com", from = "pi-boiler@oalam.org", subject = "boiler too low", content = s"TemperatureTooLow : $currentTemperature => arret des vis sans fin et allumage circulateurs")
                mail.sendMessage
            }catch {
                case e: Exception => log.error("unable to send email")
            }


                case BoilerUpdateSettings(settings: BoilerSettings) =>
                try {
                    client.prepareIndex("boiler", "event")
                        .setSource(jsonBuilder()
                            .startObject()
                            .field("type", "BoilerUpdateSettings")
                            .field("date", new Date())
                            .field("dureeFonctionnementVis", settings.dureeFonctionnementVis.toString())
                            .field("dureeArretVis", settings.dureeArretVis.toString())
                            .field("dureePostFonctionnementBruleur", settings.dureePostFonctionnementBruleur.toString())
                            .field("temperatureConsigne", settings.temperatureConsigne.toString())
                            .field("postCirculationCirculateur", settings.postCirculationCirculateur.toString())
                            .field("postCirculationVentilateur", settings.postCirculationVentilateur.toString())
                            .field("tempsFonctionnementRalenti", settings.tempsFonctionnementRalenti.toString())
                            .field("dureeModeRalenti", settings.dureeModeRalenti.toString())
                            .field("dureeRepos", settings.dureeRepos.toString())
                            .endObject()
                        )
                        .get()
                } catch {
                    case e: Exception => log.error("unable to publish event to Elasticsearch")
                } finally {
                    log.info(s"BoilerUpdateSettings( settings :$settings)")
                }


        case BoilerShutdown =>
            // releas es
            client.close()
            }
    }
