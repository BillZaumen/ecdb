package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

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

    public InstanceEditor(ECDB ecdb, Connection conn,
			  int ownerID, int eventID, int locationID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID, eventID, locationID));
    }
}
