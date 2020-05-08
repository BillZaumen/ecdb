package org.bzdev.ecdb.dryrun;
import org.bzdev.ecdb.SMTPAgentSPI;
import org.bzdev.ecdb.SMTPAgent;



public class DryrunSMTPAgentProvider implements SMTPAgentSPI {
    String name = "dryrun";

    public DryrunSMTPAgentProvider() {}

    @Override
    public String getName() {return name;}

    @Override
    public SMTPAgent createAgent() {
	return new DryrunSMTPAgent();
    }
}
