/**
 * Event-calendar database package.
 *
 * This package provides an API for initializing and manipulating a
 * relational database representing an event calendar. In addition to
 * methods that select, insert, update, or delete table entries, ECDB
 * can generate RFC 5545 calendar appointments and send them via email.
 * <P>
 * Calendar appointments include the starting date, starting time,
 * ending date, and ending time for an event, whether there is some
 * activity before an event and when that starts, and two optional
 * alarms, with the times set on a per-user basis.  The class will also
 * send messages with or without calendar appointments to users to
 * either an email address or to the user's SMS service.  Each calendar
 * appointment is tagged with fields that allow a previously sent calendar
 * appointment to be updated.
 *
 * Please see <A HREF="doc-files/description.html">the description</A>
 * for further details.
 *
 */
package org.bzdev.ecdb;
