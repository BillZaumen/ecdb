package org.bzdev.ecdb;

import java.sql.Date;
import java.time.LocalTime;
import java.time.format.FormatStyle;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.DateFormatter;

/**
 * Cell editor for dates.
 * <P>
 * This class is based on the example provided in a
 * <A HREF="https://docs.oracle.com/javase/tutorial/uiswing/examples/components/TableFTFEditDemoProject/src/components/IntegerEditor.java">
 * Java tutorial</A>.  That link includes a copyright and permission
 * to use/modify that example.
 *
 * 
 */
class DateEditor extends DefaultCellEditor {
    JFormattedTextField ftf;
    SimpleDateFormat tf;

    static boolean DEBUG = false;
    
    /**
     * Constructor.
     */
    public DateEditor() {
	super(new JFormattedTextField());
	ftf = (JFormattedTextField) getComponent();
	tf = new SimpleDateFormat("MM/dd/yyyy");
	DateFormatter formatter = new DateFormatter(tf);
	formatter.setFormat(tf);
	ftf.setFormatterFactory(new DefaultFormatterFactory(formatter));
	ftf.setHorizontalAlignment(JTextField.TRAILING);
	ftf.setFocusLostBehavior(JFormattedTextField.PERSIST);
        //React when the user presses Enter while the editor is
        //active.  (Tab is handled as specified by
        //JFormattedTextField's focusLostBehavior property.)
        ftf.getInputMap().put(KeyStroke.getKeyStroke(
						     KeyEvent.VK_ENTER, 0),
			      "check");
        ftf.getActionMap().put("check", new AbstractAction() {
		public void actionPerformed(ActionEvent e) {
		    if (!ftf.isEditValid()) { //The text is invalid.
			if (userSaysRevert()) { //reverted
			    ftf.postActionEvent(); //inform the editor
			}
		    } else try {              //The text is valid,
			    ftf.commitEdit();     //so use it.
			    ftf.postActionEvent(); //stop editing
			} catch (java.text.ParseException exc) { }
		}
	    });
    }

    //Override to invoke setValue on the formatted text field.
    /** Implements the TableCellEditor interface.
     * @param table the JTable requesting tha editor; null if no table
     * @param value the value of the cell to be edited; may not be null
     * @param isSelected true if the cell is to be rendered with highlighting
     *        to show that the cell is selected
     * @param row the index of the row for the cell being edited
     * @param column the index of the column for the cell being edited
     * @return the component that provides the editor
    */
    @Override
    public Component getTableCellEditorComponent(JTable table,
						 Object value,
						 boolean isSelected,
						 int row, int column)
    {
	JFormattedTextField ftf =
	    (JFormattedTextField)super
	    .getTableCellEditorComponent(table, value, isSelected,
					 row, column);
	if (ftf != null) {
	    // added a null-value check just in case: not used
	    // in the example.
	    ftf.setValue(value);
	}
	return ftf;
    }

    /**
     * Get the value contained in the editor.
     * @return the value contained in the editor
     */
    //Override to ensure that the value remains a java.sql.Date.
    public Object getCellEditorValue() {
	JFormattedTextField ftf = (JFormattedTextField)getComponent();
	Object o = ftf.getValue();
	if (o == null) {
	    return null;
	} else if (o instanceof java.sql.Time) {
	    return o;
	} else if (o instanceof java.util.Date) {
	    java.util.Date d = (java.util.Date) o;
	    return new java.sql.Date(d.getTime());
	} else {
	    if (DEBUG) {
		System.out.println("getCellEditorValue: " +o.getClass());
	    }
	    try {
		String s = o.toString();
		if (s.trim().length() == 0) return null;
		return tf.parseObject(s);
	    } catch (ParseException exc) {
		System.err.println("getCellEditorValue: can't parse: "
				   + o);
		return null;
	    }
	}
    }

    //Override to check whether the edit is valid,
    //setting the value if it is and complaining if
    //it isn't.  If it's OK for the editor to go
    //away, we need to invoke the superclass's version 
    //of this method so that everything gets cleaned up.
    /**
     * Stops editing.
     * @return true normally; false if the editor cannot create a
     *         date (e.g., because of a syntax error in a text field)
     */
    @Override
    public boolean stopCellEditing() {
	JFormattedTextField ftf = (JFormattedTextField)getComponent();
	if (ftf.isEditValid()) {
	    try {
		ftf.commitEdit();
	    } catch (java.text.ParseException exc) { }
	    
	} else { //text is invalid
	    if (!userSaysRevert()) { //user wants to edit
		return false; //don't let the editor go away
	    } 
	}
	return super.stopCellEditing();
    }

    /** 
     * Lets the user know that the text they entered is 
     * bad. Returns true if the user elects to revert to
     * the last good value.  Otherwise, returns false, 
     * indicating that the user wants to continue editing.
     */
    protected boolean userSaysRevert() {
	Toolkit.getDefaultToolkit().beep();
	ftf.selectAll();
	Object[] options = {"Edit",
			    "Revert"};
	int answer = JOptionPane
	    .showOptionDialog(SwingUtilities.getWindowAncestor(ftf),
			      "The value must be a date specified by "
			      + "the format MM/DD/YYYY\n"
			      + "You can either continue editing "
			      + "or revert to the last valid value.",
			      "Invalid Text Entered",
			      JOptionPane.YES_NO_OPTION,
			      JOptionPane.ERROR_MESSAGE,
			      null,
			      options,
			      options[1]);
	    
	if (answer == 1) { //Revert!
	    ftf.setValue(ftf.getValue());
	    return true;
	}
	return false;
    }
}
