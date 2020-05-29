/**
 * Event-calendar database module.
 * This module uses a database to describe a series of events
 * (e.g., meetings, conferences, performances), users who may
 * attend these events, plus user preferences for
 * calendar-appointment alarms. The package it exports provides
 * an API for manipulating the database, and the ability to generate
 * calendar appointments customized to each user. This package
 * can email calendar appointments to users, with customized alarm
 * times, and can email these appointments to an SMS email gateway
 * so that the appointments can be added to a cell phone.
 */
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
