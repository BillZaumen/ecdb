package org.bzdev.ecdb;

import java.awt.*;
import java.awt.event.*;
import java.util.Vector;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.table.*;

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

    public static class ColSpec {
	String heading;
	String example;
	Class<?> clasz;
	TableCellRenderer tcr;
	TableCellEditor tce;

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

    public InputTablePane(ColSpec[] colspec, int nrows,
			  boolean canAdd, boolean canDel, boolean canMove)
    {
	this(colspec, nrows, null, canAdd, canDel, canMove);
    }

    public InputTablePane(ColSpec[] colspec, Vector<Vector<Object>>rows,
			  boolean canAdd, boolean canDel, boolean canMove)
    {
	this(colspec, Math.max(rows.size(), 1), rows, canAdd, canDel, canMove);
    }

    private JTable table;

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
	    tc.setCellRenderer(colspec[i].tcr);
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

    public int getRowCount() {return tm.getRowCount();}

    public int getColumnCount() {return tm.getColumnCount();}

    public Object getValueAt(int rowIndex, int columnIndex) {
	return tm.getValueAt(rowIndex, columnIndex);
    }
    
    public void stopCellEditing() {
	CellEditor ce = table.getCellEditor();
	if (ce != null) {
	    ce.stopCellEditing();
	}
    }

    public static InputTablePane
	showDialog(Component parent, String title,
		   ColSpec[] colspec, int nrows,
		   boolean canAdd, boolean canDel, boolean canMove)
    {
	return showDialog(parent, title, colspec, nrows, null,
			  canAdd, canDel, canMove);
    }

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
