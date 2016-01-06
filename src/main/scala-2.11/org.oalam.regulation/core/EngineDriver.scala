package org.oalam.regulation.core

import akka.actor.Actor
import com.pi4j.io.gpio.event.{GpioPinDigitalStateChangeEvent, GpioPinListenerDigital}
import com.pi4j.io.gpio.{GpioFactory, PinPullResistance, PinState, RaspiPin}


object BoilerEngine extends Enumeration {
    type BoilerEngine = Value
    val Tremie, Bruleur, Ventilo, Laddomat, Vanne4V = Value
}


import org.oalam.regulation.core.BoilerEngine._
trait EngineDriver {
    def checkEngineHealth(engine: BoilerEngine): Boolean

    def startEngine(engine: BoilerEngine)

    def stopEngine(engine: BoilerEngine)

    def shutdwown()
}

class MockEngineDriver extends EngineDriver {
    def checkEngineHealth(engine: BoilerEngine): Boolean = {
        true
    }

    def startEngine(engine: BoilerEngine) = {
    }

    def stopEngine(engine: BoilerEngine) = {
    }

    def shutdwown() = {
    }
}




/**
  * Moteur piloté via PIN GPIO
  */
class GPIOEngineDriver extends Actor with EngineDriver {

    /**
      * akka setup
      */
    val boiler = context.actorSelection("../boiler")
    val reportHandler = context.actorSelection("../reportHandler")

    override def receive = {
        case StartEngine(engine: BoilerEngine) =>
            startEngine(engine)

        case StopEngine(engine: BoilerEngine) =>
            stopEngine(engine)

        case BoilerShutdown =>
            shutdwown()
    }

    /**
      * create gpio controller
      */
    private val gpio = GpioFactory.getInstance()

    // provision gpio output pins and turn on
    private val bruleur = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_00, "bruleur", PinState.HIGH)
    private val tremie = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_02, "tremie", PinState.HIGH)
    private val ventilo = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_03, "ventilo", PinState.HIGH)
    private val vanne4V = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_01, "vanne4V", PinState.HIGH)
    private val laddomat = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_04, "laddomat", PinState.HIGH)
    private val tremieSecu = gpio.provisionDigitalInputPin(RaspiPin.GPIO_05, "tremieSecu", PinPullResistance.PULL_UP)
    private val bruleurSecu = gpio.provisionDigitalInputPin(RaspiPin.GPIO_06, "bruleurSecu", PinPullResistance.PULL_UP)

    // set shutdown state for these pin
    bruleur.setShutdownOptions(true, PinState.LOW)
    tremie.setShutdownOptions(true, PinState.LOW)
    ventilo.setShutdownOptions(true, PinState.LOW)
    vanne4V.setShutdownOptions(true, PinState.LOW)
    laddomat.setShutdownOptions(true, PinState.LOW)

    // create and register gpio pin listener
    tremieSecu.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(event:GpioPinDigitalStateChangeEvent) = {
            // display pin state on console
            System.out.println(" --> GPIO PIN STATE CHANGE: " + event.getPin() + " = " + event.getState());
        }
    })

    // create and register gpio pin listener
    bruleurSecu.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(event:GpioPinDigitalStateChangeEvent) = {
            // display pin state on console
            System.out.println(" --> GPIO PIN STATE CHANGE: " + event.getPin() + " = " + event.getState());
        }
    })

    /**
      * vérifie l'état du moteur
      * @param engine
      * @return
      */
    def checkEngineHealth(engine: BoilerEngine): Boolean = {
        engine match {
            case BoilerEngine.Bruleur => bruleurSecu.isHigh
            case BoilerEngine.Tremie => tremieSecu.isHigh
            case _ => throw new IllegalArgumentException(s"unknown engineName:$engine")
        }
    }

    /**
      * démarre un moteur
      * @param engine
      */
    def startEngine(engine: BoilerEngine) = {
        engine match {
            case BoilerEngine.Bruleur => bruleur.low()
            case BoilerEngine.Tremie => tremie.low()
            case BoilerEngine.Ventilo => ventilo.low()
            case BoilerEngine.Vanne4V => vanne4V.low()
            case BoilerEngine.Laddomat => laddomat.low()
            case _ => throw new IllegalArgumentException(s"unknown engineName:$engine")
        }
    }

    /**
      * stoppe un moteur
      * @param engine
      */
    def stopEngine(engine: BoilerEngine) = {
        engine match {
            case BoilerEngine.Bruleur => bruleur.high()
            case BoilerEngine.Tremie => tremie.high()
            case BoilerEngine.Ventilo => ventilo.high()
            case BoilerEngine.Vanne4V => vanne4V.high()
            case BoilerEngine.Laddomat => laddomat.high()
            case _ => throw new IllegalArgumentException(s"unknown engineName:$engine")
        }
    }

    /**
      * doit être appelé pour clore les ressources gpio proprement
      */
    def shutdwown() = {
        gpio.shutdown()
    }
}