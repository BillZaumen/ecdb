package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

/**
 * Table Cell editor for ECDB series.
 */
public class SeriesEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.SeriesLabeledID>
	getComboBox(ECDB ecdb, Connection conn, int ownerID) throws SQLException
    {
	ECDB.SeriesLabeledID[] labels =
	    ecdb.listSeriesLabeledIDs(conn, ownerID);
	return new JComboBox<ECDB.SeriesLabeledID>(labels);
    }

    /**
     * Constructor.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param ownerID an ECDB owner associated with a series
     */
    public SeriesEditor(ECDB ecdb, Connection conn, int ownerID)
	throws SQLException
    {
	super(getComboBox(ecdb, conn, ownerID));
    }
}
