package org.oalam.regulation.core

import akka.actor.Actor
import com.pi4j.io.gpio.event.{GpioPinDigitalStateChangeEvent, GpioPinListenerDigital}
import com.pi4j.io.gpio.{GpioFactory, PinPullResistance, PinState, RaspiPin}


object BoilerEngine extends Enumeration {
    type BoilerEngine = Value
    val Tremie, Bruleur, Ventilo, Laddomat, Vanne4V = Value
}


import org.oalam.regulation.core.BoilerEngine._




/**
  * Moteur piloté via PIN GPIO
  */
class GPIOEngineDriver extends Actor  {

    /**
      * akka setup
      */
    val engineCyclesManager = context.actorSelection("../engineCyclesManager")
    val reportHandler = context.actorSelection("../reportHandler")

    override def receive = {

        case StartEngine(engine: BoilerEngine) =>
            engine match {
                case BoilerEngine.Bruleur => bruleur.low()
                case BoilerEngine.Tremie => tremie.low()
                case BoilerEngine.Ventilo => ventilo.low()
                case BoilerEngine.Vanne4V => vanne4V.low()
                case BoilerEngine.Laddomat => laddomat.low()
                case _ => throw new IllegalArgumentException(s"unknown engineName:$engine")
            }


        case StopEngine(engine: BoilerEngine) =>
            engine match {
                case BoilerEngine.Bruleur => bruleur.high()
                case BoilerEngine.Tremie => tremie.high()
                case BoilerEngine.Ventilo => ventilo.high()
                case BoilerEngine.Vanne4V => vanne4V.high()
                case BoilerEngine.Laddomat => laddomat.high()
                case _ => throw new IllegalArgumentException(s"unknown engineName:$engine")
            }


        case BoilerShutdown =>
            // doit être appelé pour clore les ressources gpio proprement
            gpio.shutdown()

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

    // tout le monde est éteint sauf la Vanne 4V
    bruleur.setShutdownOptions(true, PinState.LOW)
    tremie.setShutdownOptions(true, PinState.LOW)
    ventilo.setShutdownOptions(true, PinState.LOW)
    vanne4V.setShutdownOptions(true, PinState.LOW)
    laddomat.setShutdownOptions(true, PinState.LOW)

    // surveille le bourage de la trémie
    tremieSecu.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(event: GpioPinDigitalStateChangeEvent) = {
            if(tremieSecu.isHigh){
                reportHandler ! BourrageTremie
                engineCyclesManager ! BourrageTremie
            }
        }
    })

    // surveille le bourrage du brûleur
    bruleurSecu.addListener(new GpioPinListenerDigital() {
        override def handleGpioPinDigitalStateChangeEvent(event: GpioPinDigitalStateChangeEvent) = {
            if(bruleurSecu.isHigh) {
                reportHandler ! BourrageBruleur
                engineCyclesManager ! BourrageBruleur
            }
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


}
