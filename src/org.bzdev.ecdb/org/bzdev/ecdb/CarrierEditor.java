package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

public class CarrierEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.CarrierLabeledID>
	getComboBox(ECDB ecdb, Connection conn) throws SQLException
    {
	ECDB.CarrierLabeledID[] labels = ecdb.listCarrierLabeledIDs(conn);
	return new JComboBox<ECDB.CarrierLabeledID>(labels);
    }

    public CarrierEditor(ECDB ecdb, Connection conn) throws SQLException{
	super(getComboBox(ecdb, conn));
    }
}
