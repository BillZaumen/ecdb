package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

/**
 * Table-cell editor for ECDB  carriers.
 * This editor uses a {@link javax.swing.JComboBox}, whose type
 * parameter is {@link ECDB.CarrierLabeledID}, and that was initialized
 * from the ECDB database tables.
 */
public class CarrierEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.CarrierLabeledID>
	getComboBox(ECDB ecdb, Connection conn) throws SQLException
    {
	ECDB.CarrierLabeledID[] labels = ecdb.listCarrierLabeledIDs(conn);
	return new JComboBox<ECDB.CarrierLabeledID>(labels);
    }

    /**
     * Constructor.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @throws SQLException if an SQL error occurred
     */
    public CarrierEditor(ECDB ecdb, Connection conn) throws SQLException{
	super(getComboBox(ecdb, conn));
    }
}
