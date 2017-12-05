package org.oalam.regulation.core

import scala.io.Source

/**
  * Sonde de temperature GPIO 1-Wire
  * Ce capteur stocke ses informations dans un fichier système
  */
class TemperatureSensor(rootPath: String = "/sys/bus/w1/devices") {

    /**
      * retourne la valeur en °C du capteur
      *
      * @return
      */
    def measure(sensor: String): Option[Float] = {

        val devicePath = sensor match {
            case "temperatureEauSortie" => "28-021581cc38ff"
            case _ => sensor
        }

        try {
            // lecture des lignes du fichier
            val lines = Source.fromFile(s"$rootPath/$devicePath/w1_slave").getLines().toList

            // extraction de la valeur
            if (lines(0).contains("YES")) {
                val tempIndex = lines(1).indexOf("t=")
                if (tempIndex != 1) {
                    Some(lines(1).substring(tempIndex + 2).toFloat / 1000.0f)
                }
                else None
            }
            else None
        } catch {
            case ex: Exception => None
        }

    }
}


