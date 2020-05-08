package org.bzdev.ecdb.javamail;
import org.bzdev.ecdb.SMTPAgentSPI;
import org.bzdev.ecdb.SMTPAgent;



public class JavamailSMTPAgentProvider implements SMTPAgentSPI {
    String name = "javamail";

    public JavamailSMTPAgentProvider() {}

    @Override
    public String getName() {return name;}

    @Override
    public SMTPAgent createAgent() {
	return new JavamailSMTPAgent();
    }

}
