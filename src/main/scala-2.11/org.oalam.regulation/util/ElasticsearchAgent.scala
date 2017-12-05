package org.oalam.regulation.util

import java.net.InetAddress
import java.util.Date

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.common.xcontent.XContentFactory._
import org.oalam.regulation.core.BoilerSettings
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/**
  * Created by tom on 06/01/16.
  */
object ElasticsearchAgent {

    val log = LoggerFactory.getLogger(this.getClass.getName)


    val settings = Settings.settingsBuilder()
        .put("client.transport.ping_timeout", "15s")
        .put("client.transport.sniff", false)
        .put("client.transport.nodes_sampler_interval", "15s")
        .put("cluster.name", "boiler")
        .build()


    val client = TransportClient.builder().settings(settings).build()
        .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("192.168.1.67"), 9300))

    def shutdown = client.close()

    def dumpTemperatureReport(currentValue: Float) = {
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
        }
    }


    def dumpStartSlowMotionCycle(slowMotionCycleDuration: FiniteDuration) = {
        try {
            client.prepareIndex("boiler", "event")
                .setSource(jsonBuilder()
                    .startObject()
                    .field("type", "StartSlowMotionCycle")
                    .field("date", new Date())
                    .field("slowMotionCycleDuration", slowMotionCycleDuration.toSeconds)
                    .endObject()
                )
                .get()
        } catch {
            case e: Exception => log.error("unable to publish event to Elasticsearch")
        }
    }


    def dumpStartBurningCycle(delayOn: FiniteDuration,
                              delayOff: FiniteDuration,
                              additionnalDelay: FiniteDuration) = {
        try {
            client.prepareIndex("boiler", "event")
                .setSource(jsonBuilder()
                    .startObject()
                    .field("type", "StartBurningCycle")
                    .field("date", new Date())
                    .field("delayOn", delayOn.toSeconds)
                    .field("delayOff", delayOff.toSeconds)
                    .field("additionnalDelay", additionnalDelay.toSeconds)
                    .endObject()
                )
                .get()
        } catch {
            case e: Exception => log.error("unable to publish event to Elasticsearch")
        }
    }

    def dumpTemperatureEvent(eventType: String, tempConsigne: Float = 0.0f, currentTemperature: Float) = {
        try {
            client.prepareIndex("boiler", "event")
                .setSource(jsonBuilder()
                    .startObject()
                    .field("type", eventType)
                    .field("date", new Date())
                    .field("tempConsigne", tempConsigne)
                    .field("currentTemperature", currentTemperature)
                    .endObject()
                )
                .get()
        } catch {
            case e: Exception => log.error("unable to publish event to Elasticsearch")
        }

    }

    def dumpBoilerUpdateSettings(settings: BoilerSettings) = {
        try {
            client.prepareIndex("boiler", "event")
                .setSource(jsonBuilder()
                    .startObject()
                    .field("type", "BoilerUpdateSettings")
                    .field("date", new Date())
                    .field("dureeArretVis", settings.delayOff.toString())
                    .field("temperatureConsigne", settings.temperatureConsigne.toString())
                    .field("dureeRepos", settings.restDuration.toString())
                    .endObject()
                )
                .get()
        } catch {
            case e: Exception => log.error("unable to publish event to Elasticsearch")
        }
    }

    def dumpEngineEvent(eventType: String) = {
        try {
            client.prepareIndex("boiler", "event")
                .setSource(jsonBuilder()
                    .startObject()
                    .field("type", eventType)
                    .field("date", new Date())
                    .endObject()
                )
                .get()
        } catch {
            case e: Exception => log.error("unable to publish event to Elasticsearch")
        }

    }
}
