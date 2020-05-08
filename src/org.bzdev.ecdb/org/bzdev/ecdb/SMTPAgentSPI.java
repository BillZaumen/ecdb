package org.bzdev.ecdb;

public interface SMTPAgentSPI {
    String getName();

    SMTPAgent createAgent();
}
