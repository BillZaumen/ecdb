package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

public class OwnerEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.OwnerLabeledID>
	getComboBox(ECDB ecdb, Connection conn) throws SQLException
    {
	ECDB.OwnerLabeledID[] labels = ecdb.listOwnerLabeledIDs(conn);
	return new JComboBox<ECDB.OwnerLabeledID>(labels);
    }

    public OwnerEditor(ECDB ecdb, Connection conn) throws SQLException{
	super(getComboBox(ecdb, conn));
    }
}
