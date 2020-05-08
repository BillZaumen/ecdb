module org.bzdev.ecdb.dryrun {
    requires java.base;
    requires java.desktop;
    requires org.bzdev.base;
    requires org.bzdev.ejws;
    requires org.bzdev.ecdb;
    provides org.bzdev.ecdb.SMTPAgentSPI with
	org.bzdev.ecdb.dryrun.DryrunSMTPAgentProvider;
}
