module org.bzdev.ecdb.javamail {
    requires java.base;
    requires java.activation;
    requires javax.mail;
    requires org.bzdev.ecdb;
    provides org.bzdev.ecdb.SMTPAgentSPI with
	org.bzdev.ecdb.javamail.JavamailSMTPAgentProvider;
}
