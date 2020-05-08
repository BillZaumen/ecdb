package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

public class LocationEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.LocationLabeledID>
	getComboBox(ECDB ecdb, Connection conn) throws SQLException
    {
	ECDB.LocationLabeledID[] labels = ecdb.listLocationLabeledIDs(conn);
	return new JComboBox<ECDB.LocationLabeledID>(labels);
    }

    public LocationEditor(ECDB ecdb, Connection conn) throws SQLException{
	super(getComboBox(ecdb, conn));
    }
}
