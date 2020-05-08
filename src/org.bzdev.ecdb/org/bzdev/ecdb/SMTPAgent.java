package org.bzdev.ecdb;

import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Vector;
import javax.swing.JFrame;

public abstract class SMTPAgent {

    protected SMTPAgent() {}

    static ServiceLoader<SMTPAgentSPI> providers =
	ServiceLoader.load(SMTPAgentSPI.class);
    
    public static SMTPAgent newInstance(String name) {
	if (name == null) {
	    for (SMTPAgentSPI provider: providers) {
		SMTPAgent agent = provider.createAgent();
		if (agent != null) return agent;
	    }
	} else {
	    for (SMTPAgentSPI provider: providers) {
		if (provider.getName().equals(name)) {
		    return provider.createAgent();
		}
	    }
	}
	return null;
    }

    public abstract void send(Properties properties, String address,
			      Vector<byte[]> calendars)
	throws Exception;

    public boolean complete(JFrame frame, boolean preflight, Object... rest) {
	return true;
    }
}
