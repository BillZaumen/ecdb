module org.bzdev.ecdb {
    exports org.bzdev.ecdb;
    requires org.bzdev.base;
    requires java.base;
    requires java.datatransfer;
    requires java.desktop;
    requires java.sql;
    uses org.bzdev.ecdb.CellEmailFinderSPI;
    uses org.bzdev.ecdb.SMTPAgentSPI;
}
