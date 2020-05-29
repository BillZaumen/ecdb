package org.bzdev.ecdb;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.table.*;

import org.bzdev.lang.UnexpectedExceptionError;
import org.bzdev.util.CopyUtilities;

class Support {

    static java.util.List<Image> iconList = new LinkedList<Image>();

    public static java.util.List<Image> getIconList() {
        return iconList;
    }

    static {
	try {
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon16.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon24.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon32.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon48.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon64.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon96.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon128.png")
                                     ))).getImage());
            iconList.add((new
                          ImageIcon((Support.class
                                     .getResource("webnail/webnailicon256.png")
                                     ))).getImage());
        } catch (Exception e) {
            System.err.println("initialization failed - "
			       + "missing icon for iconList");
        }
    }
    static final Charset UTF8 = ECDB.UTF8;
    static Properties properties = new Properties();
    static File file = null;
    static JFrame frame = null;

    static Properties defaultProperties() {
	Properties defaults = new Properties();
	try {
	    Reader r = new InputStreamReader
		(ECDB.class.getResourceAsStream("Defaults.properties"), UTF8);
	    defaults.load(r);
	    r.close();
	    return defaults;
	} catch (IOException e) {
	    throw new UnexpectedExceptionError(e);
	}
    }

    private static int configColumn(JTable table, int col, String example) {
	TableCellRenderer tcr = table.getDefaultRenderer(String.class);
	int w;
	if (tcr instanceof DefaultTableCellRenderer) {
	    DefaultTableCellRenderer dtcr = (DefaultTableCellRenderer)tcr;
	    FontMetrics fm = dtcr.getFontMetrics(dtcr.getFont());
	    w = 10 + fm.stringWidth(example);
	} else {
	    w = 10 + 12 * example.length();
	}
	TableColumnModel cmodel = table.getColumnModel();
	TableColumn column = cmodel.getColumn(col);
	int ipw = column.getPreferredWidth();
	if (ipw > w) {
	    w = ipw; 
	}
	column.setPreferredWidth(w);
	/*
	if (col == 1) {
	    column.setMinWidth(w);
	} else 	if (col == 3) {
	    column.setMinWidth(15+w);
	}
	*/
	return w;
    }

    private static void addComponent(JPanel panel, JComponent component,
				     GridBagLayout gridbag,
				     GridBagConstraints c)
    {
	gridbag.setConstraints(component, c);
	panel.add(component);
    }

    static String decode(String value) {
	byte[] data = Base64.getDecoder().decode(value);
	ByteArrayInputStream is = new ByteArrayInputStream(data);
	StringBuilder sb = new StringBuilder();
	try {
	    CopyUtilities.copyStream(is, sb, UTF8);
	} catch (IOException eio) {}
	return sb.toString();
    }

    static String encode(String value) {
	ByteArrayOutputStream os = new ByteArrayOutputStream(value.length());
	OutputStreamWriter w = new OutputStreamWriter(os, UTF8);
	try {
	    w.write(value, 0, value.length());
	    w.flush();
	    w.close();
	} catch (IOException eio) {
	    throw new UnexpectedExceptionError(eio);
	}
	byte[] data = os.toByteArray();
	data = Base64.getEncoder().encode(data);
	return new String(data, UTF8);
    }

    static String decrypt(String value) {
	if (value == null) return EMPTY_STRING;
	byte[] data = Base64.getDecoder().decode(value);
	ProcessBuilder pb = new ProcessBuilder("gpg", "-d");
	pb.redirectError(ProcessBuilder.Redirect.DISCARD);
	try {
	    StringBuilder sb = new StringBuilder();
	    Process p = pb.start();
	    Thread thread = new Thread(()->{
		    try {
			CopyUtilities.copyStream(p.getInputStream(), sb, UTF8);
			p.waitFor();
		    } catch(Exception e) {
		    }
	    });
	    thread.start();
	    OutputStream os = p.getOutputStream();
	    os.write(data);
	    os.flush();
	    os.close();
	    thread.join();
	    if (p.exitValue() != 0) {
		System.err.println("gpg failed with exit code "
				   + p.exitValue());
		return EMPTY_STRING;
	    }
	    return sb.toString();
	} catch (Exception e) {
	    System.err.println("decryption failed: " + e.getMessage());
	    return null;
	}
    }

    static String encrypt(String value, String[] recipients) {
	if (value == null) return EMPTY_STRING;
	LinkedList<String> args = new LinkedList<>();
	args.add("gpg");
	args.add("-o");
	args.add("-");
	for (String recipient: recipients) {
	    args.add("-r");
	    args.add(recipient);
	}
	args.add("-e");
	ProcessBuilder pb = new ProcessBuilder(args);
	pb.redirectError(ProcessBuilder.Redirect.DISCARD);
	final StringBuilder sb = new StringBuilder(256);
	try {
	    Process p = pb.start();
	    Thread thread = new Thread(() -> {
		    try {
			InputStream is = p.getInputStream();
			byte data2[] = is.readAllBytes();
			p.waitFor();
			data2 = Base64.getEncoder().encode(data2);
			sb.append(new String(data2, UTF8));
		    } catch (Exception e) {
			e.printStackTrace();
		    }
	    });
	    thread.start();
	    OutputStream os = p.getOutputStream();
	    OutputStreamWriter w = new OutputStreamWriter(os, UTF8);
	    w.write(value);
	    w.flush();
	    w.close();
	    thread.join();
	    if (p.exitValue() != 0) {
		System.err.println("gpg -d exit code = " + p.exitValue());
		return null;
	    } else {
		return sb.toString();
	    }
	} catch (Exception e) {
	    e.printStackTrace();
	    return null;
	}
    }

    static String[] getRecipients() {
	ArrayList<String> list = new ArrayList<>();
	JPanel panel = new JPanel(new BorderLayout());
	JCheckBox cb = new JCheckBox("Add more keys");
	panel.add(cb, BorderLayout.NORTH);
	panel.add(new JLabel("next GPG/PGP UID"), BorderLayout.SOUTH);
	do {
	    cb.setSelected(false);
	    String next =
		JOptionPane.showInputDialog(frame, panel);
	    if (next != null) {
		next = next.trim();
		if (next.length() > 0) {
		    list.add(next);
		}
	    }
	} while (cb.isSelected());
	return list.toArray(new String[list.size()]);
    }

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final String EMPTY_STRING = "";

    static int selectedIndex = -1;
    static boolean addingRow = false;
    static boolean editingValTF = false;
    static Set<String> keySet = new HashSet<String>(32);
    static boolean needSave = false;

    static boolean doSave(boolean mode, JTable table) {
	Set<String> loop = checkLoops(table);
	if (loop.size() > 0) {
	    StringBuffer sb = new StringBuffer();
	    sb.append("Properties loop: ");
	    boolean first = true;
	    for (String key: loop) {
		if (first == false) {
		    sb.append("-->");
		} else {
		    first = false;
		}
		sb.append(key);
	    }
	    JOptionPane.showMessageDialog(frame, sb.toString(), "ECDB Error",
					  JOptionPane.ERROR_MESSAGE);
	    return false;
	}

	try {
	    if (mode || file == null) {
		File cdir = new File(System.getProperty("user.dir"))
		    .getCanonicalFile();
		JFileChooser chooser = new JFileChooser(cdir);
		FileNameExtensionFilter filter =
		    new FileNameExtensionFilter
		    ("ECDB Config", "ecdb");
		chooser.setFileFilter(filter);
		chooser.setSelectedFile(file);
		int status = chooser.showSaveDialog(frame);
		if (status == JFileChooser.APPROVE_OPTION) {
		    file = chooser.getSelectedFile();
		    String name = file.getName();
		    if (!(name.endsWith(".ecdb")
			  || name.endsWith(".ECDB"))) {
			file = new File(file.getParentFile(),
					name + ".ecdb");
		    }
		} else {
		    return false;
		}
	    }
	    if (!file.exists()) {
		save(file, table);
	    } else {
		File parent = file.getCanonicalFile().getParentFile();
		File tmp = File.createTempFile("ecdbtmp", ".ecdb",
					       parent);
		save(tmp, table);
		File backup = new File(file.getCanonicalPath() + "~");
		if (backup.exists()) backup.delete();
		file.renameTo(backup);
		tmp.renameTo(file);
	    }
	    needSave = false;
	} catch (IOException eio) {
	    JOptionPane.showMessageDialog(frame,
					  "Save to "
					  + file.toString()
					  + " failed: "
					  + eio.getMessage(),
					  "ECDB Error",
					  JOptionPane.ERROR_MESSAGE);
	    return false;
	}
	return true;
    }

    private static String chooseKey(HashSet<String> set) {
	for (String key: set) {
	    return key;
	}
	return null;
    }


    static final Pattern pattern =
	Pattern.compile(Pattern.quote("$(") + "([a-zA-Z0-9_]+([.]"
			+ "[a-zA-Z0-9_]+)*)"
			+ Pattern.quote(")"));

    private static Set<String> checkLoops(JTable table) {
	// do a topological sort using Kahn's algorithm
	TableModel model = table.getModel();
	int rowCount = model.getRowCount();
	HashMap<String,HashSet<String>> inmap =
	    new HashMap<String,HashSet<String>>(64);
	HashMap<String,HashSet<String>> outmap =
	    new HashMap<String,HashSet<String>>(64);
	Set<String> results = new LinkedHashSet<String>(64);
	LinkedList<String> terminals = new LinkedList<String>();
	Properties props = new Properties(64);

	for (int i = 0; i < rowCount; i++) {
	    String key = ((String)model.getValueAt(i, 0)).trim();
	    if (key.length() == 0) continue;
	    if (key.startsWith("-")) continue;
	    props.setProperty(key, (String)model.getValueAt(i, 1));
	    inmap.put(key, new HashSet<String>());
	    outmap.put(key, new HashSet<String>());
	}
	for (int i = 0; i < rowCount; i++) {
	    String key = ((String)model.getValueAt(i, 0)).trim();
	    if (key.length() == 0) continue;
	    if (key.startsWith("-")) continue;
	    HashSet<String>inmapLinks = inmap.get(key);
	    String value = props.getProperty(key);
	    if (value == null) continue;
	    Matcher matcher = pattern.matcher(value);
	    int index = 0;
	    while (matcher.find(index)) {
		int start = matcher.start();
		int end = matcher.end();
		String pkey = value.substring(start+2, end-1);
		String pval = props.getProperty(pkey);
		if (pval != null) {
		    Set<String> outmapLinks = outmap.get(pkey);
		    inmapLinks.add(pkey);
		    outmapLinks.add(key);
		}
		index = end;
	    }
	}
	for (Map.Entry<String,HashSet<String>> entry: inmap.entrySet()) {
	    if (entry.getValue().size() == 0) {
		terminals.add(entry.getKey());
	    }
	}
	while (terminals.size() > 0) {
	    String n = terminals.poll();
	    HashSet<String> outset = outmap.get(n);
	    for (String key: new ArrayList<String>(outset)) {
		outset.remove(key);
		HashSet<String> inset = inmap.get(key);
		inset.remove(n);
		if (inset.isEmpty()) {
		    terminals.add(key);
		}
	    }
	}
	for (Map.Entry<String,HashSet<String>> entry: inmap.entrySet()) {
	    HashSet<String> upstream = entry.getValue();
	    if (upstream.size() > 0) {
		String end = entry.getKey();
		results.add(end);
		String next = chooseKey(upstream);
		while (next != null) {
		    results.add(next);
		    if (next.equals(end)) break;
		    upstream = inmap.get(next);
		    next = chooseKey(upstream);
		}
	    }
	}
	return results;
    }

    static void save(File f, JTable table) throws IOException {
	FileOutputStream os = new FileOutputStream(f);
	Writer w = new OutputStreamWriter(os, UTF8);
	w = new BufferedWriter(w);
	PrintWriter out = new PrintWriter(w);
	out.println("#(!M.T application/x.ecdb-config)");
	TableModel model = table.getModel();
	int len = model.getRowCount();
	for (int i = 0; i < len; i++) {
	    String key = (String)model.getValueAt(i, 0);
	    if (key.startsWith("-")) continue;
	    String value = (String)model.getValueAt(i, 1);
	    out.format("%s=%s\n", key, value);
	}
	out.flush();
	out.close();
    }

    static final Set<String> reserved = new LinkedHashSet<String>(32);
    static int[] spacers  = null;
    static {
	int ind = 0;
	ArrayList<Integer> sl = new ArrayList<>(4);
	Collections.addAll(reserved, "type", "dbName", "dbPath",
			   "file.sql.xml");
			  
	sl.add(reserved.size() + (ind++));
	Collections.addAll(reserved,  "configAuth", "dbOwner");
	sl.add(reserved.size() + (ind++));
	Collections.addAll(reserved, "configRoles",
			   "ECADMIN", "ECOWNER", "ECUSER");
	sl.add(reserved.size() + (ind++));
	Collections.addAll(reserved, "ECSCHEMA");
	sl.add(reserved.size() + (ind++));
	Collections.addAll(reserved, "createURL", "openURL", "shutdownURL");
	sl.add(reserved.size() + (ind++));
	spacers = new int[sl.size()];
	int i = 0;
	for (Integer val: sl) {
	    spacers[i++] = val;
	}
    }

    static final int KLEN = reserved.size() + spacers.length;

    static void editConfig(File f) {
	System.out.println("editConfig: file = " + f);
	selectedIndex = -1;
	Properties defaults = defaultProperties();
	if (f != null) {
	    try {
		file = f;
		Reader r = new InputStreamReader(new FileInputStream(file),
						 UTF8);
		properties.load(r);
		r.close();
		for (String key: defaults.stringPropertyNames()) {
		    if (properties.getProperty(key) == null) {
			properties.setProperty(key, defaults.getProperty(key));
		    }
		}
	    } catch (IOException eio) {
		System.err.println(eio.getMessage());
		System.exit(1);
	    }
	} else  {
	    properties = defaults;
	    file = null;
	}
	SwingUtilities.invokeLater(() -> {
		Set<String> names = new HashSet<>(64);
		names.addAll(properties.stringPropertyNames());
		names.addAll(reserved);

		String[] keys1 = new String[names.size()];
		keys1 = names.toArray(keys1);
		for (int i = 0; i < keys1.length; i++) {
		    String key = keys1[i];
		    if (reserved.contains(key)) {
			keys1[i] = "";
		    }
		}
		String[] keys = new String[keys1.length + spacers.length]; 
		System.arraycopy(keys1, 0, keys, 0, keys1.length);
		for (int i = 0; i < spacers.length; i++) {
		    keys[keys1.length+i] = "";
		}
		Arrays.sort(keys);
		int kind = 0;
		int sind = 0;
		for (int i = 0; i < spacers.length; i++) {
		    System.out.print(" " + spacers[i]);
		}
		System.out.println();
		for (String key: reserved) {
		    keys[kind++] = key;
		    if (kind == spacers[sind]) {
			keys[kind++] = "-------------";
			sind++;
		    }
		}
		for (int i = 0; i < keys.length; i++) {
		    System.out.println(keys[i]);
		}
		/*
		keys[0] = "type";
		keys[1] = "dbName";
		keys[2] = "dbPath";
		keys[3] = "dbOwner";
		keys[4] = "configAuth";
		keys[5] = "-------------";
		keys[6] = "configRoles";
		keys[7] = "ECADMIN";
		keys[8] = "ECOWNER";
		keys[9] = "ECUSER";
		keys[10] = "-------------";
		keys[11] = "ECSCHEMA";
		keys[12] = "-------------";
		keys[13] = "createURL";
		keys[14] = "openURL";
		keys[15] = "shutdownURL";
		keys[16] = "-------------";
		*/
		/*
		for (int i = reserved.size(); i < keys1.length; i++) {
		    keys[i+spacers.length] = keys1[i];
		}
		*/
		Vector<String> columnIdents = new Vector<>(2);
		columnIdents.add("Property");
		columnIdents.add("Value");
		Vector<? extends Vector>data =
		    new Vector<Vector<String>>(keys.length);
		DefaultTableModel tm = new DefaultTableModel(data,
							     columnIdents);

		ListSelectionModel lsm = new DefaultListSelectionModel();
		lsm.addListSelectionListener((lsevent) -> {
			boolean clear = false;
			for (int i: spacers) {
			    if (lsm.isSelectedIndex(i)) {
				clear = true;
				break;
			    }
			}
			if (clear) {
			    lsm.clearSelection();
			}
		    });


		for (int i = 0; i < keys.length; i++) {
		    String key = keys[i];
		    String value = "";
		    if (!key.startsWith("-")) {
			value = properties.getProperty(key);
		    }
		    Vector<String>row = new Vector<>(2);
		    System.out.println("adding row with key=" + key
				       + ", value = " + value);
		    row.add(key);
		    row.add(value);
		    tm.addRow(row);
		}
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		JButton addButton = new JButton("Add Row");
		JButton editButton = new JButton("Edit Selected Row");
		JButton delButton = new JButton ("Delete Selected Row");
		JLabel keyLabel = new JLabel("Key: ");
		JTextField keyTF = new JTextField(52);
		JLabel valLabel = new JLabel("Value: ");
		JTextField valTF = new JTextField(52);
		keyTF.setEditable(false);
		valTF.setEditable(false);
		JTable table = new JTable() {
			public boolean isCellEditable(int row, int col) {
			    return false;
			}
		    };
		table.setModel(tm);
		table.setSelectionModel(lsm);
		lsm.addListSelectionListener((lsevent) -> {
			keyTF.setEditable(false);
			valTF.setEditable(false);
			keyTF.setText(EMPTY_STRING);
			valTF.setText(EMPTY_STRING);
			if (lsm.isSelectionEmpty()) {
			    editButton.setEnabled(false);
			    delButton.setEnabled(false);
			} else {
			    selectedIndex = lsm.getMinSelectionIndex();
			    editButton.setEnabled(true);
			    delButton.setEnabled(true);
			    boolean spacer = false;
			    for (int i: spacers) {
				if (selectedIndex == i) {
				    spacer = true;
				    break;
				}
			    }
			    if (spacer == false) {
				String key = (String)
				    tm.getValueAt(selectedIndex, 0);
				keyTF.setText(key);
				String value = (String)
				    tm.getValueAt(selectedIndex, 1);
				if (key.startsWith("base64.")) {
				    value = decode(value);
				} else if (key.startsWith("ebase64.")) {
				    value = decrypt(value);
				}
				valTF.setText(value);
			    }
			}
		    });
		addButton.addActionListener((e) -> {
			if (addingRow) {
			    String key = keyTF.getText().trim();
			    if (key.length() == 0) {
				valTF.setText(EMPTY_STRING);
				keyTF.setEditable(false);
				valTF.setEditable(false);
				table.setEnabled(true);
				addingRow = false;
				editButton.setEnabled(false);
				delButton.setEnabled(false);
				lsm.clearSelection();
				return;
			    } else if (keySet.contains(key)) {
				JOptionPane.showMessageDialog
				    (frame, "key in use", "Error",
				     JOptionPane.ERROR_MESSAGE);
				return;
			    }
			    keySet.add(key);
			    addButton.setText("Add Row");
			    keyLabel.setText("Key: ");
			    valLabel.setText("Value: ");
			    keyTF.setEditable(false);
			    valTF.setEditable(false);
			    table.setEnabled(true);
			    editButton.setEnabled(true);
			    delButton.setEnabled(true);
			    String value = valTF.getText().trim();
			    if (key.startsWith("base64.")) {
				value = encode(value);
			    } else if (key.startsWith("ebase64.")) {
				value = encrypt(value, getRecipients());
			    }
			    Vector<String> row = new Vector<>(2);
			    row.add(key);
			    row.add(value);
			    tm.addRow(row);
			    selectedIndex = table.getRowCount()-1;
			    lsm.setSelectionInterval(selectedIndex,
						     selectedIndex);
			    addingRow = false;
			    needSave = true;
			} else {
			    addingRow = true;
			    editButton.setEnabled(false);
			    delButton.setEnabled(false);
			    table.setEnabled(false);
			    keyLabel.setText("new Key: ");
			    valLabel.setText("new Value: ");
			    keyTF.setText("");
			    valTF.setText("");
			    keyTF.setEditable(true);
			    valTF.setEditable(true);
			    addButton.setText("Accept New Row");
			    keyTF.requestFocusInWindow();
			}
			
		    });
		editButton.addActionListener((e) -> {
			if (editingValTF) {
			    String value =valTF.getText(); 
			    if (keyTF.getText().startsWith("base64.")) {
				value = encode(value);
			    } else if (keyTF.getText().startsWith("ebase64.")) {
				value = encrypt(value, getRecipients());
			    }
			    tm.setValueAt(value, selectedIndex, 1);
			    editButton.setText("Edit Selected Row");
			    table.setEnabled(true);
			    delButton.setEnabled(true);
			    addButton.setEnabled(true);
			    editingValTF = false;
			    needSave = true;
			} else {
			    boolean spacer = false;
			    for (int i: spacers) {
				if (selectedIndex == i) {
				    spacer = true;
				    break;
				}
			    }
			    if (spacer == false) {
				valTF.setEditable(true);
				valTF.requestFocusInWindow();
				editingValTF = true;
				editButton.setText("Accept Value");
				table.setEnabled(false);
				delButton.setEnabled(false);
				addButton.setEnabled(false);
			    }
			}
		    });
		delButton.addActionListener((e) -> {
			needSave = true;
			if (selectedIndex >= KLEN) {
			    String key = keyTF.getText();
			    keySet.remove(key);
			    keyTF.setText(EMPTY_STRING);
			    valTF.setText(EMPTY_STRING);
			    tm.removeRow(selectedIndex);
			    if (selectedIndex < tm.getRowCount()) {
			 	lsm.setSelectionInterval(selectedIndex,
							 selectedIndex);
			    } else {
				lsm.clearSelection();
			    }
			} else {
			    valTF.setText(EMPTY_STRING);
			    tm.setValueAt(EMPTY_STRING, selectedIndex, 1);
			}
		    });
		    
		table.getTableHeader().setReorderingAllowed(false);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		JScrollPane sp = new JScrollPane(table);
		table.setFillsViewportHeight(true);
		int twidth = configColumn(table, 0,
					  "mmmmmmmmmmmmmmmmmm");
		twidth += configColumn(table, 1,
				       "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm");
		sp.setPreferredSize(new Dimension(twidth+10, 400));
		panel.add(sp, BorderLayout.CENTER);
		JPanel subpanel = new JPanel();
		GridBagLayout gridbag = new GridBagLayout();
		subpanel.setLayout(gridbag);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 8, 4, 8);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout());
		buttonPanel.add(addButton);
		buttonPanel.add(editButton);
		buttonPanel.add(delButton);
		c.gridwidth = GridBagConstraints.REMAINDER;
		c.anchor = GridBagConstraints.CENTER;
		addComponent(subpanel, buttonPanel, gridbag, c);

		c.anchor = GridBagConstraints.LINE_END;
		c.gridwidth = 1;
		addComponent(subpanel, keyLabel, gridbag, c);

		c.anchor = GridBagConstraints.LINE_START;
		c.gridwidth = GridBagConstraints.REMAINDER;
		addComponent(subpanel, keyTF, gridbag, c);

		c.anchor = GridBagConstraints.LINE_END;
		c.gridwidth = 1;
		addComponent(subpanel, valLabel, gridbag, c);

		c.anchor = GridBagConstraints.LINE_START;
		c.gridwidth = GridBagConstraints.REMAINDER;
		addComponent(subpanel, valTF, gridbag, c);

		JMenuBar menubar = new JMenuBar();
		JMenu fileMenu = new JMenu("File");
		fileMenu.setMnemonic(KeyEvent.VK_F);
		menubar.add(fileMenu);
		JMenuItem menuItem = new JMenuItem("Quit", KeyEvent.VK_Q);
		menuItem.setAccelerator(KeyStroke.getKeyStroke
					(KeyEvent.VK_Q,
					 InputEvent.CTRL_DOWN_MASK));
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
			    if (needSave) {
				switch (JOptionPane.showConfirmDialog
					(frame, "Save before exiting?",
					 "ECDB Configuration",
					 JOptionPane.OK_CANCEL_OPTION,
					 JOptionPane.QUESTION_MESSAGE)) {
				case JOptionPane.OK_OPTION:
				    System.exit(0);
				default:
				    return;
				}
			    } else {
				System.exit(0);
			    }
			}
		    });
		fileMenu.add(menuItem);
		menuItem = new JMenuItem("Save", KeyEvent.VK_S);
		menuItem.setAccelerator(KeyStroke.getKeyStroke
					(KeyEvent.VK_S,
					 InputEvent.CTRL_DOWN_MASK));
		menuItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
			    if (doSave(false, table)) {
				needSave = false;
			    }
			}
		    });
		fileMenu.add(menuItem);
		menuItem = new JMenuItem("Save As", KeyEvent.VK_A);
		menuItem.setAccelerator(KeyStroke.getKeyStroke
					(KeyEvent.VK_S,
					 (InputEvent.CTRL_DOWN_MASK
					  | InputEvent.SHIFT_DOWN_MASK)));
		menuItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
			    if (doSave(true, table)) {
				needSave = false;
			    }
			}
		    });
		fileMenu.add(menuItem);

		frame = new JFrame("ECDB Configuration File");
		frame.setIconImages(iconList);
		frame.setLayout(new BorderLayout());
		frame.setPreferredSize(new Dimension(800, 600));
		frame.setJMenuBar(menubar);
		frame.add(subpanel, BorderLayout.NORTH);
		frame.add(panel, BorderLayout.CENTER);
		frame.pack();
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
			    System.exit(0);
			}
		    });
		frame.setVisible(true);
	    });
    }
    

    static class ICalTransferable implements Transferable, ClipboardOwner {
	private ArrayList<File> list = new ArrayList<>();
	File dir = null;
	public ICalTransferable(Vector<byte[]> calendars) throws IOException {
	    dir = Files.createTempDirectory("ecdb").toFile();
	    dir.deleteOnExit();
	    dir.mkdirs();
	    int n = calendars.size();
	    int nn = n;
	    int digits = 0;
	    do {
		digits++;
		nn = nn/10;
	    } while (nn > 0);
	    String format = "event%0" + digits +"d.ics";
	    int i = 0;
	    for (byte[] calendar: calendars) {
		String fname = String.format(format, ++i);
		File f = new File(dir, fname);
		InputStream in = new ByteArrayInputStream(calendar);
		Path p = f.toPath();
		Files.copy(in, p);
		f.deleteOnExit();
		list.add(f); 
	    }
	}

	@Override
	public Object getTransferData(DataFlavor flavor)
	    throws UnsupportedFlavorException, IOException
	{
	    if (flavor == DataFlavor.javaFileListFlavor) {
		return list;
	    }
	    return new UnsupportedFlavorException(flavor);
	}

	private static final DataFlavor ourFlavors[] = {
	    DataFlavor.javaFileListFlavor
	};
	    

	@Override
	public DataFlavor[] getTransferDataFlavors() {
	    return ourFlavors;
	}

	@Override
	public boolean isDataFlavorSupported(DataFlavor flavor) {
	    for (DataFlavor f: ourFlavors) {
		if (f.equals(flavor)) return true;
	    }
	    return false;
	}

	@Override
	public void lostOwnership(Clipboard clipboard, Transferable t) {
	    if (dir != null) {
		for (File f: list) {
		    f.delete();
		}
		dir.delete();
	    }
	}
    }

    static class HeaderMapData {
	String header;
	String example;
	Class<?>clasz;
	HeaderMapData(String h, String e, Class<?> c) {
	    header = h; example = e; clasz = c;
	}
    }

    private static Map<String,HeaderMapData> hmap = new HashMap<>(64);
    static {
	HeaderMapData mapdata[] = {
	    new HeaderMapData("alarmTime", "mmmmmmmmmmmmm",
			      java.sql.Time.class),
	    new HeaderMapData("attendeeState", "mmmmmmmmmmm",
			      ECDB.AttendeeState.class),
	    new HeaderMapData("attendingPreEvent", "attendingPreEvent",
			      Boolean.class),
	    new HeaderMapData("carrier", "mmmmmmmmmmmmmmmmmmm",
			      ECDB.CarrierLabeledID.class),
	    new HeaderMapData("carrierID", "mmmmmm", Integer.class),
	    new HeaderMapData("carrierName", "mmmmmmmmmmmmmmmmmmm",
			      String.class),
	    new HeaderMapData("cellNumber", "mmmmmmmmmm", String.class),
	    new HeaderMapData("countryPrefix", "mmmm", String.class),
	    new HeaderMapData("description", "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
			     String.class),
	    new HeaderMapData("emailAddr", "mmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
			     String.class),
	    new HeaderMapData("endDate", "mmmmmmmmmmmmmm", java.sql.Date.class),
	    new HeaderMapData("endTime", "mmmmmmmn", java.sql.Time.class),
	    new HeaderMapData("eventID", "mmmmmm", Integer.class),
	    new HeaderMapData("event", "mmmmmmmmmmmmmmmm",
			      ECDB.EventLabeledID.class),
	    new HeaderMapData("eventTime", "mmmmmmmm", java.sql.Time.class),
	    new HeaderMapData("firstName", "mmmmmmmmmmmmmmm", String.class),
	    new HeaderMapData("LNF", "false", Boolean.class),
	    new HeaderMapData("forEmail", "false", Boolean.class),
	    new HeaderMapData("forPhone", "false", Boolean.class),
	    new HeaderMapData("idomain", "mmmmmmmmmmmmmmmmmmmmmmmmmmm",
			     String.class),
	    new HeaderMapData("instanceID", "mmmmm", Integer.class),
	    new HeaderMapData("instance",
			     "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
			      ECDB.InstanceLabeledID.class),
	    new HeaderMapData("label", "mmmmmmmmmmmmmmmm", String.class),
	    new HeaderMapData("lastName", "mmmmmmmmmmmmmmmm", String.class),
	    new HeaderMapData("location", "mmmmmmmmmmmmmmmmmmmmmmmmm",
			     ECDB.LocationLabeledID.class),
	    new HeaderMapData("locationID", "mmmmm", Integer.class),
	    new HeaderMapData("locationName", "mmmmmmmmmmmmmmmmmmmmmmmmm",
			      String.class),
	    new HeaderMapData("offset", "mmmmm", Integer.class),
	    new HeaderMapData("ownerID", "mmmmmm", Integer.class),
	    new HeaderMapData("owner", "mmmmmmmmmmmmmmmmmmmmmmm",
			      ECDB.OwnerLabeledID.class),
	    new HeaderMapData("preEventOffset", "mmmmm", Integer.class),
	    new HeaderMapData("preEventType", "mmmmmmmmmmmmmmmmmmm",
			     String.class),
	    new HeaderMapData("seriesID", "mmmmm", Integer.class),
	    new HeaderMapData("series", "mmmmmmmmmmm",
			      ECDB.SeriesLabeledID.class),
	    new HeaderMapData("startDate", "mmmmmmmmmmmmmmm",
			     java.sql.Date.class),
	    new HeaderMapData("startTime", "mmmmmmmmmm", java.sql.Time.class),
	    new HeaderMapData("status", "mmmmmmmmmmmmm", Enum.class),
	    new HeaderMapData("summary", "mmmmmmmmmmmmmmmmmmmmmmmm",
			     String.class),
	    new HeaderMapData("title", "mmmmmmmmmmm", String.class),
	    new HeaderMapData("attend", "false", Boolean.class),
	    new HeaderMapData("userID", "mmmmm", Integer.class),
	    new HeaderMapData("user", "mmmmmmmmmmmmmmmmmmmmmmmmmm",
			     ECDB.UserLabeledID.class),
	    new HeaderMapData("weekday", "false", Boolean.class),
	};
	for (HeaderMapData entry: mapdata) {
	    hmap.put(entry.header, entry);
	}
    }

    public static String getColExample(String columnName) {
	return hmap.get(columnName).example;
    }

    private static Class<?> getColClass(String columnName) {
	return hmap.get(columnName).clasz;
    }


    public static Vector<Vector<Object>>
	copyTMVector(Vector<Vector<Object>> vector)
    {
	if (vector == null) return null;
	Vector<Vector<Object>> result =
	    new Vector<Vector<Object>>(vector.size());
	for (Vector<Object>subvector: vector) {
	    Vector<Object> v = new Vector<>(subvector.size());
	    for (Object obj: subvector) {
		v.add(obj);
	    }
	}
	return result;
    }

    static class CellInfo {
	int i, j, n;
	Object value;
	CellInfo(int i, int j, int n, Object obj) {
	    this.i = i;
	    this.j = j;
	    this.n = n;
	    this.value = obj;
	}
	int getFlatIndex() {
	    return getFlatInd(i, j, n);
	}
	static int getFlatInd(int i, int j, int n) {
	    return i*n + j;
	}
    }

    static class OurJPanel extends JPanel {
	int n;
	HashMap<Integer,CellInfo> cellmap = new HashMap<>();
	int ri = -1;
	int ci = -1;
	Object saved = null;
	Integer[] uneditableCols = null;
	// used for cases where the table is filtered by user, etc..
	ECDB.UserLabeledID ulid = null;
	ECDB.OwnerLabeledID olid = null;
	ECDB.LocationLabeledID llid = null;

    }

    public static void apply(OurJPanel panel, ECDB ecdb, ECDB.Table table,
			     Vector<String> columnHeadings,
			     Vector<Vector<Object>> rows)
	throws SQLException
    {
	try (Connection conn = ecdb.getConnection()) {
	    try {
		conn.setAutoCommit(false);
		for (CellInfo cellinfo: panel.cellmap.values()) {
		    int ri = cellinfo.i;
		    int ci = cellinfo.j;
		    Vector<Object> row = rows.get(ri);
		    int carrierID = -1, ownerID = -1, locationID = -1,
			eventID = -1, instanceID = -1, offset = -1,
			seriesID = -1, userID = -1;
		    java.sql.Time alarmTime = null, startTime = null,
			endTime = null, eventTime = null;
		    java.sql.Date startDate = null, endDate = null;
		    Boolean lnf = null, forEmail = null, forPhone = null,
			attendingPreEvent = null, attend = null,
			weekday = null;
		    ECDB.CarrierLabeledID carrier = null;
		    ECDB.EventLabeledID event = null;
		    ECDB.InstanceLabeledID instance = null;
		    ECDB.LocationLabeledID location = null;
		    ECDB.OwnerLabeledID owner = null;
		    ECDB.SeriesLabeledID series = null;
		    ECDB.UserStatus userStatus = null;
		    ECDB.CalendarStatus calStatus = null;
		    ECDB.AttendeeState attendeeState = null;
		    String carrierName = null, cellNumber = null,
			countryPrefix = null, description = null,
			emailAddr = null, firstName = null, lastName = null,
			locationName = null, idomain = null, label = null,
			preEventType = null, summary = null, state = null,
			title = null, user = null;
		    Object obj = row.get(ci);
		    if (obj instanceof String) {
			obj = ((String)obj).trim();
		    }
		    switch (table) {
		    case CARRIER:
			carrierID = (Integer)row.get(0);
			carrierName = (String)obj;
			ecdb.setCarrier(conn, carrierID, carrierName, false);
			break;
		    case CARRIER_MAP:
			countryPrefix = (String) row.get(0);
			carrierID = ((ECDB.CarrierLabeledID)row.get(1)).getID();
			if (carrierID != 1) {
			    // carrierID 1 'OTHER' is reserved.
			    idomain = (String)obj;
			    ecdb.setCarrierMapping(conn, countryPrefix,
						   carrierID, idomain, false);
			}
			break;
		    case USER:
			userID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    firstName = (String) obj;
			    if (firstName == null) firstName = "";
			    break;
			case 2:
			    lastName =  (String) obj;
			    if (lastName == null) lastName = "";
			    break;
			case 3:
			    lnf =  (Boolean) obj;
			    break;
			case 4:
			    title =  (String) obj;
			    if (title == null) title = "";
			    break;
			case 5:
			    countryPrefix =  (String) obj;
			    if (countryPrefix == null) countryPrefix = "";
			    break;
			case 6:
			    emailAddr = (String) obj;
			    if (emailAddr == null) emailAddr = "";
			    break;
			case 7:
			    cellNumber = (String) obj;
			    if (cellNumber == null) {
				cellNumber = "";
			    }
			    break;
			case 8:
			    if (obj != null) {
				carrierID =
				    ((ECDB.CarrierLabeledID)obj).getID();
			    }
			    break;
			case 9:
			    userStatus = (ECDB.UserStatus)obj;
			    break;
			}
			ecdb.setUserInfo(conn, userID, firstName, lastName,
					 lnf, title, emailAddr, countryPrefix,
					 cellNumber, carrierID,
					 userStatus.toString(), false);
			break;
		    case OWNER:
			ownerID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    label = (String) obj;
			    break;
			case 2:
			    summary = (String) obj;
			    break;
			case 3:
			    idomain = (String) obj;
			    break;
			}
			ecdb.setOwner(conn, ownerID, label, summary, idomain,
				      false);
			break;
		    case PRE_EVENT_DEFAULT:
			userID = ((ECDB.UserLabeledID)row.get(0)).getID();
			ownerID = ((ECDB.OwnerLabeledID)row.get(1)).getID();
			attend = (Boolean)obj;
			ecdb.setPreEventDefault(conn, userID, ownerID,
						attend, false);
		    case LOCATION:
			locationID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    label = (String)obj;
			    break;
			case 2:
			    locationName = (String)obj;
			    break;
			}
			ecdb.setLocation(conn, locationID, label,
					 locationName, false);
			break;
		    case FIRST_ALARM:
			userID = ((ECDB.UserLabeledID)row.get(0)).getID();
			ownerID =((ECDB.OwnerLabeledID)row.get(1)).getID();
			locationID =
			    ((ECDB.LocationLabeledID)row.get(2)).getID();
			eventTime = (java.sql.Time)row.get(3);
			weekday = (Boolean)row.get(4);
			switch (ci) {
			case 5:
			    alarmTime = (java.sql.Time)obj;
			    break;
			case 6:
			    forEmail = (Boolean)obj;
			    break;
			case 7:
			    forPhone = (Boolean)obj;
			    break;
			}
			ecdb.setFirstAlarm(conn, userID, ownerID, locationID,
					   eventTime, weekday, alarmTime,
					   forEmail, forPhone, false);
			break;
		    case SECOND_ALARM:
			userID = ((ECDB.UserLabeledID)row.get(0)).getID();
			ownerID =((ECDB.OwnerLabeledID)row.get(1)).getID();
			locationID =
			    ((ECDB.LocationLabeledID)row.get(2)).getID();
			switch (ci) {
			case 3:
			    offset = (Integer) obj;
			    break;
			case 4:
			    forEmail = (Boolean)obj;
			    break;
			case 5:
			    forPhone = (Boolean) obj;
			    break;
			}
			ecdb.setSecondAlarm(conn, userID, ownerID, locationID,
					    offset, forEmail, forPhone, false);
			break;
		    case EVENT:
			eventID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    ownerID = ((ECDB.OwnerLabeledID)obj).getID();
			    break;
			case 2:
			    label = (String)obj;
			    break;
			case 3:
			    description = (String)obj;
			    break;
			}
			ecdb.setEvent(conn, userID, ownerID, label, description,
				      false);
			break;
		    case INSTANCE:
			instanceID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    eventID = ((ECDB.EventLabeledID)obj).getID();
			    break;
			case 2:
			    locationID = ((ECDB.LocationLabeledID)obj).getID();
			    break;
			case 3:
			    preEventType = (String)obj;
			    break;
			case 4:
			    offset = (Integer)obj;
			    break;
			case 5:
			    startDate = (java.sql.Date)obj;
			    break;
			case 6:
			    startTime = (java.sql.Time)obj;
			    break;
			case 7:
			    endDate = (java.sql.Date)obj;
			    break;
			case 8:
			    endTime = (java.sql.Time)obj;
			    break;
			case 9:
			    calStatus = (ECDB.CalendarStatus)obj;
			    break;
			}
			ecdb.setEventInstance(conn, instanceID, eventID,
					      locationID, preEventType,
					      offset, startDate, startTime,
					      endDate, endTime,
					      ((calStatus == null)? "":
					       calStatus.toString()), false);
			break;
		    case SERIES:
			seriesID = (Integer)row.get(0);
			switch (ci) {
			case 1:
			    ownerID = ((ECDB.OwnerLabeledID)obj).getID();
			    break;
			case 2:
			    label = (String)obj;
			    break;
			}
			ecdb.setSeries(conn, seriesID, ownerID, label, false);
			break;
		    case SERIES_INSTANCE:
			// no fields that can be edited.
			break;
		    case ATTENDEE:
			userID = ((ECDB.UserLabeledID)row.get(0)).getID();
			instanceID =
			    ((ECDB.InstanceLabeledID)row.get(1)).getID();
			switch (ci) {
			case 2:
			    attendeeState = (ECDB.AttendeeState)obj;
			    state = (attendeeState == null)? "":
				attendeeState.toString();
			    break;
			case 3:
			    attendingPreEvent = (Boolean)obj;
			    break;
			case 4:
			    seriesID = ((ECDB.SeriesLabeledID)obj).getID();
			    break;
			}
			ecdb.setAttendee(conn, userID, instanceID, state,
					 seriesID, false);
		    }
		}
		conn.commit();
	    } catch (SQLException e) {
		try {
		    conn.rollback();
		} catch (SQLException ee) {
		}
		throw e;
	    } finally {
		conn.setAutoCommit(true);
	    }
	}
    }

    public static void cancel(OurJPanel panel, JTable table)
    {
	for (CellInfo cellInfo: panel.cellmap.values()) {
	    System.out.format("restoring (%d, %d) to %s\n",
			      cellInfo.i, cellInfo.j, cellInfo.value);
	    table.setValueAt(cellInfo.value, cellInfo.i, cellInfo.j);
	}
	panel.cellmap.clear();
    }

    private static String  getInputTablePaneTitle(OurJPanel panel,
						  ECDB.Table table)
    {
	switch (table) {
	case CARRIER: return "ECDB: Add Carriers";
	case CARRIER_MAP: return "ECDB: Add to Carrier Map";
	case USER: return "ECDB: Add  Users";
	case OWNER: return "ECDB: Add Owners ";
	case PRE_EVENT_DEFAULT: return "ECDB: Add Pre-Event Defaults for "
		+ panel.ulid;
	case LOCATION: return "ECDB: Add Locations ";
	case FIRST_ALARM: return "ECDB: Add to First-Alarm Table for "
		+ panel.ulid;
	case SECOND_ALARM: return "ECDB: Add to Second-Alarm Table for "
		+ panel.ulid;
	case EVENT: return "ECDB: Add Events";
	case INSTANCE: return "ECDB: Add Instances ";
	case SERIES: return "ECDB: Add Series";
	case SERIES_INSTANCE: return "ECDB: Add Series Instances";
	case ATTENDEE: return "ECDB: Add Attendees (user = "
		+ panel.ulid + ")";
	default:
	    throw new UnexpectedExceptionError();
	}
    }

    private static boolean emptyRow(InputTablePane ipane, int row) {
	for (int col = 0; col < ipane.getColumnCount(); col++) {
	    Object val = ipane.getValueAt(row, col);
	    if (val != null) {
		if (val instanceof String) {
		    String s = (String) val;
		    s = s.trim();
		    if (s.length() != 0) return false;
		} else {
		    return false;
		}
	    }
	}
	return true;
    }

    private static void addRows(OurJPanel parent,
			       ECDB ecdb, ECDB.Table table, JTable jtable,
			       String[] columnHeadings,
			       Vector<Vector<Object>> rows)
	throws SQLException
    {
	int colSpecLength = columnHeadings.length;
	Integer[] uneditableCols = parent.uneditableCols;
	colSpecLength -= uneditableCols.length;
	/*
	switch (table) {
	case FIRST_ALARM:
	case SECOND_ALARM:
	    if (parent.olid != null) {
		colSpecLength--;
	    }
	    if (parent.llid != null) {
		colSpecLength--;
	    }
	case CARRIER:
	case USER:
	case OWNER:
	case PRE_EVENT_DEFAULT:
	case LOCATION:
	case EVENT:
	case INSTANCE:
	case SERIES:
	    colSpecLength--;
	    colOffset++;
	    break;
	}
	*/
	InputTablePane.ColSpec[] colSpec =
	    new InputTablePane.ColSpec[colSpecLength];

	int colOffset = 0;
	int j = 0;
	for (int i = 0; i < colSpecLength; i++) {
	    int ii = i + colOffset;
	    if (j < uneditableCols.length && ii == uneditableCols[j]) {
		switch (table) {
		default:
		    j++;
		    colOffset++;
		    i--;
		    continue;
		}
	    }
	    String heading = columnHeadings[ii];
	    /*
	    switch (table) {
	    case FIRST_ALARM:
	    case SECOND_ALARM:
		if ((heading.equals("owner") && parent.olid != null)
		    || (heading.equals("location") && parent.llid != null)) {
		    System.out.println("skipping "  + heading);
		    i--;

		    colOffset++;
		    continue;
		}
	    }
	    */
	    Class<?>clasz = jtable.getColumnClass(ii);
	    String example = getColExample(heading);
	    TableCellRenderer tcr = jtable.getDefaultRenderer(clasz);
	    TableCellEditor tce = jtable.getDefaultEditor(clasz);
	    colSpec[i] = new InputTablePane.ColSpec(heading, example, clasz,
						    tcr, tce);
	}
	String title = getInputTablePaneTitle(parent, table);
	InputTablePane ipane = new InputTablePane(colSpec, 15,
						  true, true, true);
	for (;;) {
	    if (InputTablePane .showDialog(parent, title, ipane)
		!= JOptionPane.OK_OPTION) {
		return;
	    }
	    DefaultTableModel tm = (DefaultTableModel)jtable.getModel();
	    try (Connection conn = ecdb.getConnection()) {
		boolean commit = false;
		int rowcount = 0;
		Vector<Vector<Object>> nrows =
		    new Vector<>(ipane.getRowCount());
		try {
		    String sarray[];
		    switch (table) {
		    case CARRIER:
			sarray = new String[ipane.getRowCount()];
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    sarray[i] = (String)ipane.getValueAt(i, 0);
			}
			ecdb.addCarrier(conn, sarray);
			for (String s: sarray) {
			    if (s == null) continue;
			    s = s.trim().replaceAll("\\s\\s+", " ")
				.toUpperCase();
			    if (s.length() == 0) continue;
			    Vector<Object>row = new Vector<>(2);
			    row.add(ecdb.findCarrier(conn, s));
			    row.add(s);
			    nrows.add(row);
			}
			break;
		    case CARRIER_MAP:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    String cp = (String)ipane.getValueAt(i, 0);
			    ECDB.CarrierLabeledID cid = (ECDB.CarrierLabeledID)
				ipane.getValueAt(i, 1);
			    String idom = (String)ipane.getValueAt(i, 2);
			    if (idom == null) continue;
			    idom = idom.trim();
			    if (idom.length() == 0) continue;
			    rowcount++;
			    ecdb.setCarrierMapping(conn, cp, cid.getID(), idom,
						   false);
			    Vector<Object> row = new Vector<>(3);
			    row.add(cp);
			    row.add(cid);
			    row.add(idom);
			    nrows.add(row);
			}
			break;
		    case USER:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    String firstName = (String) ipane.getValueAt(i, 0);
			    String lastName = (String) ipane.getValueAt(i, 1);
			    Boolean lnf = (Boolean) ipane.getValueAt(i,2);
			    if (lnf == null) lnf = false;
			    String utitle = (String)ipane.getValueAt(i,3);
			    String emailAddr = (String) ipane.getValueAt(i,4);
			    String cp = (String) ipane.getValueAt(i,5);
			    String cell = (String) ipane.getValueAt(i,6);
			    ECDB.CarrierLabeledID carrier =
				(ECDB.CarrierLabeledID)ipane.getValueAt(i,7);
			    ECDB.UserStatus status = (ECDB.UserStatus)
				 ipane.getValueAt(i,8);
			    ecdb.addUserInfo(conn, firstName, lastName,
					     lnf, utitle, emailAddr,
					     cp, cell, carrier.getID(),
					     false);
			    Vector<Object> row = new Vector<>(10);
			    String phoneno = (cp == null
					      || cp.trim().length() == 0)?
				cell: ("+" + cp + cell);
			    int userID  = (emailAddr != null
				       && emailAddr.trim().length() > 0)?
				ecdb.findUserInfo(conn, emailAddr):
				ecdb.findUserInfo(conn, phoneno);
			    row.add(userID);
			    row.add(firstName); row.add(lastName);
			    row.add(lnf); row.add(utitle);
			    row.add(emailAddr); row.add(cp); row.add(cell);
			    row.add(carrier); row.add(status);
			    nrows.add(row);
			}
			break;
		    case OWNER:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    String label = (String)ipane.getValueAt(i,0);
			    String summary =  (String)ipane.getValueAt(i,1);
			    String idomain =  (String)ipane.getValueAt(i,2);
			    ecdb.addOwner(conn, label, summary, idomain, false);
			    Vector<Object> row = new Vector<>(4);
			    int ownerID = ecdb.findOwner(conn, label);
			    row.add(ownerID); row.add(label);
			    row.add(summary); row.add(idomain);
			    nrows.add(row);
			}
			break;
		    case PRE_EVENT_DEFAULT:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    int userID = parent.ulid.getID();
			    ECDB.OwnerLabeledID ownerLID = (ECDB.OwnerLabeledID)
				ipane.getValueAt(i, 0);
			    Boolean ped = (Boolean)ipane.getValueAt(i, 1);
			    if (ped == null) ped = Boolean.FALSE;
			    ecdb.addPreEventDefault(conn, userID,
						    ownerLID.getID(),
						    ped, false);
			    Vector<Object> row = new Vector<>(3);
			    row.add(parent.ulid);
			    row.add(ownerLID);
			    row.add(ped);
			    nrows.add(row);
			}
			break;
		    case LOCATION:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    String label = (String)ipane.getValueAt(i, 0);
			    if (label != null) {
				label = label.trim()
				    .replaceAll("\\s+\\s*", " ").toUpperCase();
			    }
			    String locationName = (String)
				ipane.getValueAt(i, 1);
			    ecdb.addLocation(conn, label, locationName, false);
			    int locationID = ecdb.findLocation(conn, label);
			    Vector<Object> row = new Vector<>(3);
			    row.add(locationID);
			    row.add(label);
			    row.add(locationName);
			    nrows.add(row);
			}
			break;
		    case FIRST_ALARM:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    int ind = 0;
			    ECDB.OwnerLabeledID olid = (parent.olid != null)?
				parent.olid:
				(ECDB.OwnerLabeledID)ipane.getValueAt(i, ind++);
			    ECDB.LocationLabeledID llid = (parent.llid != null)?
				parent.llid:
				(ECDB.LocationLabeledID)ipane.getValueAt(i,
									 ind++);
			    java.sql.Time eventTime =
				(java.sql.Time)ipane.getValueAt(i, ind++);
			    Boolean weekday = (Boolean)ipane.getValueAt(i,
									ind++);
			    if (weekday == null) weekday = false;
			    java.sql.Time alarmTime =
				(java.sql.Time)ipane.getValueAt(i, ind++);
			    Boolean forEmail = (Boolean)ipane.getValueAt(i,
									 ind++);
			    if (forEmail == null) forEmail = Boolean.FALSE;
			    Boolean forPhone = (Boolean)ipane.getValueAt(i,
									 ind++);
			    if (forPhone == null) forPhone = Boolean.FALSE;
			    int userID = (parent.ulid != null)?
				parent.ulid.getID(): -1;
			    ecdb.addFirstAlarm(conn, userID, olid.getID(),
					       llid.getID(), eventTime, weekday,
					       alarmTime, forEmail, forPhone,
					       false);
			    ECDB.UserLabeledID ulid =
				ecdb.getUserLabeledID(conn, userID);
			    Vector<Object> row = new Vector<>(8);
			    row.add(ulid); row.add(olid); row.add(llid);
			    row.add(eventTime); row.add(weekday);
			    row.add(alarmTime); row.add(forEmail);
			    row.add(forPhone);
			    nrows.add(row);
			}
			break;
		    case SECOND_ALARM:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    int ind = 0;
			    ECDB.OwnerLabeledID olid = (parent.olid != null)?
				parent.olid:
				(ECDB.OwnerLabeledID)ipane.getValueAt(i, ind++);
			    ECDB.LocationLabeledID llid = (parent.llid != null)?
				parent.llid:
				(ECDB.LocationLabeledID)ipane.getValueAt(i,
									 ind++);
			    Integer offset = (Integer)ipane.getValueAt(i,ind++);
			    if (offset == null) offset = 0;
			    Boolean forEmail = (Boolean)ipane.getValueAt(i,
									 ind++);
			    if (forEmail == null) forEmail = Boolean.FALSE;
			    Boolean forPhone = (Boolean)ipane.getValueAt(i,
									 ind++);
			    if (forPhone == null) forPhone = Boolean.FALSE;
			    int userID = (parent.ulid != null)?
				parent.ulid.getID(): -1;
			    ecdb.addSecondAlarm(conn, userID, olid.getID(),
						llid.getID(), offset,
						forEmail, forPhone, false);
			    Vector<Object> row = new Vector<>(6);
			    row.add(parent.ulid); row.add(olid); row.add(llid);
			    row.add(offset); row.add(forEmail);
			    row.add(forPhone);
			    nrows.add(row);
			}
			break;
		    case EVENT:
			commit = true;
			conn.setAutoCommit(false);
			for (int i = 0; i < ipane.getRowCount(); i++) {
			    if (emptyRow(ipane, i)) continue;
			    int ind = 0;
			    ECDB.OwnerLabeledID olid = (parent.olid != null)?
				parent.olid: (ECDB.OwnerLabeledID)
				ipane.getValueAt(i, ind++);
			    String label = (String)ipane.getValueAt(i,ind++);
			    if (label != null) {
				label = label.trim()
				    .replaceAll("\\s+\\s*", " ").toUpperCase();
				if (label.length() == 0) label = null;
			    }
			    String description = (String)
				ipane.getValueAt(i,ind++);
			    if  (description != null) {
				description = description.trim()
				    .replaceAll("\\s+\\s*", " ");
				if (description.length() == 0)
				    description = null;
			    }
			    ecdb.addEvent(conn, olid.getID(), label,
					  description);
			    Vector<Object> row = new Vector<>(4);
			    int eventID =
				ecdb.findEvent(conn, olid.getID(), label);
			    row.add(eventID); row.add(olid);
			    row.add(label); row.add(description); 
			    nrows.add(row);
			}
			break;
		    case INSTANCE:
			break;
		    case SERIES:
			break;
		    case SERIES_INSTANCE:
			break;
		    case ATTENDEE:
			break;
		    }
		    if (commit) conn.commit();
		    for (Vector<Object> row: nrows) {
			tm.addRow(row);
		    }
		} catch (IllegalArgumentException iae) {
		    System.out.println(iae.getMessage());
		    if (commit) {
			try {
			    conn.rollback();
			} catch (SQLException sqle2) {
			}
		    }
		    continue;
		} catch (SQLException sqle) {
		    System.out.println(sqle.getMessage());
		    if (commit) {
			try {
			    conn.rollback();
			} catch (SQLException sqle2) {
			}
		    }
		    continue;
		}
	    }
	    break;
	}
    }

    public static void delete(ECDB ecdb, ECDB.Table table, JTable jtable,
			     Vector<Vector<Object>> rows)
	throws SQLException
    {
	int ri = jtable.getSelectedRow();
	if (ri == -1) return;
	Vector<Object> row = rows.get(ri);
	try (Connection conn = ecdb.getConnection()) {
	    int id = -1;
	    int id2 = -1;
	    int id3 = -1;
	    int[] iarray = new int[1];
	    String s = null;
	    boolean b = false;
	    java.sql.Time time = null;
	    switch (table) {
	    case CARRIER:
		id = (Integer)row.get(0);
		ecdb.deleteCarrier(conn, id);
		break;
	    case CARRIER_MAP:
		s = (String)row.get(0);
		id = ((ECDB.CarrierLabeledID)row.get(1)).getID();
		ecdb.setCarrierMapping(conn, s, id, "");
		break;
	    case USER:
		iarray[0] = (Integer)row.get(0);
		ecdb.deleteUserInfo(conn, iarray);
		break;
	    case OWNER:
		id = (Integer)row.get(0);
		ecdb.deleteOwner(conn, id);
		break;
	    case PRE_EVENT_DEFAULT:
		id = ((ECDB.UserLabeledID)row.get(0)).getID();
		id2 = ((ECDB.OwnerLabeledID)row.get(1)).getID();
		ecdb.deletePreEventDefault(conn, id, id2);
		break;
	    case LOCATION:
		id = (Integer)row.get(0);
		ecdb.deleteLocation(conn, id);
		break;
	    case FIRST_ALARM:
		id = ((ECDB.UserLabeledID)row.get(0)).getID();
		id2 = ((ECDB.OwnerLabeledID)row.get(1)).getID();
		id3 = ((ECDB.LocationLabeledID)row.get(2)).getID();
		time = (java.sql.Time)row.get(3);
		b = (Boolean)row.get(4);
		ecdb.deleteFirstAlarm(conn, id, id2, id3, time, b);
		break;
	    case SECOND_ALARM:
		id = ((ECDB.UserLabeledID)row.get(0)).getID();
		id2 = ((ECDB.OwnerLabeledID)row.get(1)).getID();
		id3 = ((ECDB.LocationLabeledID)row.get(2)).getID();
		ecdb.deleteSecondAlarm(conn, id, id2, id3);
		break;
	    case EVENT:
		id = (Integer)row.get(0);
		ecdb.deleteEvent(conn, id);
		break;
	    case INSTANCE:
		id = (Integer)row.get(0);
		ecdb.setEventInstance(conn, id, -1, -1, null, -1,
				      null, null, null, null,
				      "CANCELLED");
		jtable.clearSelection();
		return;
	    case SERIES:
		id = (Integer)row.get(0);
		ecdb.deleteSeries(conn, id);
		break;
	    case SERIES_INSTANCE:
		id = ((ECDB.SeriesLabeledID)row.get(0)).getID();
		id2 = ((ECDB.InstanceLabeledID)row.get(1)).getID();
		ecdb.deleteSeriesInstance(conn, id, id2);
		break;
	    case ATTENDEE:
		id = ((ECDB.UserLabeledID)row.get(0)).getID();
		id2 = ((ECDB.InstanceLabeledID)row.get(1)).getID();
		ecdb.deleteAttendee(conn, id, id2, null, -1, true);
		break;
	    }
	}
	DefaultTableModel tm = (DefaultTableModel)(jtable.getModel());
	tm.removeRow(ri);
	jtable.clearSelection();
    }

    private static EnumSet<ECDB.Table> indexSet =
	EnumSet.of(ECDB.Table.CARRIER, ECDB.Table.USER, ECDB.Table.OWNER,
		   ECDB.Table.LOCATION, ECDB.Table.EVENT,ECDB.Table.INSTANCE,
		   ECDB.Table.SERIES);


    static class TimeRenderer extends DefaultTableCellRenderer {
	private static DateTimeFormatter tf =
	    DateTimeFormatter.ofPattern("hh:mm a");
	public TimeRenderer() {super();}
	public void setValue(Object value) {
	    java.sql.Time time = (value == null)? null: (java.sql.Time) value;
	    String text = (time == null)? "":
		time.toLocalTime().format(tf);
	    setText(text);
	}
    }

    static class DateRenderer extends DefaultTableCellRenderer {
	private static DateTimeFormatter df =
	    DateTimeFormatter.ofPattern("MM/dd/YYYY");
	public DateRenderer() {super();}
	public void setValue(Object value) {
	    java.sql.Date date = (value == null)? null: (java.sql.Date) value;
	    String text = (date == null)? "":
		date.toLocalDate().format(df);
	    setText(text);
	}
    }


    private static JComboBox<ECDB.UserStatus> getUserStatusCB() {
	return new JComboBox<ECDB.UserStatus>(ECDB.UserStatus.values());
    }

    private static JComboBox<ECDB.CalendarStatus> getCalendarStatusCB() {
	return new JComboBox<ECDB.CalendarStatus>(ECDB.CalendarStatus.values());
    }

    private static JComboBox<ECDB.AttendeeState> getAttendeeStateCB() {
	return new JComboBox<ECDB.AttendeeState>(ECDB.AttendeeState.values());
    }

    private static final DefaultTableCellRenderer
	tcr = new DefaultTableCellRenderer();
    static {
	tcr.setBackground(new Color(255,255, 225));
    }

    private static String[] emptyString = new String[0];


    public static OurJPanel createTablePanel(final ECDB ecdb,
					     final ECDB.Table table,
					     int ownerID,
					     int eventID,
					     int locationID,
					     Integer[] uneditableCols,
					     Vector<String> columnHeadings,
					     Vector<Vector<Object>> rows)
    {
	// following needed so no forward reference issues
	JCheckBox allowEditingCheckBox = (table == ECDB.Table.SERIES_INSTANCE)?
	    null: new JCheckBox("Allow Editing");
	JButton applyButton = new JButton("Apply");
	JButton cancelButton = new JButton("Cancel Changes");
	JButton addButton = new JButton("Add Rows");
	JButton deleteButton = new JButton("Delete Row");
	

	OurJPanel panel = new OurJPanel();
	JTable jtable = new JTable() {
		// int ri = -1, ci = -1;
		// Object saved = null;
		public boolean editCellAt(int row, int column, EventObject eo) {
		    boolean editing = super.editCellAt(row, column, eo);
		    if (editing) {
			panel.ri = row; panel.ci = column;
			panel.saved = getValueAt(panel.ri, panel.ci);
		    } else {
			panel.ri = -1; panel.ci = -1;
			panel.saved = null;
		    }
		    return editing;
		}
		public void editingStopped(ChangeEvent ce) {
		    super.editingStopped(ce);
		    if (panel.ri != -1 && panel.ci != -1) {
			int fi = CellInfo.getFlatInd(panel.ri, panel.ci,
						     panel.n);
			if (!panel.cellmap.containsKey(fi)) {
			    CellInfo cinf = new CellInfo(panel.ri, panel.ci,
							 panel.n, panel.saved);
			    panel.cellmap.put(fi, cinf);
			    panel.ri = -1; panel.ci = -1; panel.saved = null;
			}
		    }
		}
		public void editingCanceled(ChangeEvent  ce) {
		    super.editingCanceled(ce);
		    if (panel.ri != -1 && panel.ci != -1) {
			int fi = CellInfo.getFlatInd(panel.ri, panel.ci,
						     panel.n);
			if (!panel.cellmap.containsKey(fi)) {
			    CellInfo cinf = new CellInfo(panel.ri, panel.ci,
							 panel.n, panel.saved);
			    panel.cellmap.put(fi, cinf);
			    panel.ri = -1; panel.ci = -1; panel.saved = null;
			}
		    }
		}
	    };
	jtable.setDefaultRenderer(java.sql.Time.class, new TimeRenderer());
	jtable.setDefaultRenderer(java.sql.Date.class, new DateRenderer());
	jtable.setDefaultEditor(java.sql.Time.class, new TimeEditor());
	jtable.setDefaultEditor(java.sql.Date.class, new DateEditor());
	try (Connection conn = ecdb.getConnection()) {
	    jtable.setDefaultEditor(ECDB.CarrierLabeledID.class,
				    new CarrierEditor(ecdb, conn));
	    jtable.setDefaultEditor(ECDB.LocationLabeledID.class,
				    new LocationEditor(ecdb, conn));
	    jtable.setDefaultEditor(ECDB.OwnerLabeledID.class,
				    new OwnerEditor(ecdb, conn));
	    jtable.setDefaultEditor(ECDB.EventLabeledID.class,
				    new EventEditor(ecdb, conn, ownerID));
	    jtable.setDefaultEditor(ECDB.InstanceLabeledID.class,
				    new InstanceEditor(ecdb, conn,
						       ownerID,
						       eventID,
						       locationID));
	    jtable.setDefaultEditor(ECDB.SeriesLabeledID.class,
				    new SeriesEditor(ecdb, conn, ownerID));
	} catch (SQLException e) {
	    e.printStackTrace();
	}
	if (table == ECDB.Table.USER || table ==  ECDB.Table.INSTANCE) {
	    jtable.setDefaultEditor(Enum.class, new DefaultCellEditor
				    ((table == ECDB.Table.USER)?
				     getUserStatusCB(): getCalendarStatusCB()));
	}
	if  (table == ECDB.Table.ATTENDEE) {
	    jtable.setDefaultEditor(ECDB.AttendeeState.class,
				    new
				    DefaultCellEditor(getAttendeeStateCB()));
	}
	panel.n = columnHeadings.size();
	DefaultTableModel tm = new DefaultTableModel(rows, columnHeadings) {
		public Class<?> getColumnClass(int columnIndex) {
		    return getColClass(getColumnName(columnIndex));
		}
		public boolean isCellEditable(int row, int col) {
		    if (jtable.getCellSelectionEnabled() == false) {
			return false;
		    }
		    for (int i = 0; i < uneditableCols.length; i++) {
			if (uneditableCols[i] == col) return false;
		    }
		    return true;
		    /*
		    switch (table) {
		    case EVENT:
		    case INSTANCE:
		    case SERIES:
			return col > 1;
		    case CARRIER_MAP:
		    case PRE_EVENT_DEFAULT:
			return (col == 2);
		    case FIRST_ALARM:
			return (col > 4);
		    case SECOND_ALARM:
			return (col > 2);
		    case SERIES_INSTANCE:
			return false;
		    case ATTENDEE:
			return (col > 1);
		    default:
			return (col != 0);
		    }
		    */
		}
	    };
	ListSelectionModel lsm = new DefaultListSelectionModel();
	lsm.addListSelectionListener((se) -> {
		if (jtable.getColumnSelectionAllowed()) {
		    deleteButton.setEnabled(false);
		    addButton.setEnabled(false);
		} else {
		    boolean rowSelected = (jtable.getSelectedRowCount() > 0);
		    deleteButton.setEnabled(rowSelected);
		    addButton.setEnabled(true);
		}
	    });

	jtable.setModel(tm);
	tm.addTableModelListener((tml) -> {
		if ((table != ECDB.Table.SERIES_INSTANCE)) {
		    allowEditingCheckBox.setEnabled(false);
		    applyButton.setEnabled(true);
		    cancelButton.setEnabled(true);
		}
	    });

	int minEditableColumn = 0;
	switch (table) {
	case CARRIER_MAP:
	case PRE_EVENT_DEFAULT:
	case ATTENDEE:
	    minEditableColumn = 2;
	    break;
	case SECOND_ALARM:
	    minEditableColumn = 3;
	    break;
	case FIRST_ALARM:
	    minEditableColumn = 5;
	    break;
	case SERIES_INSTANCE:
	    minEditableColumn = 2;
	    break;
	default:
	    minEditableColumn = 1;
	}
	TableColumnModel tcm = jtable.getColumnModel();
	for (int i = 0; i < minEditableColumn; i++) {
	    TableColumn tc = tcm.getColumn(i);
	    tc.setCellRenderer(tcr);
	}
	for (int i = 0; i < uneditableCols.length; i++) {
	    TableColumn tc = tcm.getColumn(uneditableCols[i]);
	    tc.setCellRenderer(tcr);
	}
	jtable.setSelectionModel(lsm);
	jtable.setColumnSelectionAllowed(false);
	jtable.setRowSelectionAllowed(true);
	jtable.getTableHeader().setReorderingAllowed(false);
	jtable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
	JScrollPane sp = new JScrollPane(jtable);
	jtable.setFillsViewportHeight(true);
	int twidth = 0;
	int col = 0;
	for (String heading: columnHeadings) {
	    twidth += configColumn(jtable, col++, getColExample(heading));
	}
	Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
	twidth += 10;
	int maxwidth = (screensize.width*8)/10;
	if (twidth > maxwidth) twidth = maxwidth;
	sp.setPreferredSize(new Dimension(twidth, 400));
	panel.setLayout(new BorderLayout());
	panel.add(sp, BorderLayout.CENTER);
	JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
	if (table != ECDB.Table.SERIES_INSTANCE) {
	    allowEditingCheckBox.addItemListener((ie) -> {
		    if (ie.getStateChange() == ItemEvent.SELECTED) {
			jtable.setColumnSelectionAllowed(true);
			deleteButton.setEnabled(false);
			addButton.setEnabled(false);
		    } else {
			jtable.setColumnSelectionAllowed(false);
			if (jtable.getSelectedRow() < 0) {
			    deleteButton.setEnabled(false);
			} else {
			    deleteButton.setEnabled(true);
			}
			addButton.setEnabled(true);
		    }
		});
	    topPanel.add(allowEditingCheckBox);
	}
	applyButton.setEnabled(false);
	applyButton.addActionListener((ae) -> {
		if (jtable.isEditing()) {
		    int fi = CellInfo.getFlatInd(panel.ri, panel.ci,
						 panel.n);
		    if (!panel.cellmap.containsKey(fi)) {
			CellInfo cinf = new CellInfo(panel.ri, panel.ci,
						     panel.n, panel.saved);
			panel.cellmap.put(fi, cinf);
		    }
		    panel.ri = -1; panel.ci = -1; panel.saved = null;
		    TableCellEditor tce = jtable.getCellEditor();
		    if (tce != null) {
			tce.cancelCellEditing();
		    }
		}
		SwingUtilities.invokeLater(() -> {
			try {
			    apply(panel, ecdb, table, columnHeadings, rows);
			    cancelButton.setEnabled(false);
			    applyButton.setEnabled(false);
			    if ((table != ECDB.Table.SERIES_INSTANCE)) {
				allowEditingCheckBox.setEnabled(true);
			    }
			} catch (SQLException sqle) {
			    JOptionPane
				.showMessageDialog(panel,
						   sqle.getMessage(),
						   "ECDB: SQL error",
						   JOptionPane.ERROR_MESSAGE);
			    sqle.printStackTrace();
			}
		    });
	    });
	if (table != ECDB.Table.SERIES_INSTANCE) {
	    topPanel.add(applyButton);
	}
	cancelButton.setEnabled(false);
	cancelButton.addActionListener((ae) -> {
		if (jtable.isEditing()) {
		    int fi = CellInfo.getFlatInd(panel.ri, panel.ci,
						 panel.n);
		    if (!panel.cellmap.containsKey(fi)) {
			CellInfo cinf = new CellInfo(panel.ri, panel.ci,
						     panel.n, panel.saved);
			panel.cellmap.put(fi, cinf);
		    }
		    panel.ri = -1; panel.ci = -1; panel.saved = null;
		    TableCellEditor tce = jtable.getCellEditor();
		    if (tce != null) {
			tce.cancelCellEditing();
		    }
		}
		SwingUtilities.invokeLater(() -> {
			cancel(panel, jtable);
			cancelButton.setEnabled(false);
			applyButton.setEnabled(false);
			if ((table != ECDB.Table.SERIES_INSTANCE)) {
			    allowEditingCheckBox.setEnabled(true);
			}
		    });
	    });
	if (table != ECDB.Table.SERIES_INSTANCE) {
	    topPanel.add(cancelButton);
	}

	addButton.setEnabled(true);
	addButton.addActionListener((ae) -> {
		try {
		    addRows(panel, ecdb, table, jtable,
			    columnHeadings.toArray(emptyString), rows);
		} catch (SQLException sqle) {
		    sqle.printStackTrace();
		}
	    });
	topPanel.add(addButton);

	deleteButton.setEnabled(false);
	deleteButton.addActionListener((ae) -> {
		try {
		    delete(ecdb, table, jtable, rows);
		} catch (SQLException sqle) {
		    sqle.printStackTrace();
		}
	    });
	topPanel.add(deleteButton);
	panel.add(topPanel, "North");
	return panel;
    }

    private static enum SendMode {
	SEND_MSG,
	    EMAIL_CALENDAR,
	    TEXT_CALENDAR,
	    EMAIL_AND_TEXT_CALENDAR,
	// For menu items
	    COPY_EMAIL_CALENDARS,
	    COPY_PHONE_CALENDARS,
	// via dialogs
	    SEND_MSG_TO_EMAIL,
	    SEND_MSG_TO_PHONE
    }
    
    public static void sendCalendar(ECDB ecdb, SendMode mode)
    {
	JTextField upatternTF = new JTextField(32);
	// requestFocusInDialog(upatternTF);
	JPanel upanel = new JPanel(new GridLayout(2,1));
	upanel.add(new JLabel("User Search Pattern:"));
	upanel.add(upatternTF);
	String userOptions1[] = {
	    "Match Users",
	    "All Users",
	    "Cancel"};
	String userOptions2[] = {
	    "Match Users",
	    "Cancel"};
	String[] userOptions;
	boolean attending = true;
	boolean userOnly = true;
	boolean suppressCalendars = (mode == SendMode.SEND_MSG);
	switch (mode) {
	case SEND_MSG:
	    {
		String msgOptions[] = {
		    "Send Email (Attending)",
		    "Send Text (Attending)",
		    "Send Email (Not Attending)",
		    "Send Text (Not Attending)",
		    "Send Email (Selected Users)",
		    "Send Text (Selected Users)"
		};
		JComboBox<String> msgCB = new JComboBox<>(msgOptions);
		int opt =
		    JOptionPane.showConfirmDialog(frame, msgCB,
						"ECDB: Send-Message Option",
						JOptionPane.OK_CANCEL_OPTION,
						JOptionPane.QUESTION_MESSAGE);
		if (opt == JOptionPane.CANCEL_OPTION) return;
		opt = msgCB.getSelectedIndex();
		if (opt == -1) return;
		switch(opt) {
		case 0:
		case 2:
		    userOnly = false;
		    attending = (opt < 2);
		case 4:
		    mode = SendMode.SEND_MSG_TO_EMAIL;
		    break;
		case 1:
		case 3:
		    userOnly = false;
		    attending = (opt < 2);
		case 5:
		    mode = SendMode.SEND_MSG_TO_PHONE;
		    break;
		}
	    }
	    userOptions = userOptions1;
	    break;
	case EMAIL_CALENDAR:
	case TEXT_CALENDAR:
	case EMAIL_AND_TEXT_CALENDAR:
	    userOptions = userOptions1;
	    userOnly = false;
	    break;
	case COPY_EMAIL_CALENDARS:
	case COPY_PHONE_CALENDARS:
	    userOptions = userOptions2;
	    userOnly = false;
	    break;
	default:
	    userOptions = userOptions1;
	    userOnly = false;
	}
	
	int uflag = (attending == false)? 1:
	    JOptionPane.showOptionDialog(frame, upanel,
					 "ECDB: Filter User(s)",
					 JOptionPane.DEFAULT_OPTION,
					 JOptionPane.PLAIN_MESSAGE,
					 null, userOptions, userOptions[0]);
	if (userOptions == userOptions1 && uflag == 2) return;
	if (userOptions == userOptions2 && uflag == 1) return;
	String upattern;
	if (uflag == 0) {
	    upattern = upatternTF.getText();
	    if (upattern == null) upattern = "%";
	    upattern = upattern.trim();
	    if (upattern.length() == 0) upattern = "%";
	} else {
	    upattern = "%";
	}
	ECDB.UserLabeledID[] usrLabeledIDs = null;
	if (attending) {
	    try (Connection conn = ecdb.getConnection()) {
		usrLabeledIDs =
		    ecdb.listUserLabeledIDs(conn, upattern);
	    } catch (SQLException sqle) {
		System.err.println("ECDB: " + sqle.getMessage());
	    }
	}
	int usrIDs[] = (userOptions == userOptions2)? new int[1]:
	    ((attending == false)? new int[1]:
	     new int[usrLabeledIDs.length]);
	if (attending && usrLabeledIDs.length == 0) {
	    return;
	}
	Object o;
	Object[] objects;
	String opattern, epattern;
	int /*userID,*/ ownerID, eventID = -1;
	try {
	    if (uflag == 0) {
		InputTablePane.ColSpec[] colspec = {
		    new InputTablePane.ColSpec("Select", "mmmmmmm",
					       Boolean.class,
					       null,
					       new DefaultCellEditor
					       (new JCheckBox())),
		    new InputTablePane.ColSpec("User",
					   "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
					       ECDB.UserLabeledID.class,
					       new DefaultTableCellRenderer(),
					       null)
		};
		Vector<Vector<Object>>rows =
		    new Vector<Vector<Object>>(usrLabeledIDs.length);
		for (int i = 0; i < usrLabeledIDs.length; i++) {
		    Vector<Object>row = new Vector<Object>(2);
		    row.add(Boolean.FALSE);
		    row.add(usrLabeledIDs[i]);
		    rows.add(row);
		}
		if (userOptions == userOptions2) {
		    if (usrLabeledIDs.length == 1) {
			o = usrLabeledIDs[0];
		    } else {
			o=JOptionPane.showInputDialog(frame, "Users",
						      "ECDB: Select User",
						      JOptionPane
						      .QUESTION_MESSAGE,
						      null, usrLabeledIDs,
						      usrLabeledIDs[0]);
		    }
		    if (o == null) return;
		    // userID = ((ECDB.UserLabeledID)o).getID();
		    usrIDs[0] = ((ECDB.UserLabeledID)o).getID();
		} else {
		    InputTablePane ipane =
			InputTablePane.showDialog(frame, "ECDB: Select Users",
						  colspec, rows,
						  false, false, false);
		    if (ipane == null) return;
		    int nusers = 0;
		    for (int i = 0; i < usrLabeledIDs.length; i++) {
			if ((Boolean)ipane.getValueAt(i, 0)) {
			    usrIDs[nusers++] = ((ECDB.UserLabeledID)
						ipane.getValueAt(i, 1)).getID();
			}
		    }
		    if (nusers < usrIDs.length) {
			// indicate that there are not additional entries.
			usrIDs[nusers] = -1;
		    }
		}
	    } else {
		if (attending) {
		    int i = 0;
		    for (ECDB.UserLabeledID uid: usrLabeledIDs) {
			usrIDs[i++] = uid.getID();
		    }
		} else {
		    usrIDs[0] = -1;
		}
	    }
	    SMTPAgent agent = null;
	    if (userOnly) {
		// For this case, we don't generate a calendar. We just
		// need a list of users to send messages to.
		String s = ecdb.getSubject();
		if (s == null || s.length() == 0) {
		    System.err.println("ECDB: subject missing\n");
		    return;
		}
		URL u1 = ecdb.getTemplateURL();
		URL u2 = ecdb.getAltTemplateURL();
		if (u1 == null && u2 == null) {
		    System.err.println("ECDB: template URL missing\n");
		    return;
		}
		boolean forEmail = true;
		Vector<ECDB.UserCalendars> vector = new Vector<>(usrIDs.length);
		try (Connection conn = ecdb.getConnection()) {
		    for (int id: usrIDs) {
			ECDB.UserCalendars cals = new ECDB.UserCalendars();
			cals.userID = id;
			switch (mode) {
			case SEND_MSG_TO_EMAIL:
			    cals.forEmail = true;
			    break;
			case SEND_MSG_TO_PHONE:
			    cals.forEmail = false;
			    break;
			default:
			    return;
			}
			cals.kmap = ecdb.getUserKeyMap(conn, id);
			vector.add(cals);
		    }
		    boolean preflight = ecdb.getPreflight();
		    if (preflight) {
			if (ECDB.sendViaEmail(ecdb, conn, vector, true, frame,
					      true) == false) {
			    return;
			}
		    }
		    ECDB.sendViaEmail(ecdb, conn, vector, true, frame, false);
		    return;
		} catch (Exception e) {
		    System.err.format("ECDB (%s): %s\n",
				      e.getClass().toString(), e.getMessage());
		    return;
		} finally {
		    if (agent != null) {
		    }
		}
	    }

	    try (Connection conn = ecdb.getConnection()) {
		objects = ecdb.listOwnerLabels(conn);
	    }
	    o = JOptionPane.showInputDialog(frame, "Owner:",
					    "ECDB: Select Owner",
					    JOptionPane.QUESTION_MESSAGE,
					    null, objects, objects[0]);
	    opattern = (o == objects[0])? null: (String)o;
	    try (Connection conn = ecdb.getConnection()) {
		ownerID = (opattern == null || opattern.length() == 0)? -1:
		    ecdb.findOwner(conn, opattern);
		objects = ecdb.listEventLabels(conn, ownerID);
	    }
	    if (objects.length == 2) {
		// the first entry is "[ All ]"
		epattern = (String) objects[1];
	    } else {
		o = JOptionPane.showInputDialog(frame, "Events:",
						"ECDB: Select Event",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		epattern = (o == objects[0])? null: (String)o;
	    }
	    int locationID = -1;
	    int instanceID = -1;
	    if (attending == false) {
		ECDB.LocationLabeledID[] lids = null;
		try (Connection conn = ecdb.getConnection()) {
		    lids = ecdb.listLocationLabeledIDs(conn, true);
		}
		
		try (Connection conn = ecdb.getConnection()) {
		    eventID = (epattern == null || epattern.length() == 0)? -1:
			ecdb.findEvent(conn, ownerID, epattern);
		    objects = ecdb.listInstanceLabeledIDs(conn, ownerID,
							  eventID, -1, true);
		}
		o = JOptionPane.showInputDialog(frame, "Instances:",
						"ECDB: Select Instance",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		if (o == null) return;
		instanceID =  ((ECDB.InstanceLabeledID)o).getID();
	    }
	    try (Connection conn = ecdb.getConnection()) {
		if (attending) {
		    eventID = (epattern == null || epattern.length() == 0)? -1:
			ecdb.findEvent(conn, ownerID, epattern);
		}
		Vector<ECDB.UserCalendars> vector = new Vector<>(usrIDs.length);
		for (int userID: usrIDs) {
		    if (attending && userID == -1) break;
		    // System.out.println("trying userID " + userID);
		    // send emails with calendars
		    Vector<ECDB.UserCalendars> v;
		    boolean forEmail;
		    // System.out.println("mode = " + mode);
		    switch (mode) {
		    case COPY_EMAIL_CALENDARS:
		    case COPY_PHONE_CALENDARS:
			// we allow only a single user in this case.
			forEmail = (mode == SendMode.COPY_EMAIL_CALENDARS);
			v = ecdb.getCalendars(conn, userID, ownerID,
					      eventID, forEmail);
			if (v.size() == 0) return;
			Vector<byte[]>cv = v.get(0).calendars;
			try {
			    ECDB.copyToClipboard(cv, false);
			} catch (IOException eio) {
			    System.err.println("ECDB: " + eio.getMessage());
			}
			break;
		    case EMAIL_AND_TEXT_CALENDAR:
		    case SEND_MSG_TO_EMAIL:
		    case EMAIL_CALENDAR:
			forEmail = true;
			if (attending) {
			    v = ecdb.getCalendars(conn, userID, ownerID,
						  eventID, forEmail);
			} else {
			    v = ecdb.getNonAttendees(conn, ownerID, eventID,
						     locationID, instanceID,
						     forEmail);
			    /*
			    System.out.println("v.size() = " + v.size());
			    for (ECDB.UserCalendars ucal: v) {
				System.out.println("For userID = " + userID);
				ucal.kmap.print();
			    }
			    */
			}
			vector.addAll(v);
			if (mode != SendMode.EMAIL_AND_TEXT_CALENDAR) break;
		    case TEXT_CALENDAR:
		    case SEND_MSG_TO_PHONE:
			forEmail = false;
			if (attending) {
			    v = ecdb.getCalendars(conn, userID, ownerID,
						  eventID, forEmail);
			} else {
			    v = ecdb.getNonAttendees(conn, ownerID, eventID,
						     locationID, instanceID,
						     forEmail);
			}
			vector.addAll(v);
			break;
		    default:
			System.out.println("SendMode = " + mode);
			break;
		    }
		}
		switch (mode) {
		case COPY_EMAIL_CALENDARS:
		case COPY_PHONE_CALENDARS:
		    break;
		case EMAIL_AND_TEXT_CALENDAR:
		case SEND_MSG_TO_EMAIL:
		case EMAIL_CALENDAR:
		case TEXT_CALENDAR:
		case SEND_MSG_TO_PHONE:
		    try {
			boolean preflight = ecdb.getPreflight();
			if (preflight &&
			    ECDB.sendViaEmail(ecdb, conn, vector,
					      suppressCalendars,
					      frame, true) == false) {
			    return;
			}
			ECDB.sendViaEmail(ecdb, conn, vector,
					  suppressCalendars, frame, false);
		    } catch (Exception e) {
			System.err.format("ECDB (%s): %s\n",
					  e.getClass(),
					  e.getMessage());
			e.printStackTrace();
		    }
		    break;
		default:
		    System.out.println("SendMode = " + mode);
		}
	    }
	} catch (SQLException sqle) {
	    System.err.println("ECDB: " + sqle.getMessage());
	}
    }

    public static void showTablePanel(JFrame parent,
				      ECDB ecdb, ECDB.Table tableType)
    {
	Vector<Vector<Object>> rows = null;
	Vector<Vector<Object>> urows = null;
	String upattern, opattern, lpattern, epattern, spattern;
	int userID = -1, ownerID = -1, locationID = -1, eventID = -1,
	    seriesID = -1;
	ECDB.UserLabeledID[] userLabeledIDs = null;
	try (Connection conn = ecdb.getConnection()) {
	    Object[] objects;
	    Object o;
	    ArrayList<Integer> uneditableList = new ArrayList<>(10);
	    ArrayList<Integer> uneditableListAR = new ArrayList<>(10);
	    switch (tableType) {
	    case CARRIER:
		rows = ecdb.listCarriers(conn, (int[]) null, true);
		uneditableList.add(0);
		uneditableListAR.add(0);
		break;
	    case CARRIER_MAP:
		rows = ecdb.listCarrierMap(conn, null, null, true);
		uneditableList.add(0);
		uneditableList.add(1);
		break;
	    case USER:
		upattern = (String)JOptionPane
		    .showInputDialog(parent, "User Search Pattern:",
				     "ECDB: Find User(s)",
				     JOptionPane.QUESTION_MESSAGE,
				     null, null,
				     "%");
		if (upattern == null) return;
		uneditableList.add(0);
		uneditableListAR.add(0);
		rows = ecdb.listUserInfo(conn, upattern, true);
		break;
	    case OWNER:
		rows = ecdb.listOwners(conn, (int[]) null, true);
		uneditableList.add(0);
		uneditableListAR.add(0);
		break;
	    case PRE_EVENT_DEFAULT:
		upattern = (String)JOptionPane
		    .showInputDialog(parent, "User Search Pattern:",
				     "ECDB: Select User",
				     JOptionPane.QUESTION_MESSAGE);
		if (upattern == null)  return;
		upattern = upattern.trim();
		if (upattern.length() == 0) upattern = "%";
		userLabeledIDs = ecdb.listUserLabeledIDs(conn, upattern);
		if (userLabeledIDs.length == 0) {
		    rows = new Vector<Vector<Object>>();
		} else {
		    if (userLabeledIDs.length == 1) {
			o = userLabeledIDs[0];
		    } else {
			o = (JOptionPane.showInputDialog
			     (parent, "Users", "ECDB: Select User",
			      JOptionPane.QUESTION_MESSAGE, null,
			      userLabeledIDs, userLabeledIDs[0]));
		    }
		    if (o == null) return;
		    userID = ((ECDB.UserLabeledID)o).getID();
		    uneditableList.add(0);
		    uneditableListAR.add(0);
		    uneditableList.add(1);
		    rows = ecdb.listPreEventDefaults(conn, userID, -1, true);
		}
		break;
	    case LOCATION:
		uneditableList.add(0);
		uneditableListAR.add(0);
		rows = ecdb.listLocations(conn, (int[]) null, true);
		break;
	    case FIRST_ALARM:
	    case SECOND_ALARM:
		upattern = (String)JOptionPane
		    .showInputDialog(parent, " User Search Pattern:",
				     "ECDB: Find User",
				     JOptionPane.QUESTION_MESSAGE);
		if (upattern == null)  return;
		upattern = upattern.trim();
		if (upattern.length() == 0) upattern = "%";
		userLabeledIDs = ecdb.listUserLabeledIDs(conn, upattern);
		if (userLabeledIDs.length == 0) {
		    rows = new Vector<Vector<Object>>();
		} else {
		    if (userLabeledIDs.length == 1) {
			o = userLabeledIDs[0];
		    } else {
			o = (JOptionPane.showInputDialog
			     (parent, "Users", "ECDB: Select User",
			      JOptionPane.QUESTION_MESSAGE, null,
			      userLabeledIDs, userLabeledIDs[0]));
		    }
		    if (o == null) return;
		    userID = ((ECDB.UserLabeledID)o).getID();
		    objects = ecdb.listOwnerLabels(conn);
		    o = JOptionPane.showInputDialog
			(parent, "Owner:", "ECDB: Select Owner",
			 JOptionPane.QUESTION_MESSAGE, null,
			 objects, objects[0]);
		    opattern = (o == objects[0])? null: (String)o;
		    ownerID = (opattern == null || opattern.length() == 0)?
			-1: ecdb.findOwner(conn, opattern);
		    objects = ecdb.listLocationLabels(conn);
		    o = JOptionPane.showInputDialog
			(parent, "Location:", "ECDB: Select Location",
			 JOptionPane.QUESTION_MESSAGE,
			 null, objects, objects[0]);
		    lpattern = (o == objects[0])? null: (String)o;
		    locationID = (lpattern == null || lpattern.length() == 0)?
			-1: ecdb.findLocation(conn, lpattern);
		    uneditableList.add(0);
		    uneditableList.add(1);
		    uneditableList.add(2);
		    uneditableListAR.add(0);
		    if (ownerID != -1) uneditableListAR.add(1);
		    if (locationID != -1) uneditableListAR.add(2);
		    rows = (tableType == ECDB.Table.FIRST_ALARM)?
			ecdb.listFirstAlarms(conn, userID, ownerID,
					     locationID, null, null, true):
			ecdb.listSecondAlarms(conn, userID, ownerID, locationID,
					      true);			
		}
		break;
		/*
		  case SECOND_ALARM:
		  upattern = (String)JOptionPane
		  .showInputDialog(parent, " User Search Pattern:",
		  "ECDB: Select User",
		  JOptionPane.QUESTION_MESSAGE);
		  userID = ecdb.findUserInfo(conn, upattern);
		  objects = ecdb.listOwnerLabels(conn);
		  o = JOptionPane.showInputDialog(parent, "Owner:",
		  "ECDB: Select Owner",
		  JOptionPane.QUESTION_MESSAGE,
		  null, objects, objects[0]);
		  opattern = (o == objects[0])? null: (String)o;
		  ownerID = (opattern == null || opattern.length() == 0)?
		  -1: ecdb.findOwner(conn, opattern);
		  objects = ecdb.listLocationLabels(conn);
		  o = JOptionPane.showInputDialog(parent, "Location:",
		  "ECDB: Select Location",
		  JOptionPane.QUESTION_MESSAGE,
		  null, objects, objects[0]);
		  lpattern = (o == objects[0])? null: (String)o;
		  locationID = (opattern == null || opattern.length() == 0)?
		  -1: ecdb.findLocation(conn, opattern);
		  rows = ecdb.listSecondAlarms(conn, userID, ownerID, locationID,
		  true);
		  break;
		*/
	    case EVENT:
		objects = ecdb.listOwnerLabels(conn);
		o = JOptionPane.showInputDialog(parent, "Owner:",
						"ECDB: Select Owner",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		opattern = (o == objects[0])? null: (String)o;
		ownerID = (opattern == null || opattern.length() == 0)?
		    -1: ecdb.findOwner(conn, opattern);
		uneditableList.add(0);
		uneditableListAR.add(0);
		if (ownerID != -1) {
		    uneditableList.add(1);
		    uneditableListAR.add(1);

		}
		rows = ecdb.listEventsForOwner(conn, ownerID, true);
		break;
	    case INSTANCE:
		objects = ecdb.listOwnerLabels(conn);
		o = JOptionPane.showInputDialog(parent, "Owner:",
						"ECDB: Select Owner",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		opattern = (o == objects[0])? null: (String)o;
		ownerID = (opattern == null || opattern.length() == 0)?
		    -1: ecdb.findOwner(conn, opattern);
		objects = ecdb.listEventLabels(conn, ownerID);
		o = JOptionPane.showInputDialog(parent, "Event:",
						"ECDB: Select Event",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		epattern = (o == objects[0])? null: (String)o;
		eventID = (epattern == null || epattern.length() == 0)?
		    -1: ecdb.findEvent(conn, ownerID, epattern);
		objects = ecdb.listLocationLabels(conn);
		o = JOptionPane.showInputDialog(parent, "Location:",
						"ECDB: Select Location",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		lpattern = (o == objects[0])? null: (String)o;
		locationID = (lpattern == null || lpattern.length() == 0)?
		    -1: ecdb.findLocation(conn, lpattern);
		uneditableList.add(0);
		uneditableList.add(1);
		uneditableListAR.add(0);
		if (eventID != -1) {
		    uneditableListAR.add(1);
		}
		if (locationID != -1) {
		    uneditableListAR.add(2);
		}
		rows = ecdb.listEventInstances(conn, ownerID, eventID,
					       locationID, true);
		break;
	    case SERIES:
		objects = ecdb.listOwnerLabels(conn);
		o = JOptionPane.showInputDialog(parent, "Owner:",
						"ECDB: Select Owner",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		opattern = (o == objects[0])? null: (String)o;
		ownerID = (opattern == null || opattern.length() == 0)?
		    -1: ecdb.findOwner(conn, opattern);
		uneditableList.add(0);
		uneditableListAR.add(0);
	        if (ownerID != -1) {
		    uneditableList.add(1);
		    uneditableListAR.add(1);
		}
		rows = ecdb.listSeries(conn, ownerID, null, true);
		break;
	    case SERIES_INSTANCE:
		objects = ecdb.listOwnerLabels(conn);
		o = JOptionPane.showInputDialog(parent, "Owner:",
						"ECDB: Select Owner",
						JOptionPane.QUESTION_MESSAGE,
						null, objects, objects[0]);
		opattern = (o == objects[0])? null: (String)o;
		ownerID = (opattern == null || opattern.length() == 0)?
		    -1: ecdb.findOwner(conn, opattern);
		objects = ecdb.listSeriesLabels(conn, ownerID, true);
		if (objects.length == 2) {
		    // the first entry is "[ All ]"
		    spattern = (String) objects[1];
		    System.out.println("spattern = " + spattern);
		} else {
		    o = JOptionPane.showInputDialog
			(parent, "Series:", "ECDB: Select Series",
			 JOptionPane.QUESTION_MESSAGE, null, objects,
			 objects[0]);
		    spattern = (o == objects[0])? null: (String)o;
		}
		seriesID = (spattern == null || spattern.length() == 0)?
		    -1: ecdb.findSeries(conn, ownerID, spattern);
		uneditableList.add(0);
		uneditableList.add(1);
		if (seriesID != -1) {
		    uneditableListAR.add(0);
		}
		rows = ecdb.listSeriesInstanceByOwner(conn, seriesID,
						      ownerID, true);
		break;
	    case ATTENDEE:
		upattern = (String)JOptionPane
		    .showInputDialog(parent, "User Search Pattern:",
				     "ECDB: Select User",
				     JOptionPane.QUESTION_MESSAGE);
		if (upattern == null)  return;
		upattern = upattern.trim();
		if (upattern.length() == 0) upattern = "%";
		userLabeledIDs = ecdb.listUserLabeledIDs(conn, upattern);
		if (userLabeledIDs.length == 0) {
		    rows = new Vector<Vector<Object>>();
		} else {
		    if (userLabeledIDs.length == 1) {
			o = userLabeledIDs[0];
		    } else {
			o = (JOptionPane.showInputDialog
			     (parent, "Users", "ECDB: Select User",
			      JOptionPane.QUESTION_MESSAGE, null,
			      userLabeledIDs, userLabeledIDs[0]));
		    }
		    if (o == null) return;
		    userID = ((ECDB.UserLabeledID)o).getID();
		    uneditableList.add(0);
		    uneditableListAR.add(0);
		    uneditableList.add(1);
		    uneditableList.add(4);
		    uneditableListAR.add(4);
		    rows = ecdb.listAttendees(conn, userID, -1, -1, null, true);
		}
		break;
	    default:
		throw new IllegalArgumentException();
	    }
	    Integer[] uneditableCols = new Integer[uneditableList.size()];
	    uneditableList.toArray(uneditableCols);
	    Integer[] uneditableColsAR = new Integer[uneditableListAR.size()];
	    uneditableListAR.toArray(uneditableColsAR);
	    if (rows != null) {
		OurJPanel panel =
		    createTablePanel(ecdb, tableType,
				     ownerID, eventID, locationID,
				     uneditableCols,
				     ECDB.getHeadingVector(tableType, true),
				     rows);
		panel.uneditableCols = uneditableColsAR;
		switch (tableType) {
		case EVENT:
		    if (ownerID != -1) {
			System.out.println("saw ownerID = " + ownerID);
			panel.olid = ecdb.getOwnerLabeledID(conn, ownerID);
		    }
		    break;
		case FIRST_ALARM:
		case SECOND_ALARM:
		    if (ownerID != -1) {
			System.out.println("saw ownerID = " + ownerID);
			panel.olid = ecdb.getOwnerLabeledID(conn, ownerID);
		    }
		    if (locationID != -1) {
			System.out.println("saw locationID = " + locationID);
			panel.llid = ecdb.getLocationLabeledID(conn,
							       locationID);
		    }
		case PRE_EVENT_DEFAULT:
		case ATTENDEE:
		    panel.ulid = ecdb.getUserLabeledID(conn, userID);
		}
		JFrame frame = new JFrame("ECDB " + tableType.toString());
		frame.setIconImages(iconList);

		JMenuBar menubar = new JMenuBar();
		JMenu fileMenu = new JMenu("File");
		fileMenu.setMnemonic(KeyEvent.VK_F);
		menubar.add(fileMenu);
		JMenuItem menuItem = new JMenuItem("Close", KeyEvent.VK_W);
		menuItem.setAccelerator(KeyStroke.getKeyStroke
					(KeyEvent.VK_W,
					 InputEvent.CTRL_DOWN_MASK));
		menuItem.addActionListener((ae) -> {
			frame.dispose();
		    });
		fileMenu.add(menuItem);
		frame.setJMenuBar(menubar);
		frame.setLayout(new BorderLayout());
		frame.add(panel, BorderLayout.CENTER);
		frame.pack();
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
			    frame.dispose();
			}
		    });
		frame.setVisible(true);
	    }
	} catch (SQLException sqle) {
	    System.err.println(sqle.getMessage());
	    sqle.printStackTrace();
	}
    }

    static final Color goColor = new Color(224, 255, 224);

    static void requestFocusInDialog(final JComponent tf) {
	tf.addAncestorListener(new AncestorListener() {
		@Override public void ancestorRemoved(AncestorEvent ae) {}
		@Override public void ancestorMoved(AncestorEvent ae) {}
		@Override
		public void ancestorAdded(AncestorEvent ae) {
		    tf.requestFocusInWindow();
		}
		
	    });
	tf.addFocusListener(new FocusListener() {
		@Override public void focusGained(FocusEvent fe) {}

		private boolean firstTime = true;

		@Override
		public void focusLost(FocusEvent fe) {
		    if (firstTime) {
			tf.requestFocusInWindow();
			firstTime = false;
		    }
		}
	    });
    }

    static void configureMessaging(JFrame parent, ECDB ecdb) {
	MsgPane mpane = MsgPane.showDialog(parent, "ECDB: Configure Messaging",
					   ecdb);
	if (mpane != null) {
	    Object value;
	    value = mpane.getValueAt(0, 1);
	    System.out.println("subject = " + value);
	    ecdb.setSubject((String) value);
	    value = mpane.getValueAt(1, 1);
	    String svalue = (String)(value.toString());
	    if (svalue.length() == 0) svalue = null;
	    System.out.println("media type: " + svalue);
	    ecdb.setMediaType(svalue);
	    value = mpane.getValueAt(2, 1);
	    URL uvalue;
	    try {
		uvalue = (value == null)? null: ((File) value).toURI().toURL();
	    } catch (MalformedURLException e) {uvalue = null;}
	    ecdb.setTemplateURL(uvalue);
	    value = mpane.getValueAt(3, 1);
	    svalue = (String)(value.toString());
	    System.out.println("alt media type: " + svalue);
	    if (svalue.length() == 0) svalue = null;
	    ecdb.setAltMediaType(svalue);
	    value = mpane.getValueAt(4, 1);
	    try {
		uvalue = (value == null)? null: ((File) value).toURI().toURL();
	    } catch (MalformedURLException e) {uvalue = null;}
	    ecdb.setAltTemplateURL(uvalue);
	}
    }


    public static void createGUI(final ECDB ecdb) {
	SwingUtilities.invokeLater(() -> {
		final JButton[] buttons = {
		    new JButton("Carriers"),	      // 0
		    new JButton("Carrier Map"),	      // 1
		    new JButton("Users"),	      // 2
		    new JButton("Owners"),	      // 3
		    new JButton("Pre-Event Default"), // 4
		    new JButton("Locations"),	      // 5
		    new JButton("First Alarm"),	      // 6
		    new JButton("Second Alarm"),      // 7
		    new JButton("Events"),	      // 8
		    new JButton("Instances"),	      // 9
		    new JButton("Series"),	      // 10
		    new JButton("Series Instance"),   // 11
		    new JButton("Attendee List"),     // 12
		    new JButton("Apply Series"),      // 13
		    new JButton("Send Message"),      // 14
		    new JButton("Email Calendars"),    // 15
		    new JButton("Text Calendars"),     // 16
		    new JButton("Email&Text Calendars")// 17
		};

		buttons[13].setBackground(goColor);
		buttons[14].setBackground(goColor);
		buttons[15].setBackground(goColor);
		buttons[16].setBackground(goColor);
		buttons[17].setBackground(goColor);

		frame = new JFrame("ECDB");
		frame.setIconImages(iconList);
		JMenuBar menubar = new JMenuBar();
		JMenu fileMenu = new JMenu("File");
		fileMenu.setMnemonic(KeyEvent.VK_F);
		menubar.add(fileMenu);
		JMenuItem menuItem = new JMenuItem("Edit Config");
		menuItem.addActionListener((ae) -> {
			editConfig(ecdb.getConfigFile());
			for (int i = 0; i < buttons.length; i++) {
			    buttons[i].setEnabled(false);
			}
			frame.setVisible(false);
			frame.dispose();
		    });
		fileMenu.add(menuItem);
		menuItem = new JMenuItem("Quit", KeyEvent.VK_Q);
		menuItem.setAccelerator(KeyStroke.getKeyStroke
					(KeyEvent.VK_Q,
					 InputEvent.CTRL_DOWN_MASK));
		menuItem.addActionListener((ae) -> {
			System.exit(0);
		    });
		fileMenu.add(menuItem);
		
		JMenu editMenu = new JMenu("Edit");
		editMenu.setMnemonic(KeyEvent.VK_E);
		menubar.add(editMenu);
		menuItem = new JMenuItem("Copy Email Calendars...",
					 KeyEvent.VK_E);
		menuItem.addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.COPY_EMAIL_CALENDARS);
		    });
		editMenu.add(menuItem);
		menuItem =new JMenuItem("Copy Cell-Phone Calendars...",
					KeyEvent.VK_P);
		menuItem.addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.COPY_PHONE_CALENDARS);
		    });
		editMenu.add(menuItem);

		JMenu msgMenu = new JMenu("Messaging");
		msgMenu.setMnemonic(KeyEvent.VK_M);
		menubar.add(msgMenu);
		menuItem = new JMenuItem("Configure...", KeyEvent.VK_C);
		menuItem.addActionListener((ae) -> {
			configureMessaging(frame, ecdb);
		    });
		msgMenu.add(menuItem);

		JCheckBoxMenuItem preflightMI
		    = new JCheckBoxMenuItem("preflight");
		preflightMI.setSelected(ecdb.getPreflight());
		menuItem.addActionListener((ae) -> {
			ecdb.setPreflight(preflightMI.isSelected());
		    });
		msgMenu.add(preflightMI);


		frame.setJMenuBar(menubar);

		buttons[0].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.CARRIER);
		    });
		buttons[1].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.CARRIER_MAP);
		    });
		buttons[2].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.USER);
		    });
		buttons[3].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.OWNER);
		    });
		buttons[4].addActionListener((ae) -> {
			showTablePanel(frame, ecdb,
				       ECDB.Table.PRE_EVENT_DEFAULT);
		    });
		buttons[5].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.LOCATION);
		    });
		buttons[6].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.FIRST_ALARM);
		    });
		buttons[7].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.SECOND_ALARM);
		    });
		buttons[8].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.EVENT);
		    });
		buttons[9].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.INSTANCE);
		    });
		buttons[10].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.SERIES);
		    });
		buttons[11].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.SERIES_INSTANCE);
		    });
		buttons[12].addActionListener((ae) -> {
			showTablePanel(frame, ecdb, ECDB.Table.ATTENDEE);
		    });

		buttons[13].addActionListener((ae) -> {
			JTextField upatternTF = new JTextField(32);
			// requestFocusInDialog(upatternTF);
			JPanel upanel = new JPanel(new GridLayout(2,1));
			upanel.add(new JLabel("User Search Pattern:"));
			upanel.add(upatternTF);
			String userOptions[] = {
			    "Filter Users",
			    "All Users",
			    "Cancel"};
			int uflag = JOptionPane.showOptionDialog
			    (frame, upanel, "ECDB: Select User(s)",
			     JOptionPane.DEFAULT_OPTION,
			     JOptionPane.PLAIN_MESSAGE,
			     null, userOptions, userOptions[0]);
			if (uflag == 2) return;
			String upattern;
			if (uflag == 0) {
			    upattern = upatternTF.getText();
			    if (upattern == null) upattern = "%";
			    upattern = upattern.trim();
			    if (upattern.length() == 0) upattern = "%";
			} else {
			    upattern = "%";
			}
			/*
			String upattern = (String)JOptionPane
			    .showInputDialog(frame, "User Search Pattern:",
					     "ECDB: Select User",
					     JOptionPane.QUESTION_MESSAGE);
			if (upattern == null)  return;
			upattern = upattern.trim();
			if (upattern.length() == 0) upattern = "%";
			*/
			Object o;
			Object[] objects;
			String opattern, spattern;
			int /*userID,*/ ownerID, seriesID;
			ECDB.UserLabeledID[] userLabeledIDs;
			try {
			    try (Connection conn = ecdb.getConnection()) {
				userLabeledIDs =
				    ecdb.listUserLabeledIDs(conn, upattern);
			    }
			    int userIDs[] = new int[(uflag == 0)? 1:
						    userLabeledIDs.length];
			    if (userLabeledIDs.length == 0) {
				return;
			    }
			    if (uflag == 0) {
				if (userLabeledIDs.length == 1) {
				    o = userLabeledIDs[0];
				} else {
				    o = (JOptionPane.showInputDialog
					 (frame, "Users", "ECDB: Select User",
					  JOptionPane.QUESTION_MESSAGE, null,
					  userLabeledIDs, userLabeledIDs[0]));
				}
				if (o == null) return;
				// userID = ((ECDB.UserLabeledID)o).getID();
				userIDs[0] = ((ECDB.UserLabeledID)o).getID();
			    } else {
				// select from a list of all users
				InputTablePane.ColSpec colspec[] = {
				    new InputTablePane.ColSpec
				    ("Select", "mmmm", Boolean.class,
				     null,
				     new DefaultCellEditor(new JCheckBox())),
				    new InputTablePane.ColSpec
				    ("User",
				     "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm",
				     ECDB.UserLabeledID.class,
				     new DefaultTableCellRenderer(),
				     null)
				};
				Vector<Vector<Object>>rows =
				    new Vector<Vector<Object>>
				    (userLabeledIDs.length);
				for (ECDB.UserLabeledID lid: userLabeledIDs) {
				    Vector<Object> row = new Vector<>(2);
				    row.add(Boolean.FALSE);
				    row.add(lid);
				    rows.add(row);
				}
				InputTablePane ipane = new
				    InputTablePane(colspec,
						   userLabeledIDs.length,
						   rows, false, false, false);
				int status = InputTablePane.showDialog
				    (frame, "ECDB: Select Users", ipane);
				if (status == 1) return;
				int n = ipane.getRowCount();
				int m = 0;
				for (int i = 0; i < n; i++) {
				    boolean use =
					(Boolean)ipane.getValueAt(i,0);
				    if (use) {
					userIDs[m++] =
					    ((ECDB.UserLabeledID)
					     ipane.getValueAt(i, 1)).getID();
				    }
				}
				if (m < n) {
				    userIDs[m] = -1;
				}
			    }
			    try (Connection conn = ecdb.getConnection()) {
				objects = ecdb.listOwnerLabels(conn);
			    }
			    o = JOptionPane.showInputDialog
				(frame, "Owner:", "ECDB: Select Owner",
				 JOptionPane.QUESTION_MESSAGE,
				 null, objects, objects[0]);
			    opattern = (o == objects[0])? null: (String)o;
			    try (Connection conn = ecdb.getConnection()) {
				ownerID = (opattern == null
					   || opattern.length() == 0)?
				    -1: ecdb.findOwner(conn, opattern);
				objects = ecdb.listSeriesLabels(conn, ownerID,
								false);
			    }
			    if (objects.length == 0) {
				// nothing to do
				return;
			    } else  if (objects.length == 1) {
				// there is only a single series
				spattern = (String) objects[0];
			    } else {
				o = JOptionPane.showInputDialog
				    (frame, "Series:",
				     "ECDB: Select Series",
				     JOptionPane.QUESTION_MESSAGE, null,
				     objects, objects[0]);
				spattern = (String)o;
			    }
			    try (Connection conn = ecdb.getConnection()) {
				seriesID = (spattern == null
					    || spattern.length() == 0)? -1:
				    ecdb.findSeries(conn, ownerID, spattern);
				try {
				    conn.setAutoCommit(false);
				    for (int userID: userIDs) {
					if (userID == -1) break;
					ecdb.applySeries(conn, userID,
							 seriesID, false);
				    }
				    conn.commit();
				} catch (Exception e) {
				    conn.rollback();
				} finally {
				    conn.setAutoCommit(true);
				}
			    }
			} catch (SQLException sqle) {
			    System.err.println("ecdb: " + sqle.getMessage());
			}
		    });

		buttons[14].addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.SEND_MSG); 
		    });
		buttons[15].addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.EMAIL_CALENDAR); 
		    });
		buttons[16].addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.TEXT_CALENDAR); 
		    });
		buttons[17].addActionListener((ae) -> {
			sendCalendar(ecdb, SendMode.EMAIL_AND_TEXT_CALENDAR); 
		    });

		JPanel panel = new JPanel(new GridLayout(6, 3));
		for (int i = 0; i < buttons.length; i++) {
		    panel.add(buttons[i]);
		}

		frame.setLayout(new BorderLayout());
		frame.add(panel, BorderLayout.CENTER);
		frame.pack();
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
			    System.exit(0);
			}
		    });
		frame.setVisible(true);
	    });
    }
}
