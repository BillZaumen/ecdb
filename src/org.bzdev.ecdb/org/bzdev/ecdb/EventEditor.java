package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

public class EventEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.EventLabeledID>
	getComboBox(ECDB ecdb, Connection conn, int ownerID) throws SQLException
    {
	ECDB.EventLabeledID[] labels = ecdb.listEventLabeledIDs(conn, ownerID);
	return new JComboBox<ECDB.EventLabeledID>(labels);
    }

    public EventEditor(ECDB ecdb, Connection conn, int ownerID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID));
    }
}
