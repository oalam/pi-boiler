package org.oalam.regulation

import org.oalam.regulation.core.BoilerEngine._

import scala.concurrent.duration._

/**
  * Created by tom on 29/12/15.
  */
package object core {

    /**
      * Les paramètres constants
      */
    val BURNER_ENGINE_RUNNING_POST_DURATION = 5 seconds
    val HEATING_MODE_ENGINES_RUNNING_DURATION = 3 seconds
    val DEFAULT_CONSIGNE_TEMPERATURE = 75
    val DEFAULT_ENGINES_REST_DURATION = 20 seconds
    val DEFAULT_SLOWNDOWN_REST_DURATION = 60 minutes // previous 50
    val SLOWDOWN_MODE_FAN_RUNNING_DURATION = 30 seconds
    val SLOWDOWN_MODE_ENGINES_RUNNING_DURATION = 50 seconds // previous 90
    val PELLETS_LOADING_DURATION = 50 seconds


    /**
      * Temerature events
      */
    sealed trait BoilerEvent

    case class TemperatureCrossThresholdUp(threshold: Float, currentTemperature: Float) extends BoilerEvent

    case class TemperatureCrossThresholdDown(threshold: Float, currentTemperature: Float) extends BoilerEvent

    case class TemperatureCrossConsigneUp(tempConsigne: Float, currentTemperature: Float) extends BoilerEvent

    case class TemperatureCrossConsigneDown(tempConsigne: Float, currentTemperature: Float) extends BoilerEvent

    case class TemperatureReport(lastValue: Float, currentValue: Float) extends BoilerEvent

    case class TemperatureTooHigh(currentTemperature: Float) extends BoilerEvent

    case class TemperatureTooLow(currentTemperature: Float) extends BoilerEvent


    /**
      * Engine Actions
      */
    sealed trait EngineAction

    case class StartBurningCycle(delayOn: FiniteDuration,
                                 delayOff: FiniteDuration,
                                 additionnalDelay: FiniteDuration) extends EngineAction

    case class StopBurningCycle() extends EngineAction

    case class StopCycleTremie(delayOn: FiniteDuration,
                               delayOff: FiniteDuration,
                               additionnalDelay: FiniteDuration) extends EngineAction

    case class StopCycleBruleur() extends EngineAction

    case class StartSlowMotionCycle(restDuration: FiniteDuration)

    case class StartEngine(engine: BoilerEngine) extends EngineAction

    case class StopEngine(engine: BoilerEngine) extends EngineAction

    case class LoadPellets() extends EngineAction


    /**
      * Engine Events
      */
    sealed trait EngineEvent

    case class BourrageBruleur() extends EngineEvent

    case class BourrageTremie() extends EngineEvent

    /**
      * Boiler Actions
      */
    sealed trait BoilerAction

    case class BoilerStart() extends BoilerAction

    case class BoilerStop() extends BoilerAction

    case class BoilerShutdown() extends BoilerAction

    case class BoilerUpdateSettings(settings: BoilerSettings) extends BoilerAction

}
