package org.oalam.regulation.app

import org.oalam.regulation.core.ReportHandler
import org.oalam.regulation.util.MailAgent
import scala.sys.process._
/**
  * Created by tom on 02/01/16.
  */
object Sandbox extends App{

    // This uses ! to get the exit code
    def sendSms(message: String) = {

        Seq("w3m", "-dump", s"https://smsapi.free-mobile.fr/sendmsg?user=10602099&pass=2Sv7JFuiwOskhO&msg=$message") !
    }

//sendSms("test")

    val mail = new MailAgent(to = "bailet.thomas@gmail.com", from = "pi-boiler@oalam.org", subject = "boiler too high", content = "plik plok")
    mail.sendMessage

}
