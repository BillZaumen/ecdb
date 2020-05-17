package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

/**
 * Table cell editor for an ECDB labeled ID
 */
public class LabeledIDEditor extends DefaultCellEditor {

    private static JComboBox<ECDB.LabeledID>
	getComboBox(ECDB ecdb, Connection conn, ECDB.Table type)
    {
	Vector<ECDB.LabeledID> labels = new Vector<>();
	switch(type) {
	case USER:
	case OWNER:
	case LOCATION:
	case EVENT:
	case INSTANCE:
	}
	return new JComboBox<ECDB.LabeledID>(labels);
    }

    /**
     * Constructor.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param type the table type associated with an ID
     */
    public LabeledIDEditor(ECDB ecdb, Connection conn, ECDB.Table type) {
	super(getComboBox(ecdb, conn, type));
    }
}
