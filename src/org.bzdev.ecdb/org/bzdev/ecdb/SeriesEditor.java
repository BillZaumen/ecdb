package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

public class SeriesEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.SeriesLabeledID>
	getComboBox(ECDB ecdb, Connection conn, int ownerID) throws SQLException
    {
	ECDB.SeriesLabeledID[] labels =
	    ecdb.listSeriesLabeledIDs(conn, ownerID);
	return new JComboBox<ECDB.SeriesLabeledID>(labels);
    }

    public SeriesEditor(ECDB ecdb, Connection conn, int ownerID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID));
    }
}
