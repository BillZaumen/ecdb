package org.bzdev.ecdb;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.net.URL;
import java.util.Vector;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.table.*;

public class MsgPane extends InputTablePane {

    static class MsgPropertyEditor extends AbstractCellEditor
	implements TableCellEditor, ActionListener
    {
	JTable table;
	Object currentObject;
	int row;
	JComboBox<OurMediaType> msgComboBox;
	JTextField tf;
	JButton fileButton;
	JDialog dialog;
	SelectedFileChooser fileChooser;

	protected static final String EDIT = "edit";

	public MsgPropertyEditor() {
	    msgComboBox = new
		JComboBox<OurMediaType>(OurMediaType.class.getEnumConstants());
	    msgComboBox.addPopupMenuListener(new PopupMenuListener() {
		    public void popupMenuCanceled(PopupMenuEvent e) {}
		    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
		    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			currentObject = msgComboBox.getSelectedItem();
		    }
		});

	    tf = new JTextField(48);
	    fileButton = new JButton();
	    fileButton.setActionCommand(EDIT);
	    fileButton.addActionListener(this);
	    fileButton.setBorderPainted(false);

	    File home = new File(System.getProperty("user.home"));
	    File cwd = new File(System.getProperty("user.dir"));
	    File parent = cwd;
	    boolean flag = false;
	    while (parent != null) {
		if (parent.equals(home)) {
		    flag = true;
		    break;
		}
		parent = parent.getParentFile();
	    }
	    fileChooser = new SelectedFileChooser((flag? cwd: home), false);
	    FileFilter filter = new FileNameExtensionFilter("Template", "tpl");
	    fileChooser.addChoosableFileFilter(filter);
	    fileChooser.setFileFilter(filter);
	}

	public void actionPerformed(ActionEvent e) {
	    if (EDIT.equals(e.getActionCommand())) {
		if (row == 2 || row == 4) {
		    File f = (File) currentObject;
		    fileChooser.setSelectedFile(f);
		    int status = fileChooser.showSelectDialog(table,
							      "Select File",
							      null);
		    switch (status) {
		    case SelectedFileChooser.APPROVE_OPTION:
			currentObject = fileChooser.getSelectedFile();
			break;
		    case SelectedFileChooser.RESET_OPTION:
			currentObject = null;
		    }
		}
		fireEditingStopped(); // so the renderer comes back
	    } else {
		if (row == 2 || row == 4) {
		    currentObject = fileChooser.getSelectedFile();
		}
	    }
	}

	public Object getCellEditorValue() {
	    if (row == 0) {
		return tf.getText();
	    }
	    return currentObject;
	}

	public Component getTableCellEditorComponent(JTable table,
						     Object value,
						     boolean isSelected,
						     int row, int column)
	{
	    this.table = table;
	    if (column == 0) return null;
	    this.row = row;
	    currentObject = value;
	    switch (row) {
	    case 0:			// subject (String)
		tf.setText((String)value);
		return tf;
	    case 1:			// media type (OurMediaType)
	    case 3:                 // alt media type (OurMediaTYpe)
		msgComboBox.setSelectedIndex(((OurMediaType)value).ordinal());
		return msgComboBox;
	    case 2:			// template (File)
	    case 4:			// alt template (File)
		return fileButton;
	    }
	    return null;
	}
    }
    
    ECDB ecdb;
    static enum OurMediaType {
	NONE  {
	    @Override
	    public String toString() {
		return "[ NULL ]";
	    }
	},
	PLAIN {
	    @Override
	    public String toString() {
		return "text/plain; charset=UTF-8";
	    }
	},
	HTML {
	    @Override
	    public String toString() {
		return "text/html; charset=UTF-8";
	    }
	}
    }
    
    static InputTablePane.ColSpec colspec[] = {
	new InputTablePane.ColSpec("Message Property",
				   "    Message Property    ",
				   String.class,
				   new DefaultTableCellRenderer(), null),
	new InputTablePane.ColSpec("Value",
				   "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
				   Object.class,
				   new DefaultTableCellRenderer(),
				   new MsgPropertyEditor())
    };

    static Vector<Vector<Object>>getInitialRows(ECDB ecdb) {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>(5);
	try {
	    Vector<Object> row = new Vector<Object>(2);
	    row.add("Subject");
	    row.add(ecdb.getSubject());
	    vector.add(row);
	    row = new Vector<Object>(2);
	    row.add("Message-Component Media Type");
	    String mt = ecdb.getMediaType();
	    if (mt == null) {
		row.add(OurMediaType.NONE);
	    } else if (mt.startsWith("text/plain")) {
		row.add(OurMediaType.PLAIN);
	    } else {
		row.add(OurMediaType.HTML);
	    }
	    vector.add(row);
	    row = new Vector<Object>(2);
	    row.add("Message Template");
	    URL url = ecdb.getTemplateURL();
	    if (url == null) {
		row.add(null);
	    } else {
		row.add(new File (url.toURI()));
	    }
	    vector.add(row);
	    row = new Vector<Object>(2);
	    row.add("Alt Message-Component Media Type");
	    mt = ecdb.getAltMediaType();
	    if (mt == null) {
		row.add(OurMediaType.NONE);
	    } else if (mt.startsWith("text/plain")) {
		row.add(OurMediaType.PLAIN);
	    } else {
		row.add(OurMediaType.HTML);
	    }
	    vector.add(row);
	    row = new Vector<Object>(2);
	    row.add("Alt Message Template");
	    url = ecdb.getAltTemplateURL();
	    if (url == null) {
		row.add(null);
	    } else {
		row.add(new File (url.toURI()));
	    }
	    vector.add(row);
	    return vector;
	} catch (Exception e) {
	    System.err.println("ECDB: " + e.getMessage());
	}
	return null;
    }

    public MsgPane(ECDB ecdb) {
	super(colspec, getInitialRows(ecdb), false, false, false);
    }


    public static MsgPane showDialog(Component parent, String title,
				     ECDB ecdb)
    {

	MsgPane mpane = new MsgPane(ecdb);
	int status = InputTablePane.showDialog(parent, title, mpane);
	if (status == 0 || status == JOptionPane.CLOSED_OPTION) {
	    return mpane;
	} else {
	    return null;
	}
    }
}
