package org.bzdev.ecdb.javamail;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;
import javax.activation.*;
import javax.mail.*;
import javax.mail.internet.*;
import org.bzdev.ecdb.ECDB;
import org.bzdev.ecdb.SMTPAgent;

public class JavamailSMTPAgent extends SMTPAgent {

    static final Charset UTF8 = Charset.forName("UTF-8");

    JavamailSMTPAgent() {
	super();
    }

    public void send (Properties properties, String toAddress,
		      Vector<byte[]> calendars)
	throws MessagingException, IllegalStateException,
	       UnsupportedEncodingException
    {
	Properties props = new Properties();

 	String userAuth1 = null;
	String pw1 = null;
	String userName = null;
	String userEmail = null;
	String replyto = null;
	String subject = "Calendar Appointments";
	String text = "The appointments are enclosed as attachments.";
	String textMimeType = "text/plain; charset=UTF-8";
	String altText = null;
	String altTextMimeType = null;
	for (String key: properties.stringPropertyNames()) {
	    if (key.equals("userAuth")) {
		userAuth1 = properties.getProperty(key);
	    } else if (key.equals("password")) {
		pw1 = properties.getProperty(key);
	    } else if (key.equals("userName")) {
		userName = properties.getProperty(key);
	    } else if (key.equals("user")) {
		userEmail = properties.getProperty(key);
	    } else if (key.equals("replyto")) {
		replyto = properties.getProperty(key);
	    } else if (key.equals("subject")) {
		subject = properties.getProperty(key);
	    } else if (key.equals("textMediaType")) {
		textMimeType = properties.getProperty(key);
	    } else if (key.equals("text")) {
		text = properties.getProperty(key);
	    } else if (key.equals("altTextMediaType")) {
		altTextMimeType = properties.getProperty(key);
	    } else if (key.equals("altText")) {
		altText = properties.getProperty(key);
	    } else {
		props.setProperty(key, properties.getProperty(key));
	    }
	}
	if (userAuth1 == null) userAuth1 = userEmail;
	final String userAuth = userAuth1;
	final String pw = pw1;
	if (replyto == null) replyto = userEmail;
	Authenticator auth = new Authenticator() {
		protected PasswordAuthentication getPasswordAuthentication() {
		    return new PasswordAuthentication(userAuth, pw);
		}
	    };
	Session session = Session.getInstance(props, auth);
	MimeMessage msg = new MimeMessage(session);
	msg.setRecipients(Message.RecipientType.TO,
			  InternetAddress.parse(toAddress, false));
	if (userName == null) {
	    msg.setFrom(new InternetAddress(userEmail));
	} else {
	    msg.setFrom(new InternetAddress(userEmail, userName));
	}
	msg.setReplyTo(InternetAddress.parse(replyto, false));
	msg.setSubject(subject);
	msg.setSentDate(new Date());
	// msg.addHeader("format", "flowed");
	msg.addHeader("Content-Transfer-Encoding", "8bit");
	Multipart multipart = new MimeMultipart();
	BodyPart mbp = new MimeBodyPart();
	mbp.setContent(text, textMimeType);
	if (altText != null && altTextMimeType != null) {
	    Multipart alts = new MimeMultipart("alternative");
	    alts.addBodyPart(mbp);
	    mbp = new MimeBodyPart();
	    mbp.setContent(altText, altTextMimeType);
	    alts.addBodyPart(mbp);
	    mbp = new MimeBodyPart();
	    mbp.setContent(alts, "multipart/alternative");
	    multipart.addBodyPart(mbp);
	} else {
	    multipart.addBodyPart(mbp);
	}
	if (calendars != null) {
	    int nn = calendars.size();
	    int digits = 0;
	    do {
		digits++;
		nn = nn/10;
	    } while (nn > 0);
	    String format = "event%0" + digits +"d.ics";
	    int i = 0;
	    for (byte[] calendar: calendars) {
		final int ind  = ++i;
		final byte[] content = calendar;
		final String name = String.format(format, ind);
		DataSource source = new DataSource() {
			byte[] bytes = content;
			public String getContentType() {
			    return "text/calendar; charset=UTF-8";
			}
			public InputStream getInputStream() {
			    return new ByteArrayInputStream(bytes);
			}
			public String getName() {return name;}
			public OutputStream  getOutputStream() {return null;}
		    };
		mbp = new MimeBodyPart();
		mbp.setDataHandler(new DataHandler(source));
		multipart.addBodyPart(mbp);
	    }
	}
	msg.setContent(multipart);
	// System.out.println("sending msg");
	Transport.send(msg);
    }
}
