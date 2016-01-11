package org.oalam.regulation

import org.oalam.regulation.core.BoilerEngine._

import scala.concurrent.duration.FiniteDuration

/**
  * Created by tom on 29/12/15.
  */
package object core {

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
