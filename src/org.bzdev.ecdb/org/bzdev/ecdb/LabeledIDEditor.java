package org.bzdev.ecdb;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Vector;
import javax.swing.*;

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

    public LabeledIDEditor(ECDB ecdb, Connection conn, ECDB.Table type) {
	super(getComboBox(ecdb, conn, type));
    }
}
