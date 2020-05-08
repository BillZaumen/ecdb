package org.bzdev.ecdb.dryrun;

import java.awt.Desktop;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;
import java.util.Properties;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import org.bzdev.ecdb.SMTPAgent;

import org.bzdev.ejws.*;
import org.bzdev.ejws.maps.*;
import org.bzdev.net.WebEncoder;

public class DryrunSMTPAgent extends SMTPAgent {

    static final Charset UTF8 = Charset.forName("UTF-8");
    
    int entryid = 0;
    Properties props = new Properties();
    StringBuilder sb = new StringBuilder();

    DryrunSMTPAgent() {
	super();
	sb.append("<!DOCTYPE HTML><HTML>");
	sb.append("<HEAD><TITLE>ECDB: recipient list</TITLE></HEAD>");
	sb.append("<BODY><UL>");
    }

    public void send (Properties properties, String toAddress,
		      Vector<byte[]> calendars)
	throws IllegalStateException, UnsupportedEncodingException
    {
	
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
	    if (key.equals("subject")) {
		subject = properties.getProperty(key);
	    } else if (key.equals("textMediaType")) {
		textMimeType = properties.getProperty(key);
	    } else if (key.equals("text")) {
		text = properties.getProperty(key);
	    } else if (key.equals("altTextMediaType")) {
		altTextMimeType = properties.getProperty(key);
	    } else if (key.equals("altText")) {
		altText = properties.getProperty(key);
	    }
	}
	entryid++;
	String suffix = null;
	if (textMimeType != null) {
	    if (textMimeType.toLowerCase().startsWith("text/plain")) {
		suffix = "txt";
	    } else if (textMimeType.toLowerCase().startsWith("text/html")) {
		suffix = "html";
	    } else {
		suffix = "dat";
	    }
	}
	String altSuffix = null;
	if (altTextMimeType != null) {
	    if (altTextMimeType.toLowerCase().startsWith("text/plain")) {
		altSuffix = "txt";
	    } else if (altTextMimeType.toLowerCase().startsWith("text/html")) {
		altSuffix = "html";
	    } else {
		altSuffix = "dat";
	    }
	}

	String msg = "msg" + entryid;
	sb.append(String.format("<LI><A href=\"../%s\"/>%s</A>",
				msg + "/index.html",
				WebEncoder.htmlEncode(toAddress)));
	StringBuilder msb = new StringBuilder();
	msb.append("<!DOCTYPE HTML><HTML><HEAD>");
	msb.append("</HEAD><BODY><H3>");
	props.put(msg + "/Recipient.txt", toAddress.getBytes(UTF8));
	msb.append(String.format("Recipient: %s</H3><P>",
				 WebEncoder.htmlEncode(toAddress)));
	msb.append("Subject: " + WebEncoder.htmlEncode(subject) + "</P><UL>");
	if (textMimeType != null && text != null) {
	    // props.put(msg + "/MediaType.txt", textMimeType.getBytes(UTF8));
	    props.put(msg + "/Text." + suffix, text.getBytes(UTF8));
	    msb.append(String.format("<LI><A HREF=\"/%s/Text.%s\"/>%s</A>",
				     msg, suffix,
				     WebEncoder.htmlEncode(textMimeType)));

	}
	if (altTextMimeType != null && altText != null) {
	    /*
	    props.put(msg + "/AltMediaType.txt",
		      altTextMimeType.getBytes(UTF8));
	    */
	    props.put(msg + "/AltText." + altSuffix, altText.getBytes(UTF8));
	    msb.append(String.format("<LI><A HREF=\"/%s/AltText.%s\"/>%s</A>",
				     msg, altSuffix,
				     WebEncoder.htmlEncode(altTextMimeType)));
	}
	int calID = 0;
	for (byte[] calendar: calendars) {
	    calID++;
	    props.put(msg + "/calendar" + calID + ".ics", calendar);
	    msb.append(String.format
		       ("<LI><A HREF=\"/%s/calendar%s.ics\">%s</A>",
			msg, calID, "Calendar " + calID));
				     
	}
	msb.append("</UL></BODY></HTML>\n");
	props.put(msg + "/index.html", msb.toString().getBytes(UTF8));
    }

    private boolean getStatus(JFrame frame, boolean preflight) {
	if (frame == null) {
	    Console console = System.console();
	    if (preflight) {
		String response;
		for(;;) {
		    response =
			console.readLine("Continue sending mail? [Yn]:");
		    if (response.equals("Y")) {
			return true;
		    } else if (response.equals("n")) {
			return false;
		    }
		}
	    } else {
		console.readLine("Please press the Enter key to "
				 + "stop the web server: ");
		return true;
	    }
	} else {
	    if (preflight) {
		int status = JOptionPane.showConfirmDialog
		    (frame, "Continue sending mail?",
		     "ecdb: Preview Control",
		     JOptionPane.YES_NO_OPTION);
		if (status == JOptionPane.YES_OPTION) {
		    return true;
		} else {
		    return false;
		}
	    } else {
		JOptionPane.showMessageDialog
		    (frame, "Stop the web server.",
		     "ecdb: web server for email dryrun",
		     JOptionPane.PLAIN_MESSAGE);
		return true;
	    } 
	}
    }

    public boolean complete(JFrame frame, boolean preflight, Object... args) {
	sb.append("</UL></BODY></HTML>\n");
	props.put("recipients.html", sb.toString().getBytes(UTF8));
	EmbeddedWebServer ews = new
	    EmbeddedWebServer(InetAddress.getLoopbackAddress(),
			      0, 48, 2, false);
	try {
	    int port = ews.getPort();
	    ews.add("/", PropertiesWebMap.class, props, null,
		    true, true, true);
	    WebMap wmap = ews.getWebMap("/");
	    wmap.addWelcome("recipients.html");
	    wmap.addMapping("ics", "text/calendar;charset=UTF-8");
	    URI uri = new URI("http://localhost:" + port + "/");
	    Desktop.getDesktop().browse(uri);
	    ews.start();
	    boolean status = getStatus(frame, preflight);
	    ews.stop(2);
	    return status;
	} catch (Exception e) {
	    e.printStackTrace();
	    boolean status = getStatus(frame, preflight);
	    try {
		ews.stop(2);
	    } catch (Exception ee) {}
	    return status;
	} finally {
	    // reset in case someone re-uses this agent.
	    props.clear();
	    sb.setLength(0);
	    sb.append("<!DOCTYPE HTML><HTML>");
	    sb.append("<HEAD><TITLE>ECDB: recipient list</TITLE></HEAD>");
	    sb.append("<BODY><UL>");
	}
    }
}
