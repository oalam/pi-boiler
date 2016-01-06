package org.oalam.regulation.app

import java.io.{File, FileWriter, PrintWriter}

import akka.actor.{ActorSystem, Props}
import org.oalam.regulation.core._

import scala.concurrent.duration._

object TemperatureUpdater {
    def using[A <: {def close() : Unit}, B](param: A)(f: A => B): B =
        try {
            f(param)
        } finally {
            param.close()
        }

    def appendToFile(fileName: String, textData: String) =
        using(new FileWriter(fileName, true)) {
            fileWriter => using(new PrintWriter(fileWriter)) {
                printWriter => printWriter.println(textData)
            }
        }

    private def deleteFile(path: String) = {
        val fileTemp = new File(path)
        if (fileTemp.exists) {
            fileTemp.delete()
        }
    }

    def update(fileName: String, temp: Float) = {
        deleteFile(fileName)
        appendToFile(fileName, "72 01 4b 46 7f ff 0e 10 57 : crc=57 YES")
        appendToFile(fileName, s"72 01 4b 46 7f ff 0e 10 57 t=${temp * 1000.0f}")
    }
}

/**
  * Created by tom on 28/12/15.
  */
object Main extends App {
    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("pi-boiler")

    val reportHandler = system.actorOf(Props(new ReportHandler()), "reportHandler")

    // setup engine cycles
    val engineManager = system.actorOf(Props(new EngineCyclesManager()), "engineManager")

    // setup temperature event handler
    val sensor = new TemperatureSensor(rootPath = "src/main/resources")
    val temperatureEventHandler = system.actorOf(Props(new TemperatureEventHandler(sensor, 70.0f)), "temperatureEventHandler")

    // setup boiler
    val settings = new BoilerSettings(
        dureeFonctionnementVis = 1000 milliseconds,
        dureeArretVis = 5000 milliseconds,
        dureePostFonctionnementBruleur = 2000 milliseconds,
        dureeModeRalenti = 10 seconds,
        temperatureConsigne = 70)
    val boiler = system.actorOf(Props(new Boiler(settings)), "boiler")
    boiler ! BoilerStart

    /*  val driverEngine = new GPIOEngineDriver()

     driverEngine.startEngine("bruleur")
     Thread.sleep(2000)
     driverEngine.stopEngine("bruleur")

     driverEngine.startEngine("tremie")
     Thread.sleep(2000)
     driverEngine.stopEngine("tremie")

     driverEngine.shutdwown()*/
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 23.125f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 42.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 65.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 75.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 66.999f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 72.000f)
    Thread.sleep(30000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 73.999f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 72.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 66.999f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 91.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 45.000f)
    Thread.sleep(2000)
    TemperatureUpdater.update("src/main/resources/28-021581cc38ff/w1_slave", 23.125f)
    Thread.sleep(2000)

    system.terminate()

}

