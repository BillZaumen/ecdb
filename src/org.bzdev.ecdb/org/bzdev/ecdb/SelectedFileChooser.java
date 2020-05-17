package org.bzdev.ecdb;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.table.*;
import java.beans.*;

/**
 * Custom file chooser for selecting files rather than opening them or
 * saving them.
 */
public class SelectedFileChooser extends JFileChooser {

    public static final int APPROVE_OPTION = JFileChooser.APPROVE_OPTION;
    public static final int CANCEL_OPTION = JFileChooser.CANCEL_OPTION;
    public static final int RESET_OPTION = 2;
	/*Math.max(APPROVE_OPTION, CANCEL_OPTION) + 1*/;

    boolean allowNewFile = true;

    /**
     * Constructor.
     * @param allowNewFile true if a file name can be typed into this
     *        component's text field; false otherwise
     */
    public SelectedFileChooser(boolean allowNewFile) {
	super();
	this.allowNewFile  = allowNewFile;
    }

    /**
     * Constructor specifying a current directory.
     * @param currentDirectory the current directory for this component
     * @param allowNewFile true if a file name can be typed into this
     *        component's text field; false otherwise
     */
    public SelectedFileChooser(File currentDirectory, boolean allowNewFile) {
	super(currentDirectory);
	this.allowNewFile  = allowNewFile;
    }

    /**
     * Constructor specifying a current directory and file system view.
     * @param currentDirectory the current directory for this component
     * @param fsv the file system view
     * @param allowNewFile true if a file name can be typed into this
     *        component's text field; false otherwise
     */
    public SelectedFileChooser(File currentDirectory, FileSystemView fsv,
			       boolean allowNewFile)
    {
	super(currentDirectory, fsv);
	this.allowNewFile  = allowNewFile;
    }

    /**
     * Constructor specifying a current directory and file system view.
     * @param currentDirectoryPath the current directory for this component
     * @param allowNewFile true if a file name can be typed into this
     *        component's text field; false otherwise
     */
    public SelectedFileChooser(String currentDirectoryPath,
			       boolean allowNewFile)
    {
	super(currentDirectoryPath);
	this.allowNewFile  = allowNewFile;
    }

    /**
     * Constructor specifying a file system view.
     * @param fsv the file system view
     * @param allowNewFile true if a file name can be typed into this
     *        component's text field; false otherwise
     */
    public SelectedFileChooser(FileSystemView fsv, boolean allowNewFile) {
	super(fsv);
	this.allowNewFile  = allowNewFile;
    }

    private int status;

    public boolean getAllowNewFile() {return allowNewFile;}

    /*
    private void showTree(String prefix, JComponent c) {
	for (int i = 0; i < c.getComponentCount(); i++) {
	    System.out.format("%scomponent %d: class = %s\n", prefix, i,
			      c.getComponent(i).getClass());
	    if (c.getComponent(i) instanceof JTextField)
		System.out.println("........  found a text field");
	    else if (c.getComponent(i) instanceof JButton) {
		JButton b = (JButton)c.getComponent(i);
		    System.out.format("........  found a JButton "
				      + "(text = \"%s\")\n", b.getText());
	    }
	    if (c.getComponent(i) instanceof JPanel) {
		showTree(prefix + "    ", (JPanel)c.getComponent(i));
	    }
	}
    }
    */
    private static JTextField findTextField(JComponent c) {
	try {
	    for (int i = 0; i < c.getComponentCount(); i++) {
		Component ci = c.getComponent(i);
		JTextField tmp = null;
		if (ci instanceof JPanel) {
		    tmp = findTextField((JComponent)ci);
		}
		if (tmp != null) {
		    return (JTextField) tmp;
		} else if (ci instanceof JTextField) {
		    return (JTextField)ci;
		}
	    }
	} catch (Exception e){}
	return null;
    }
    
    JComponent bc = null;
    JButton b1 = null;
    JButton b2 = null;
    int i1 = -1; int i2 = -1;
    private  void removeStdButtons(JComponent c, String text)  {
	try {
	    JButton b1 = null;
	    JButton b2 = null;
	    int i1 = -1; int i2 = -1;
	    for (int i = 0; i < c.getComponentCount(); i++) {
		Component ci = c.getComponent(i);
		if (ci instanceof JPanel) {
		    removeStdButtons((JComponent)ci, text);
		} else if (ci instanceof JButton) {
		    JButton b = (JButton) ci;
		    String txt = b.getText();
		    if (txt != null && txt.length() > 0) {
			if (b1 == null) {
			    b1 = b;
			    i1 = i;
			} else if (b2 == null) {
			    b2 = b;
			    i2 = i;
			}
		    }
		}
	    }
	    if (b1 != null && b2 != null) {
		if (b1.getText().equals(text)
		    || b2.getText().equals(text)) {
		    bc = c;
		    this.b1 = b1;
		    this.b2 = b2;
		    this.i1 = i1;
		    this.i2 = i2;
		    c.remove(i2);
		    c.remove(i1);
		}
	    }
	} catch (Exception e){}
    }
    private void restoreStdButtons() {
	if (bc != null && b1 != null && b2 != null) {
	    bc.add(b1, i1);
	    bc.add(b2, i2);
	    bc = null;
	    b1 = null;
	    b2 = null;
	    i1 = -1;
	    i2 = -1;
	}
    }

    private static final String APPROVE_BUTTON_TEXT = "Select";
    private static final String RESET_BUTTON_TEXT = "Accept Reset";

    public int showSelectDialog(Component parent,
				String approveButtonText,
				String resetButtonText)
    {
	Component top = SwingUtilities.getRoot(parent);
	Frame fOwner = null;
	Dialog dOwner = null;
	Window wOwner = null;
	if (top instanceof Frame) {
	    fOwner = (Frame) top;
	} else if (top instanceof Dialog) {
	    dOwner = (Dialog) top;
	} else if (top instanceof Window) {
	    wOwner = (Window) top;
	}

	JTextField tf = findTextField(this);
	if (allowNewFile) {
	    File sf = getSelectedFile();
	    tf.setText((sf == null)? "": sf.getName());
	    tf.addActionListener((ae) -> {
		    String name = tf.getText();
		    name = (name == null)?"":name.trim();
		    File dir = getCurrentDirectory();
		    File cf = getSelectedFile();
		    String cfname = (cf == null)?"":cf.getName();
		    if ((cfname.equals(name))) {
			return;
		    }
		    if (name.length() > 0) {
			File f = new File(dir, name);
			if (f.exists()) {
			    switch (getFileSelectionMode()) {
			    case JFileChooser.FILES_ONLY:
				if (f.isFile()) {
				    setSelectedFile(f);
				} else {
				    setSelectedFile(null);
				    setCurrentDirectory(dir);
				    tf.setText(name);
				}
			    case JFileChooser.DIRECTORIES_ONLY:
				if (f.isDirectory()) {
				    setSelectedFile(f);
				} else {
				    setSelectedFile(null);
				    setCurrentDirectory(dir);
				    tf.setText(name);
				}
				break;
			    case JFileChooser.FILES_AND_DIRECTORIES:
				setSelectedFile(f);
				break;
			    }
			    firePropertyChange(SELECTED_FILE_CHANGED_PROPERTY,
					       cf, f);
			} else {
			    setSelectedFile(f);
			    firePropertyChange(SELECTED_FILE_CHANGED_PROPERTY,
					       cf, f);
			}
		    } else {
			setSelectedFile(null);
			firePropertyChange(SELECTED_FILE_CHANGED_PROPERTY,
					   cf, null);
			setCurrentDirectory(dir);
		    }
		});
	} else {
	    tf.setEditable(false);
	}
	    
	JPanel panel = new JPanel(new BorderLayout());
	panel.add(this, BorderLayout.CENTER);
	// setControlButtonsAreShown(false);
	setApproveButtonText(approveButtonText);
	removeStdButtons(this, getApproveButtonText());
	JPanel ctlpane = new JPanel(new FlowLayout(FlowLayout.TRAILING));
	if (approveButtonText == null) {
	    approveButtonText= APPROVE_BUTTON_TEXT;
	}
	JButton approveButton = new JButton (approveButtonText);
	if (resetButtonText == null) {
	    resetButtonText = RESET_BUTTON_TEXT;
	}
	JButton resetButton = new JButton (resetButtonText);
	JButton cancelButton = new JButton("Cancel");
	ctlpane.add(approveButton);
	ctlpane.add(resetButton);
	ctlpane.add(cancelButton);
	panel.add(ctlpane, BorderLayout.SOUTH);
	String title = getDialogTitle();
	JDialog dialog = (fOwner != null)? new JDialog(fOwner, title, true):
	    ((dOwner != null)? new JDialog(dOwner, title, true):
	     new JDialog(wOwner, title, Dialog.ModalityType.APPLICATION_MODAL));
	addPropertyChangeListener((pce) -> {
		String pname = pce.getPropertyName();
		if (pname.equals(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY)) {
		    approveButton.setEnabled
			(SelectedFileChooser.this.getSelectedFile() != null);
		} else if (pname.equals
			   (JFileChooser.SELECTED_FILES_CHANGED_PROPERTY)) {
		    approveButton.setEnabled
			(SelectedFileChooser.this.getSelectedFiles() != null);
		}
	    });
	status = -1;
	ActionListener actionListener = (ae) -> {
	    Object src = ae.getSource();
	    if (src == approveButton) {
		status =  SelectedFileChooser.APPROVE_OPTION;
	    } else if (src == resetButton) {
		setSelectedFile(null);
		tf.setText("");
		status = SelectedFileChooser.RESET_OPTION;
	    } else if (src == cancelButton) {
		status = SelectedFileChooser.CANCEL_OPTION;
	    }
	    if (status > -1) {
		dialog.setVisible(false);
	    }
	};
	approveButton.addActionListener(actionListener);
	resetButton.addActionListener(actionListener);
	cancelButton.addActionListener(actionListener);
	approveButton.setEnabled(isMultiSelectionEnabled()?
				 (getSelectedFiles() != null):
				 (getSelectedFile() != null));
	dialog.add(panel);
	dialog.pack();
	dialog.setVisible(true);
	restoreStdButtons();
	firePropertyChange("JFileChooserDialogIsClosingProperty", dialog, null);
	dialog.getContentPane().removeAll();
	dialog.dispose();
	return status;
    }


    public int showSelectDialog(Component parent) {
	return showSelectDialog(parent, APPROVE_BUTTON_TEXT, RESET_BUTTON_TEXT);
    }
}

