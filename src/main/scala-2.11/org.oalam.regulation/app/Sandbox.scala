package org.oalam.regulation.app

import org.oalam.regulation.core.ReportHandler
import org.oalam.regulation.util.{SmsAgent, MailAgent}
import scala.sys.process._
/**
  * Created by tom on 02/01/16.
  */
object Sandbox extends App{

    SmsAgent.sendMessage("test")

   MailAgent.sendMessage(to = "bailet.thomas@gmail.com", from = "pi-boiler@oalam.org", subject = "boiler too high", content = "plik plok")

}
