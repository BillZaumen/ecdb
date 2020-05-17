package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

/**
 * Table-cell editor for ECDB Event instances.
 */
public class InstanceEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.InstanceLabeledID>
	getComboBox(ECDB ecdb, Connection conn,
		    int ownerID, int eventID, int locationID)
	throws SQLException
    {
	ECDB.InstanceLabeledID[] labels =
	    ecdb.listInstanceLabeledIDs(conn, ownerID, eventID, locationID);
	return new JComboBox<ECDB.InstanceLabeledID>(labels);
    }

    /**
     * Constructor.
     * The arguments ownerID, eventId, and locationID are used to filter
     * all event instances to select a useful subset.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param ownerID the ownerID for an event; -1 for any owner
     * @param eventID the eventID; -1 for any event
     * @param locationID; -1 for any location
     */
      public InstanceEditor(ECDB ecdb, Connection conn,
			  int ownerID, int eventID, int locationID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID, eventID, locationID));
    }
}
