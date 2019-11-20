package org.oalam.regulation.util

import scala.sys.process._

object SmsAgent {



    // throws MessagingException
    def sendMessage(message: String) {
        try {
               
        Seq("w3m", "-dump", s"https://smsapi.free-mobile.fr/sendmsg?user=10602099&pass=2Sv7JFuiwOskhO&msg=$message") !

        } catch {
            case e: Exception => throw new RuntimeException(e)
        }
    }

   
}