package org.oalam.regulation

import org.oalam.regulation.core.TemperatureSensor
import org.scalatest.{Matchers, FlatSpec}


class TemperatureSensorTest extends FlatSpec with Matchers {

    "A TemperatureSensor" should "parse the file content" in {
        val sensor = new TemperatureSensor("src/test/resources")
        val mesure = sensor.measure("28-021581cc38ff")
        mesure.isDefined should be(true)
        mesure.get should be(23.125)
    }

    it should "get None if file not found" in {
        val sensor = new TemperatureSensor("src/test/resources")
        val mesure = sensor.measure("notfound")
        mesure.isDefined should be(false)
    }

}
