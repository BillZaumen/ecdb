package org.bzdev.ecdb;

import java.awt.*;
import java.awt.event.*;
import java.util.Vector;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.table.*;

/**
 * Table pane for inputing data.
 * This class creates a JPanel containing a JTable plus some controls.
 * The class {@link InputTablePane.ColSpec} specifies each column in
 * the table, indicating a title, how the cells in the column are rendered,
 * and how the cells in the column are edited (a null editor indicates that
 * the column is a read-only column).
 * <P>
 * There are methods for determining the number of rows and columns in the
 * table and for obtaining the values at any given cell, and to create
 * dialog boxes containing this panel or a new instance of this panel.
 * <P>
 * The table model for this panel's table is initialized by providing a
 * {@link java.util.Vector} whose type is
 * Vector&lt;Vector&lt;Object&gt;&gt;, with the Vector&lt;Object&gt;
 * representing the rows.
 */
public class InputTablePane extends JPanel {

    private static int configColumn(JTable table, int col,
				    String heading, String example)
    {
	TableCellRenderer tcr = table.getDefaultRenderer(String.class);
	int w;
	if (tcr instanceof DefaultTableCellRenderer) {
	    DefaultTableCellRenderer dtcr = (DefaultTableCellRenderer)tcr;
	    FontMetrics fm = dtcr.getFontMetrics(dtcr.getFont());
	    int cw = Math.max(fm.stringWidth(heading), fm.stringWidth(example));
	    w = 10 + cw;
	} else {
	    int cw = Math.max(heading.length(), example.length());
	    w = 10 + 12 * cw;
	}
	TableColumnModel cmodel = table.getColumnModel();
	TableColumn column = cmodel.getColumn(col);
	int ipw = column.getPreferredWidth();
	if (ipw > w) {
	    w = ipw; 
	}
	column.setPreferredWidth(w);
	return w;
    }

    /**
     * Specification for a table column.
     */
    public static class ColSpec {
	String heading;
	String example;
	Class<?> clasz;
	TableCellRenderer tcr;
	TableCellEditor tce;

	/**
	 * Constructor.
	 * <P>
	 * Note: if one explicitly sets the trc argument to
	 * {@link DefaultTableCellRenderer}, {@link javax.swing.JTable}
	 * implementation will render boolean values as strings not as
	 * checkboxes. As a general rule, one should set the tcr argument
	 * to specify a specific table cell renderer to use when the default
	 * behavior is not wanted.
	 * @param heading the table heading
	 * @param example, sample text to compute the column width
	 * @param clasz the class for the data in this column
	 * @param tcr the table-cell renderer used to render a cell in the
	 *        column; null for the {@link javax.swing.JTable} default
	 *        redenderer
	 * @param tce the table-cell editor used to edit a cell in the table;
	 *        null if the column is not editable
	 */
	public ColSpec(String heading, String example, Class<?> clasz,
		       TableCellRenderer tcr, TableCellEditor tce)
	{
	    this.heading = heading;
	    this.example = example;
	    this.clasz = clasz;
	    this.tcr = tcr;
	    this.tce = tce;
	}
    }
    private DefaultTableModel tm;

    /**
     * Constructor.
     * @param colspec an array (one entry per column in column  order)
     *        specifying a column
     * @param nrows the number of rows
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public InputTablePane(ColSpec[] colspec, int nrows,
			  boolean canAdd, boolean canDel, boolean canMove)
    {
	this(colspec, nrows, null, canAdd, canDel, canMove);
    }

    /**
     * Constructor based on explicitly provided rows.
     * @param colspec an array (one entry per column in column  order)
     *        specifying a column
     * @param rows the table's rows as a Vector  of rows, where each row
     *        contains a vector whose elements fit the specification provided
     *        by the first argument (colspec)
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public InputTablePane(ColSpec[] colspec, Vector<Vector<Object>>rows,
			  boolean canAdd, boolean canDel, boolean canMove)
    {
	this(colspec, Math.max(rows.size(), 1), rows, canAdd, canDel, canMove);
    }

    /*private*/ JTable table;

    /**
     * Constructor with explicitly provided rows, possibly followed by
     * blank rows.
     * @param colspec an array (one entry per column in column  order)
     *        specifying a column
     * @param initialRows the table's initial rows as a Vector of rows,
     *         where each row contains a vector whose elements fit the
     *        specification provided by the first argument (colspec)
     * @param nrows the number of rows
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public InputTablePane(ColSpec[] colspec, int nrows,
			  Vector<Vector<Object>> initialRows,
			  boolean canAdd, boolean canDel, boolean canMove)
    {
	super();
	JButton addRowButton = canAdd? new JButton("Append Row"): null;
	JButton insertRowButton =
	    (canAdd && canMove)? new JButton("Insert Row"): null;
	JButton deleteRowButton = canDel? new JButton("Delete Row"): null;
	JButton moveUpButton = canMove? new JButton("Move Up"): null;
	JButton moveDownButton = canMove? new JButton("MoveDown"): null;
	JButton clearSelectionButton = new JButton("Clear Selection");

	table = new JTable() {
		public Class<?> getColumnClass(int columnIndex) {
		    return colspec[columnIndex].clasz;
		}
		public boolean isCellEditable(int row, int col) {
		    return colspec[col].tce != null;
		}
	    };
	Vector<String> colHeadings = new Vector<>(colspec.length);
	for (int i = 0; i < colspec.length; i++) {
	    colHeadings.add(colspec[i].heading);
	}
	Vector<Vector<Object>>rows;
	if (initialRows != null) {
	    int isize = initialRows.size();
	    int max = Math.max(isize, nrows);
	    rows = new Vector<Vector<Object>>(max);
	    for (int i = 0; i < isize; i++) {
		Vector<Object>row = new Vector<>(colspec.length);
		for (int j = 0; j < colspec.length; j++) {
		    row.add(initialRows.get(i).get(j));
		}
		rows.add(row);
	    }
	    for (int i = isize; i < max; i++) {
		Vector<Object>row = new Vector<>(colspec.length);
		for (int j = 0; j < colspec.length; j++) {
		    row.add(null);
		}
		rows.add(row);
	    }
	} else {
	    rows = new Vector<Vector<Object>>(nrows);
	    for (int i = 0; i < nrows; i++) {
		Vector<Object>row = new Vector<>(colspec.length);
		for (int j = 0; j < colspec.length; j++) {
		    row.add(null);
		}
		rows.add(row);
	    }
	}
	int twidth = 0;
	tm = new DefaultTableModel(rows, colHeadings);
	table.setModel(tm);
	TableColumnModel tcm = table.getColumnModel();
	for (int i = 0; i < colspec.length; i++) {
	    TableColumn tc = tcm.getColumn(i);
	    if (colspec[i].tcr != null) {
		tc.setCellRenderer(colspec[i].tcr);
	    }
	    tc.setCellEditor(colspec[i].tce);
	    twidth += configColumn(table, i, colspec[i].heading,
				   colspec[i].example);
	}
	table.setColumnSelectionAllowed(true);
	table.setRowSelectionAllowed(true);
	ListSelectionModel lsm = new DefaultListSelectionModel();
	lsm.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
	lsm.addListSelectionListener((se) -> {
		boolean test1 = table.getSelectedRowCount() > 0;
		boolean test2 =
		    table.getSelectedColumnCount() == table.getColumnCount();
		if (test1 && test2) {
		    if (insertRowButton != null) {
			insertRowButton.setEnabled(true);
		    }
		    if (deleteRowButton != null) {
			deleteRowButton.setEnabled(true);
		    }
		    if (canMove) {
			moveUpButton.setEnabled(true);
			moveDownButton.setEnabled(true);
		    }
		} else {
		    if (insertRowButton != null) {
			insertRowButton.setEnabled(false);
		    }
		    if (deleteRowButton != null) {
			deleteRowButton.setEnabled(false);
		    }
		    if (canMove) {
			moveUpButton.setEnabled(false);
			moveDownButton.setEnabled(false);
		    }
		}
		clearSelectionButton.setEnabled(test1);
	    });	
	table.setSelectionModel(lsm);
	
	JScrollPane sp = new JScrollPane(table);
	table.setFillsViewportHeight(true);
	Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
	twidth += 10;
	int maxwidth = (screensize.width*8)/10;
	int maxheight = (screensize.height*8)/10;
	if (twidth > maxwidth) twidth = maxwidth;
	int theight = 14*(nrows+2);
	if (theight > maxheight) theight = maxheight;
	sp.setPreferredSize(new Dimension(twidth, theight));
	setLayout(new BorderLayout());
	add(sp, BorderLayout.CENTER);

	JPanel topPanel = new JPanel();
	topPanel.setLayout(new FlowLayout());

	if (addRowButton != null) {
	    addRowButton.setEnabled(true);
	    addRowButton.addActionListener((ae) -> {
		    Object[] nullrow = null;
		    tm.addRow(nullrow);
		});
	    topPanel.add(addRowButton);
	}
	if (insertRowButton != null) {
	    insertRowButton.setEnabled(false);
	    insertRowButton.addActionListener((ae) -> {
		    int ind = table.getSelectedRow();
		    Object[] nullrow = null;
		    tm.insertRow(ind, nullrow);
		});
	    topPanel.add(insertRowButton);
	}
	if (deleteRowButton != null) {
	    deleteRowButton.setEnabled(false);
	    deleteRowButton.addActionListener((ae) -> {
		    int ind = table.getSelectedRow();
		    int n = table.getSelectedRowCount();
		    while (n-- > 0) {
			tm.removeRow(ind);
		    }
		    table.clearSelection();
		});
	}
	if (canMove) {
	    moveUpButton.setEnabled(false);
	    moveUpButton.addActionListener((ae) -> {
		    int ind = table.getSelectedRow();
		    int n = table.getSelectedRowCount();
		    int end = ind + n - 1;
		    if (ind > 0) {
			tm.moveRow(ind, end, ind-1);
			table.setRowSelectionInterval(ind-1, end-1);
		    }
		});
	    topPanel.add(moveUpButton);
	}
	if (canMove) {
	    moveDownButton.setEnabled(false);
	    moveDownButton.addActionListener((ae) -> {
		    int ind = table.getSelectedRow();
		    int n = table.getSelectedRowCount();
		    int end = ind + n - 1;
		    int lastRow = table.getRowCount() - 1;
		    if (end < lastRow) {
			tm.moveRow(ind, end, ind+1);
			table.setRowSelectionInterval(ind+1, end+1);
		    }
		});
	    topPanel.add(moveDownButton);
	}
	clearSelectionButton.setEnabled(false);
	clearSelectionButton.addActionListener((ae) -> {
		table.clearSelection();
	    });
	topPanel.add(clearSelectionButton);
	add(topPanel, BorderLayout.NORTH);
    }

    /**
     * Get the number of rows in the table.
     * @return the number of rows
     */
    public int getRowCount() {return tm.getRowCount();}

    /**
     * Get the number of columns in the table.
     * @return the number of tables.
     */
    public int getColumnCount() {return tm.getColumnCount();}

    /**
     * Get the value of a table cell for this table.
     * @param rowIndex the row index (starting at 0)
     * @param columnIndex the column index (starting at 0)
     * @return the value for the specified row and column
     */
    public Object getValueAt(int rowIndex, int columnIndex) {
	return tm.getValueAt(rowIndex, columnIndex);
    }
    
    /**
     * Stop editing a cell.
     */
    public void stopCellEditing() {
	CellEditor ce = table.getCellEditor();
	if (ce != null) {
	    ce.stopCellEditing();
	}
    }

    /**
     * Create a new InputTablePane inside a dialog.
     * @param parent the component on which to center the dialog
     * @param title the title of the dialog
     * @param colspec the column specification for the table
     * @param nrows the number of rows in the table
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public static InputTablePane
	showDialog(Component parent, String title,
		   ColSpec[] colspec, int nrows,
		   boolean canAdd, boolean canDel, boolean canMove)
    {
	return showDialog(parent, title, colspec, nrows, null,
			  canAdd, canDel, canMove);
    }

    /**
     * Create a new InputTablePane, initializing some rows, placing
     * the table inside a dialog.
     * @param parent the component on which to center the dialog
     * @param title the title of the dialog
     * @param colspec the column specification for the table
     * @param initialRows the rows to initially add to the table,
     *        which determines the initial number of rows in the table.
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public static InputTablePane
	showDialog(Component parent, String title,
		   ColSpec[] colspec, Vector<Vector<Object>> initialRows,
		   boolean canAdd, boolean canDel, boolean canMove)
    {
	return showDialog(parent, title, colspec,
			  Math.max(initialRows.size(), 1), initialRows,
			  canAdd, canDel, canMove);
    }

    private static Object[] options = {"OK", "Cancel"};

    /**
     * Create a new InputTablePane, initializing some rows, placing
     * the table inside a dialog.
     * @param parent the component on which to center the dialog
     * @param title the title of the dialog
     * @param colspec the column specification for the table
     * @param initialRows the rows to initially add to the table,
     *        placed as the first rows in the table
     * @param nrows the number of rows in the table
     * @param canAdd true if rows can be added to the table; false otherwise
     * @param canDel true if rows can be deleted from the table;
     *        false otherwise
     * @param canMove true if rows can be moved up or down in the table;
     *        false otherwise
     */
    public static InputTablePane
	showDialog(Component parent, String title,
		   ColSpec[] colspec, int nrows,
		   Vector<Vector<Object>> initialRows,
		   boolean canAdd, boolean canDel, boolean canMove)
    {
	InputTablePane pane = new InputTablePane(colspec, nrows, initialRows,
						 canAdd, canDel, canMove);

	int status = JOptionPane.showOptionDialog(parent, pane, title,
						  JOptionPane.YES_NO_OPTION,
						  JOptionPane.PLAIN_MESSAGE,
						  null,
						  options, options[0]);
	pane.stopCellEditing();
	if (status == 0 || status == JOptionPane.CLOSED_OPTION) {
	    return pane;
	} else {
	    return null;
	}
    }

    /**
     * The value returned by
     * {@link #showDialog(Component,String,InputTablePane)} when
     * the dialog's OK button is pressed.
     */
    public static final int OK = 0;

    /**
     * The value returned by
     * {@link #showDialog(Component,String,InputTablePane)} when
     * the dialog's CANCEL button is pressed.
     */
    public static final int CANCEL = 1;

    /**
     * Show a dialog containing an InputTablePane
     * @param parent the component on which to center the dialog
     * @param title the title of the dialog
     * @param ipane the InputTablePane to display
     * @return {@link InputTablePane#OK} if the input is accepted;
     *        {@link InputTablePane#CANCEL} if the input is cancelled
     */
    public static int showDialog(Component parent, String title,
				       InputTablePane ipane)
    {
	int status = JOptionPane.showOptionDialog(parent, ipane, title,
						  JOptionPane.YES_NO_OPTION,
						  JOptionPane.PLAIN_MESSAGE,
						  null,
						  options, options[0]);
	ipane.stopCellEditing();
	return status;
    }
}
