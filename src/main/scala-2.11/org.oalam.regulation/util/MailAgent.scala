package org.oalam.regulation.util

import java.util.{Date, Properties}
import javax.mail._
import javax.mail.internet._

import org.slf4j.LoggerFactory

object MailAgent {

    val log = LoggerFactory.getLogger(this.getClass.getName)



    // throws MessagingException
    def sendMessage(to: String,
                    cc: String = null,
                    bcc: String = null,
                    from: String,
                    subject: String,
                    content: String) = {
        try {
            val message = createMessage
            message.setFrom(new InternetAddress(from))
            setToCcBccRecipients(message, to, cc, bcc)

            message.setSentDate(new Date())
            message.setSubject(subject)
            message.setText(content)

            Transport.send(message)
        } catch {
            case e: MessagingException => log.error("unable to send email")
        }
    }

    private def createMessage: Message = {
        val props = new Properties()
        props.put("mail.smtp.host", "smtp.gmail.com")
        props.put("mail.smtp.socketFactory.port", "465")
        props.put("mail.smtp.socketFactory.class",
            "javax.net.ssl.SSLSocketFactory")
        props.put("mail.smtp.auth", "true")
        props.put("mail.smtp.port", "465")
        val session = Session.getDefaultInstance(props,
            new javax.mail.Authenticator() {
               override def getPasswordAuthentication(): PasswordAuthentication = {
                    new PasswordAuthentication("***", "***")
                }
            })


        new MimeMessage(session)
    }

    // throws AddressException, MessagingException
    private def setToCcBccRecipients(message:Message,
                                     to: String,
                                     cc: String = null,
                                     bcc: String = null) =  {
        setMessageRecipients(message, to, Message.RecipientType.TO)
        if (cc != null) {
            setMessageRecipients(message, cc, Message.RecipientType.CC)
        }
        if (bcc != null) {
            setMessageRecipients(message, bcc, Message.RecipientType.BCC)
        }
    }

    // throws AddressException, MessagingException
    private def setMessageRecipients(message:Message, recipient: String, recipientType: Message.RecipientType) {
        // had to do the asInstanceOf[...] call here to make scala happy
        val addressArray = buildInternetAddressArray(recipient).asInstanceOf[Array[Address]]
        if ((addressArray != null) && (addressArray.length > 0)) {
            message.setRecipients(recipientType, addressArray)
        }
    }

    // throws AddressException
    private def buildInternetAddressArray(address: String): Array[InternetAddress] = {
        // could test for a null or blank String but I'm letting parse just throw an exception
        return InternetAddress.parse(address)
    }

}
