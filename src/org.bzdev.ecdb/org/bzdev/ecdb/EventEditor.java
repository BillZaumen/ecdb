package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

/**
 * Table-cell editor for ECDB events.
 */
public class EventEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.EventLabeledID>
	getComboBox(ECDB ecdb, Connection conn, int ownerID) throws SQLException
    {
	ECDB.EventLabeledID[] labels = ecdb.listEventLabeledIDs(conn, ownerID);
	return new JComboBox<ECDB.EventLabeledID>(labels);
    }

    /**
     * Constructor.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param ownerID the ownerID for an event
     */
    public EventEditor(ECDB ecdb, Connection conn, int ownerID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID));
    }
}
