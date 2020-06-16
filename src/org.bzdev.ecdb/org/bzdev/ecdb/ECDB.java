package org.bzdev.ecdb;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.FormatStyle;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import javax.swing.JFrame;

import org.bzdev.io.AppendableWriter;
import org.bzdev.lang.UnexpectedExceptionError;
import org.bzdev.net.calendar.*;
import org.bzdev.util.CollectionScanner;
import org.bzdev.util.CopyUtilities;
import org.bzdev.util.TemplateProcessor;
import org.bzdev.util.TemplateProcessor.KeyMap;
import org.bzdev.util.TemplateProcessor.KeyMapList;

/**
 * Event-Calendar Database Session.
 * <P>
 * The ECDB class provides an API for initializing and manipulating a
 * relational database representing an event calendar. In addition to
 * methods that select, insert, update, or delete table entries, ECDB
 * can generate RFC 5545 calendar appointments and send them via email.
 * <P>
 * Calendar appointments include the starting date, starting time,
 * ending date, and ending time for an event, whether there is some
 * activity before an event and when that starts, and two optional
 * alarms, with the times set on a per-user basis.  The class will also
 * send messages with or without calendar appointments to users to
 * either an email address or to the user's MMS service.  Each calendar
 * appointment is tagged with fields that allow a previously sent calendar
 * appointment to be updated.
 *
 * Please see <A HREF="doc-files/ECDB.html">the ECDB description</A> for
 * a more detailed overview of this class.
 */
public class ECDB implements AutoCloseable {

    static final Charset UTF8 = Charset.forName("UTF-8");

    private static DateTimeFormatter tf =
	DateTimeFormatter.ofPattern("hh:mm a");
    private static DateTimeFormatter df =
	DateTimeFormatter.ofPattern("E MMMM dd, yyyy");
    private static DateTimeFormatter sdf =
	DateTimeFormatter.ofPattern("MM/dd/YYYY");

    Properties dbProperties = new Properties();
    Properties sqlProperties = new Properties();
    String typeSuffix;
    boolean configRoles;
    boolean configAuth;
    Set<String> sqlShutdownStates = new HashSet<>();
    ZoneId zoneId;
    File configFile = null;

    File getConfigFile() {return configFile;}

    private void setup(File file) throws IOException, ECDBException {
	if (file == null) {
	    Reader r = new InputStreamReader
		(ECDB.class.getResourceAsStream("Defaults.properties"), UTF8);
	    dbProperties.load(r);
	    r.close();
	} else {
	    Reader r = new InputStreamReader(new FileInputStream(file), UTF8);
	    dbProperties.load(r);
	    r.close();
	}
	configFile = file;
    }

    /**
     * Constructor.
     */
    public ECDB() throws IOException, ECDBException {
	setup(null);
	init();
    }

    /**
     * Constructor given a configuration file.
     * @param file the configuration file
     */
    public ECDB(File file) throws IOException, ECDBException {
	setup(file);
	init();
    }

    /**
     * Constructor given configuration properties.
     * @param properties the properties
     */
    public ECDB(Properties properties) throws IOException, ECDBException {
	if (properties == null) {
	    throw new IllegalArgumentException("null argument");
	}
	for (String key: properties.stringPropertyNames()) {
	    dbProperties.setProperty(key, properties.getProperty(key));
	}
	init();
    }

    boolean rolesSeen = false;
    String ecadmin = null;
    String ecowner = null;
    String ecuser = null;
    boolean hasRoles() {
	return rolesSeen;
    }

    static final Pattern pattern =
	Pattern.compile(Pattern.quote("$(") + "([a-zA-Z0-9_]+([.]"
			+ "[a-zA-Z0-9_]+)*)"
			+ Pattern.quote(")"));

    private static Set<String> getKeySet(Properties dbProperties)
	throws ECDBException
    {
	// do a topological sort using Kahn's algorithm
	HashMap<String,HashSet<String>> inmap =
	    new HashMap<String,HashSet<String>>(64);
	HashMap<String,HashSet<String>> outmap =
	    new HashMap<String,HashSet<String>>(64);
	Set<String> results = new LinkedHashSet<String>(64);
	LinkedList<String> terminals = new LinkedList<String>();
	for (String key: dbProperties.stringPropertyNames()) {
	    inmap.put(key, new HashSet<String>());
	    outmap.put(key, new HashSet<String>());
	}
	for (String key: dbProperties.stringPropertyNames()) {
	    HashSet<String>inmapLinks = inmap.get(key);
	    String value = dbProperties.getProperty(key);
	    if (value == null) continue;
	    Matcher matcher = pattern.matcher(value);
	    int index = 0;
	    while (matcher.find(index)) {
		int start = matcher.start();
		int end = matcher.end();
		String pkey = value.substring(start+2, end-1);
		String pval = dbProperties.getProperty(pkey);
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
	    results.add(n);
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
	    if (entry.getValue().size() > 0) {
		throw new ECDBException("circular substitution");
	    }
	}
	return results;
    }

    private static final String B64KEY_START = "base64.";
    private static final int B64KEY_START_LEN = B64KEY_START.length();

    private static final String EB64KEY_START = "ebase64.";
    private static final int EB64KEY_START_LEN = EB64KEY_START.length();

    private static final String DEFAULT_CELL_EMAIL_TIMEOUT = "30";

    long cellEmailAddrTimeout =
	Long.parseLong(DEFAULT_CELL_EMAIL_TIMEOUT);

    /**
     * Get the timeout for email addresses assocated with a cell phone number.
     * @return the timeout in units of days
     */
    public long getCellEmailAddrTimeout() {return cellEmailAddrTimeout;}


    private void init() throws IOException, ECDBException {
	for (String key: getKeySet(dbProperties)) {
	    String value = dbProperties.getProperty(key).trim();
	    if (value == null || value.length() == 0) continue;
	    if (key.startsWith(B64KEY_START)) {
		byte[] data = Base64.getDecoder().decode(value);
		ByteArrayInputStream is = new ByteArrayInputStream(data);
		StringBuilder sb = new StringBuilder();
		try {
		    CopyUtilities.copyStream(is, sb, UTF8);
		} catch (IOException eio) {}
		key = key.substring(B64KEY_START_LEN);
		dbProperties.setProperty(key, sb.toString());
	    } else if (key.startsWith(EB64KEY_START)) {
		byte[] data = Base64.getDecoder().decode(value);
		ByteArrayInputStream is = new ByteArrayInputStream(data);
		try {
		    key = key.substring(EB64KEY_START_LEN);
		    ProcessBuilder pb = new ProcessBuilder("gpg", "-d");
		    Process process = pb.start();
		    Thread ot = new Thread(() -> {
			    try {
				OutputStream os = process.getOutputStream();
				CopyUtilities.copyStream(is, os);
				os.close();
			    } catch (IOException eio) {
				System.err.println(eio.getMessage());
			    }
		    });
		    ot.start();
		    StringBuilder sb = new StringBuilder();
		    CopyUtilities.copyStream(process.getInputStream(),
					     sb, UTF8);
		    dbProperties.setProperty(key, sb.toString());
		} catch (IOException eio) {
		    System.err.println(eio.getMessage());
		}
	    } else {
		Matcher matcher = pattern.matcher(value);
		int index = 0;
		StringBuilder sb = new StringBuilder();
		while (matcher.find(index)) {
		    int start = matcher.start();
		    int end = matcher.end();
		    String pkey =value.substring(start+2, end-1);
		    sb.append(value.substring(index, start));
		    String pval = dbProperties.getProperty(pkey);
		    if (pval == null) pval = System.getProperty(pkey);
		    if (pval != null) {
			sb.append(pval);
		    }
		    index = end;
		}
		if (index > 0) {
		    sb.append(value.substring(index));
		    value = sb.toString();
		    dbProperties.setProperty(key, value);
		}
	    }
	}
	cellEmailAddrTimeout =
	    Long.parseLong(dbProperties
			   .getProperty("cell.email.timeout",
					DEFAULT_CELL_EMAIL_TIMEOUT));
		       

	String zoneIdString = dbProperties.getProperty("zoneid");
	if (zoneIdString == null || zoneIdString.trim().length() == 0) {
	    zoneId = ZoneId.systemDefault();
	} else {
	    try {
		zoneId = ZoneId.of(zoneIdString);
	    } catch (Exception ze) {
		throw new ECDBException("zoneID", ze);
	    }
	}

	String type = dbProperties.getProperty("type");
	typeSuffix = (type == null)? EMPTY_STRING: "." + type;
	String configRolesString = dbProperties.getProperty("configRoles");
	if (configRolesString != null) {
	    configRolesString = configRolesString.trim();
	} else {
	    configRolesString = "FALSE";
	}
	if (configRolesString.equalsIgnoreCase("true")) {
	    configRoles = true;
	} else if (configRolesString.equalsIgnoreCase("false")) {
	    configRoles = false;
	} else {
	    throw new ECDBException();
	}
	if (configRoles) {
	    ecadmin = dbProperties.getProperty("ECADMIN");
	    ecowner = dbProperties.getProperty("ECOWNER");
	    ecuser = dbProperties.getProperty("ECUSER");
	    ecadmin = (ecadmin == null)? null: ecadmin.trim();
	    ecowner = (ecowner == null)? null: ecowner.trim();
	    ecuser = (ecuser == null)? null: ecuser.trim();
	    if (ecadmin == null || ecadmin.length() == 0
		|| ecowner == null || ecowner.length() == 0
		|| ecuser == null || ecuser.length() == 0) {
		throw new ECDBException();
	    }
	}	    

	String configAuthString = dbProperties.getProperty("configAuth");
	if (configAuthString != null) {
	    configAuthString = configAuthString.trim();
	} else {
	    configAuthString = "FALSE";
	}
	if (configAuthString.equalsIgnoreCase("true")) {
	    configAuth = true;
	} else if (configAuthString.equalsIgnoreCase("false")) {
	    configAuth = false;
	} else {
	    throw new ECDBException();
	}

	if (dbProperties.getProperty("ECSCHEMA") == null) {
	    dbProperties.setProperty("ECSCHEMA", "EventCalendar");
	}

	String sqlfname = dbProperties.getProperty("file.sql.xml");
	if (sqlfname.trim().equals("(Default)")) sqlfname = null;
	File sqlf = (sqlfname == null)? null: new File(sqlfname);
	try {
	    InputStream is = ECDB.class.getResourceAsStream("sql.xml");
	    sqlProperties.loadFromXML(is);
	    is.close();
	    Properties tmp = new Properties();
	    if (sqlf != null) {
		is = new FileInputStream(sqlf);
		tmp.loadFromXML(is);
		is.close();
		for (String key: tmp.stringPropertyNames()) {
		    sqlProperties.setProperty(key, tmp.getProperty(key));
		}
	    }
	    String states = getSQLProperty("sqlShutdownState");
	    for (String state: states.split(",")) {
		state = state.trim();
		sqlShutdownStates.add(state);
	    }
	} catch (IOException e) {
	    if (sqlf == null) {
		throw new UnexpectedExceptionError(e);
	    } else {
		throw e;
	    }
	}
    }

    String getSQLProperty(String key) {
	String result = sqlProperties.getProperty(key + typeSuffix);
	if (result == null) result = sqlProperties.getProperty(key);
	if (result != null) {
	    result = result.replace("ECSCHEMA",
				    dbProperties.getProperty("ECSCHEMA",
							     "EventCalendar"));
	    if (configRoles) {
		result = result.replace("ECADMIN", ecadmin)
		    .replace("ECOWNER", ecowner).replace("ECUSER", ecuser);
	    }
	}
	return result.trim();
    }
    private boolean isClosed = false;
    private boolean hasOpenedAConnection = false;

    private HashSet<Connection> connections = new HashSet<>();

    /**
     * Get a database connection.
     * @return a connection
     */
    public Connection getConnection() throws SQLException {
	return getConnection(false);
    }

    /**
     * Determine if this session has been closed.
     * @return true if the session has been closed; false otherwise
     */
    public boolean isClosed() {return isClosed;}

    private Connection getConnection(boolean create) throws SQLException {
	Iterator<Connection> it = connections.iterator();
	while (it.hasNext()) {
	    Connection c = it.next();
	    if (c.isClosed()) {
		it.remove();
	    }
	}
	if (connections.size() > 0) {
	    create = false;
	}
	String url = create? dbProperties.getProperty("createURL"):
	    dbProperties.getProperty("openURL");
	Connection connection =
	    DriverManager.getConnection(url,
					getConnectionProperties());
	hasOpenedAConnection =  true;
	connections.add(connection);
	isClosed = false;
	return connection;
    }

    /**
     * Close this ECDB session.
     */
    public void close() throws SQLException {
	Iterator<Connection> it = connections.iterator();
	while (it.hasNext()) {
	    Connection c = it.next();
	    c.close();
	    it.remove();
	}
	if (connections.size() == 0 && hasOpenedAConnection) {
	    String shutdownURL = dbProperties.getProperty("shutdownURL");
	    if (shutdownURL != null) {
		try {
		    DriverManager.getConnection(shutdownURL);
		} catch (SQLException e) {
		    String estate = e.getSQLState();
		    if (!sqlShutdownStates.contains(estate)) {
			
			throw e;
		    }
		}
	    }
	    isClosed = true;
	}
    }

    private static final String  CKEY_START = "connection.";
    private static final int CKEY_START_LEN = CKEY_START.length();

    private Properties getConnectionProperties() {
	Properties connectionProperties = new Properties();

	for (String key: dbProperties.stringPropertyNames()) {
	    if (key.startsWith(CKEY_START)) {
		String value = dbProperties.getProperty(key);
		key = key.substring(CKEY_START_LEN);
		connectionProperties.setProperty(key, value);
	    }
	}
	return connectionProperties;
    }
    
    private static final String EKEY_START = "email.";
    private static final int EKEY_START_LEN = EKEY_START.length();

    /**
     * Get the full email address for a user.
     * A full email address consists of the user's name followed by
     * an email address delimited by '&lt;' and '&gt;'.
     * @param conn the database connection
     * @param userID the recipient's user ID
     * @param useEmail true if the recipient's email address will be used;
     *        false if the MMS email address for the recipient's cell phone
     *        is used.
     */
    public String getFullEmailAddress(Connection conn, int userID,
				      boolean useEmail)
	throws SQLException
    {
	String firstName = null;
	String lastName = null;
	boolean lnf = false;
	String emailAddr = null;
	String prefix = null;
	String cellNumber  = null;
	int carrierID = -1;
	try (PreparedStatement ps =
	     conn.prepareStatement(getSQLProperty("getUserInfoData"))) {
	    ps.setInt(1, userID);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    firstName = rs.getString(2);
		    lastName = rs.getString(3);
		    lnf = rs.getBoolean(4);
		    if (useEmail) {
			emailAddr = rs.getString(6);
		    } else {
			prefix = rs.getString(7);
			cellNumber = rs.getString(8);
			carrierID = rs.getInt(9);
		    }
		}
	    }
	}
	String fullName = (lnf)? lastName + " " + firstName:
	    firstName + " " + lastName;
	
	if (useEmail == false) {
	    emailAddr = CellEmailFinder.lookup(this, conn, prefix, cellNumber,
					       carrierID);
	}
	if (emailAddr == null) return null;
	else {
	    return fullName + " <" + emailAddr + ">";
	}
    }

    Properties getEmailProperties()
	throws SQLException
    {
	Properties emailProperties = new Properties();
	for (String key: dbProperties.stringPropertyNames()) {
	    if (key.startsWith(EKEY_START)) {
		String value = dbProperties.getProperty(key);
		key = key.substring(EKEY_START_LEN);
		emailProperties.setProperty(key, value);
	    }
	}
	return emailProperties;
    }

    static final String netURLPattern =
	"([a-zA-Z][a-zA-Z0-9.+-]+:)+//[^/:]+:[0-9]+/.*";

    static final String AUTHUSER = "auth.user.";
    static final int AUTHUSER_LEN = AUTHUSER.length();

    /**
     * Create a database.
     * This method uses information in the configuration file
     * to create a database's tables and, when possible, the
     * database itself.
     */
    public void createDB() throws IOException, SQLException
    {
	String dbname = dbProperties.getProperty("dbName", "ecdb");
	String dbpath = dbProperties.getProperty("dbPath");
	String createURL = dbProperties.getProperty("createURL");
	String shutdownURL = dbProperties.getProperty("shutdownURL");
	Properties connectionProperties = getConnectionProperties();

	if (!createURL.matches(netURLPattern)) {
	    String[] pathComponents = dbpath.split("/");
	    String first = pathComponents[0];
	    String[] rest = new String[pathComponents.length-1];
	    System.arraycopy(pathComponents, 1, rest, 0,
			     pathComponents.length-1);
	    File dbpathF = ((rest.length == 0)? Path.of(first):
			    Path.of(first, rest))
		.toFile();
	    if (dbpathF.exists()) {
		if (!dbpathF.isDirectory()) {
		    throw new IOException("directory for db missing");
		}
	    } else {
		dbpathF.mkdirs();
	    }
	}
	CollectionScanner<String>scanner = new CollectionScanner<>();
	String buffer = getSQLProperty("init");
	if  (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    }
	}

	TreeSet<Integer> aset = new TreeSet<>();
	if (configAuth) {
	    buffer = getSQLProperty("initForAuth");
	    if (buffer != null) {
		buffer = buffer.replaceAll("\\s+", " ");
		if (buffer.endsWith(";")) {
		    buffer = buffer.substring(0, buffer.length() - 1);
		}
		if (buffer.length() > 0) {
		    scanner.add(Arrays.asList(buffer.split(";")));
		}
	    }
	    String addUser = getSQLProperty("addUser");
	    if (addUser != null) {
		for (String key: dbProperties.stringPropertyNames()) {
		    if (key.startsWith(AUTHUSER)) {
			String key1 = key.substring(AUTHUSER_LEN);
			if (!key1.matches("[0-9]+")) continue;
			Integer ikey = Integer.valueOf(key1);
			aset.add(ikey);
		    }
		}
	    }
	}

	if (configRoles) {
	    buffer = getSQLProperty("initForRoles");
	    if  (buffer != null &&  buffer.length() > 0) {
		buffer = buffer.replaceAll("\\s+", " ");
		if (buffer.endsWith(";")) {
		    buffer = buffer.substring(0, buffer.length() - 1);
		}
		if (buffer.length() > 0) {
		    scanner.add(Arrays.asList(buffer.split(";")));
		}
	    }
	}

	try (Connection conn = getConnection(true)) {
	    Statement statement = null;
	    try {
		conn.setAutoCommit(false);
		statement = conn.createStatement();
		for (String s: scanner) {
		    try {
			s = s.trim();
			if (s.length() > 0) {
			    // System.out.println(s);
			    statement.execute(s);
			}
		    } catch (SQLException e) {
			System.err.format("%s\n", s);
			throw e;
		    }
		}
		if (configAuth) {
		    String addUser = getSQLProperty("addUser");
		    if (addUser != null && addUser.length() > 0) {
			for (Integer ikey: aset) {
			    String userkey = "auth.user." + ikey;
			    String pwkey = "auth.password." + ikey;
			    String user = dbProperties.getProperty(userkey);
			    String password = dbProperties.getProperty(pwkey);
			    String cmd = String.format((Locale)null, addUser,
						       user, password);
			    try {
				// System.out.println(cmd.trim());
				statement.execute(cmd.trim());
			    } catch (SQLException e) {
				System.err.format
				    ("ERROR ON SQL STATEMENT: %s\n",
				     cmd.trim());
				throw e;
 			    }
			}
		    }
		}
		conn.commit();
	    } catch (SQLException e2) {
		try {
		    System.err.println("Rolling back DB creation");
		    conn.rollback();
		} catch (SQLException e3) {
		    System.err.println("SQL exception during rollback");
		}
		throw e2;
	    } finally {
		if (statement != null) {
		    statement.close();
		}
		conn.setAutoCommit(true);
	    }
	}
    }

    private void processSQL(CollectionScanner<String> scanner)
	throws SQLException
    {
	try (Connection conn = getConnection()) {
	    Statement statement = null;
	    try {
		conn.setAutoCommit(false);
		statement = conn.createStatement();
		for (String s: scanner) {
		    try {
			statement.execute(s.trim());
		    } catch (SQLException e) {
			System.err.format("%s\n", s.trim());
			throw e;
		    }
		}
	    } catch (SQLException e2) {
		try {
		    System.err.println("Rolling back to previous commit");
		    conn.rollback();
		} catch (SQLException e3) {
		    System.err.println("SQL exception during rollback");
		}
		throw e2;
	    } finally {
		if (statement != null) {
		    statement.close();
		}
		conn.setAutoCommit(true);
	    }
	}
    }


    /**
     * Configure a database to allow roles specified in the
     * configuration file.
     */
    public void allowRoles() throws IOException, SQLException
    {
	if (hasRoles()) {
	    try (Connection conn = getConnection()) {
		Statement statement = conn.createStatement();
		try {
		    String buf = getSQLProperty("hasRoles");
		    if (buf != null) {
			ResultSet rs = statement.executeQuery(buf);
			if (rs.next() == true) {
			    return;
			}
		    }
		} catch (Exception e) {
		} finally {
		    if (statement != null) statement.close();
		}
	    }

	    CollectionScanner<String>scanner = new CollectionScanner<>();
	    String buffer = getSQLProperty("roles");
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    scanner.add(Arrays.asList(buffer.split(";")));
	    processSQL(scanner);
	    rolesSeen = true;
	}
    }

    /**
     * Create a database's ECDB tables.
     */
    public void createTables() throws IOException, SQLException
    {

	try (Connection c =  getConnection()) {
	    Statement statement = null;
	    String buf = getSQLProperty("hasSchemas");
	    buf = buf.replaceAll("\\s+", " ");
	    if (buf.endsWith(";")) {
		buf = buf.substring(0, buf.length() - 1);
	    }
	    if (buf != null) {
		try {
		    statement = c.createStatement();
		    if (statement.execute(buf)) {
			ResultSet rs = statement.getResultSet();
			if (rs.next()) {
			    return;
			}
		    }
		} finally {
		    if (statement != null) statement.close();
		}
	    }
	}

	CollectionScanner<String>scanner = new CollectionScanner<>();
	String buffer = null;
	if (configRoles) {
	    buffer = getSQLProperty("roles");
	    if (buffer != null) {
		buffer = buffer.replaceAll("\\s+", " ");
		if (buffer.endsWith(";")) {
		    buffer = buffer.substring(0, buffer.length() - 1);
		}
		if (buffer.length() > 0) {
		    scanner.add(Arrays.asList(buffer.split(";")));
		} else {
		    System.out.println("null roles");
		}
	    }
	}

	
	buffer = getSQLProperty("schemas");
	if  (buffer != null) {
	}
	if (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    } else {
		System.out.println("null schemas");
	    }
	}

	buffer = getSQLProperty("tables");

	if (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    } else {
		System.out.println("null tables");
	    }
	}

	buffer = getSQLProperty("addCountryPrefixes");
	if (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    } else {
		System.out.println("null addCountryPrefixes");
	    }
	}

	buffer = getSQLProperty("initialCarriers");
	if (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    } else {
		System.out.println("null initialCarriers");
	    }
	}

	buffer = getSQLProperty("initialCarrierMap");
	if (buffer != null) {
	    buffer = buffer.replaceAll("\\s+", " ");
	    if (buffer.endsWith(";")) {
		buffer = buffer.substring(0, buffer.length() - 1);
	    }
	    if (buffer.length() > 0) {
		scanner.add(Arrays.asList(buffer.split(";")));
	    } else {
		System.out.println("null initialCarrierMap");
	    }
	}

	createGrants(scanner);

	processSQL(scanner);
    }

    static final String AUTHROLES = "auth.roles.";
    static final int AUTHROLES_LEN = AUTHROLES.length();

    private void createGrants(CollectionScanner<String> scanner)
    {
	if (configRoles) {
	    String buffer = getSQLProperty("grants");
	    if (buffer != null) {
		buffer = buffer.replaceAll("\\s+", " ");
		if (buffer.endsWith(";")) {
		    buffer = buffer.substring(0, buffer.length() - 1);
		}
		if (buffer.length() > 0) {
		    scanner.add(Arrays.asList(buffer.split(";")));
		}
	    }
	    TreeSet<Integer> adminSet = new TreeSet<Integer>();
	    TreeSet<Integer> ownerSet = new TreeSet<Integer>();
	    TreeSet<Integer> userSet = new TreeSet<Integer>();
	    String adminName = dbProperties.getProperty("ECADMIN").trim()
		.toUpperCase();
	    String ownerName = dbProperties.getProperty("ECOWNER").trim()
		.toUpperCase();
	    String userName = dbProperties.getProperty("ECUSER").trim()
		.toUpperCase();
	    StringBuilder sb = new StringBuilder();
	    for (String key: dbProperties.stringPropertyNames()) {
		if (!key.startsWith(AUTHROLES)) continue;
		String s = dbProperties.getProperty(key);
		if (s != null) {
		    key = key.substring(AUTHROLES_LEN);
		    Integer i = Integer.valueOf(key);
		    String[] roles = s.split(",");
		    for (String role: roles) {
			role = role.trim().toUpperCase();
			if (role.equals(adminName)) {
			    adminSet.add(i);
			} else if (role.equals(ownerName)) {
			    ownerSet.add(i);
			} else if (role.equals("userName")) {
			    userSet.add(i);
			}
		    }
		    sb.append("GRANT ");
		    sb.append(adminName + " TO ");
		    boolean notFirst = false;
		    for (Integer ii: adminSet) {
			if (notFirst) {
			    sb.append(", ");
			} else {
			    notFirst = true;
			}
			sb.append(dbProperties.getProperty("auth.user"
							+ ii.toString())
				  .trim());
		    }
		    sb.append(";");
		    sb.append("GRANT ");
		    sb.append(ownerName + " TO ");
		    notFirst = false;
		    for (Integer ii: ownerSet) {
			if (notFirst) {
			    sb.append(", ");
			} else {
			    notFirst = true;
			}
			sb.append(dbProperties.getProperty("auth.user"
							+ ii.toString())
				  .trim());
		    }
		    sb.append(";");
		    sb.append("GRANT ");
		    sb.append(userName + " TO ");
		    notFirst = false;
		    for (Integer ii: userSet) {
			if (notFirst) {
			    sb.append(", ");
			} else {
			    notFirst = true;
			}
			sb.append(dbProperties.getProperty("auth.user"
							+ ii.toString())
				  .trim());
		    }
		}
	    }
	    if (sb.length() > 0) {
		scanner.add(Arrays.asList(sb.toString().split(";")));
	    }
	}
    }


    private static final String EMPTY_STRING = "";
 
    /**
     * Add a cellphone carrier.
     * @param conn the database connection
     * @param carrier the carrier's name
     */
    public void addCarrier(Connection conn, String carrier)
	throws SQLException
    {
	try {
	    if (carrier == null || carrier.trim().length() == 0) {
		throw new IllegalArgumentException("no carrier provided");
	    }
	    carrier = carrier.trim().replaceAll("\\s\\s+", " ").toUpperCase();
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps
		 = conn.prepareStatement(getSQLProperty("addCarrier"))) {
		ps.setString(1, carrier);
		ps.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Add  cellphone carriers.
     * @param conn the database connection
     * @param carriers an array containing the carrier's name
     */
    public void addCarrier(Connection conn, String[] carriers)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps
		 = conn.prepareStatement(getSQLProperty("addCarrier")))  {
		for (String carrier: carriers) {
		    if (carrier == null) continue;
		    carrier = carrier.trim();
		    if (carrier.length() == 0) continue;
		    ps.setString(1, carrier.replaceAll("\\s\\s+", " ")
				 .toUpperCase());
		    ps.executeUpdate();
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a carrier by ID.
     * @param conn the database connection
     * @param carrierID the carrier's ID
     */
    public void deleteCarrier(Connection conn, int carrierID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteCarrierByID"))) {
		ps2.setInt(1, carrierID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a carrier by name.
     * @param conn the database connection
     * @param carrier the carrier's name
     */
    public void deleteCarrier(Connection conn, String carrier)
	throws SQLException
    {
	if (carrier == null || carrier.trim().length() == 0) {
	    throw new IllegalArgumentException("no carrier provided");
	}
	carrier = carrier.trim().replaceAll("\\s\\s+", "").toUpperCase();
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty("findCarrier"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteCarrierByID"))) {
		    // System.out.format("finding carrier \"%s\"\n", carrier);
		    ps1.setString(1, carrier);
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			int carrierID = rs.getInt(1);
			// System.out.println("using carrierID " + carrierID);
			ps2.setInt(1, carrierID);
			ps2.executeUpdate();
		    } else {
			System.err.format("Carrier '%s' does not exist\n",
					  carrier);
			throw new SQLException("no such entry in Carrier table: "
					       + carrier);
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete carriers by name.
     * @param conn the database connection
     * @param carriers an array containing the names of the carriers to delete
     */
    public void deleteCarrier(Connection conn, String[] carriers)
	throws SQLException
    {
	for (String carrier: carriers) {
	    if (carrier == null || carrier.trim().length() == 0) {
		throw new IllegalArgumentException("no carrier provided");
	    }
	}
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty("findCarrier"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteCarrierMatching"))) {
		    for (String carrier: carriers) {
			carrier = carrier.trim().replaceAll("\\s\\s+", " ")
			    .toUpperCase();
			ps1.setString(1, carrier);
			ResultSet rs = ps1.executeQuery();
			if (rs.next()) {
			    int carrierID = rs.getInt(1);
			    ps2.setInt(1, carrierID);
			    ps2.executeUpdate();
			} else {
			    System.err.format("Carrier '$s' does not exist\n",
					      carrier);
			    throw new SQLException
				("no such entry in Carrier table: " + carrier);
			}
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete carriers by ID.
     * @param conn the database connection
     * @param carrierIDs an array containing the IDs of the carriers to delete
     */
    public void deleteCarrier(Connection conn, int[] carrierIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteCarrierByID"))) {
		for (int carrierID: carrierIDs) {
		    ps2.setInt(1, carrierID);
		    ps2.executeUpdate();
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteCarrier");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    private static final String[] emptyStringArray = new String[0];

    /**
     * List all carriers by name and ID.
     * @param conn the database connection
     * @return an array, each element of which specifies a carrier ID and
     *         the corresponding name
     */
    public CarrierLabeledID[] listCarrierLabeledIDs(Connection conn)
	throws SQLException
    {
	Vector<Vector<Object>> vector =
	    listCarriers(conn, emptyStringArray, true);
	CarrierLabeledID[] result = new CarrierLabeledID[vector.size()];
	int i = 0;
	for (Vector<Object> row: vector) {
	    result[i++] = (CarrierLabeledID)row.get(1);
	}
	return result;
    }

    /**
     * List carriers given name-matching patterns.
     * The vector returned represents a table.
     * @param conn the database connection
     * @param patterns the search patterns
     * @param full true if if the table rows contains a carrier ID followed
     *        by a {@link CarrierLabeledID}; false if the table contains
     *        only a carrier ID in each row
     * @return a table.
     */
    public Vector<Vector<Object>>
	listCarriers(Connection conn, String[] patterns, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    String q = null;
	    if (patterns == null || patterns.length == 0) {
		q = getSQLProperty("listCarriers");
		patterns = null;
	    } else {
		q = getSQLProperty("listCarriersMatching");
	    }
	    if (patterns == null) {
		try (ResultSet rs = statement.executeQuery(q)) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    int carrierID = rs.getInt(1);
			    row.add(carrierID);
			    row.add(new CarrierLabeledID
				    (carrierID, rs.getObject(2, String.class)));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1, Integer.class));
			    vector.add(row);
			}
		    }
		}
	    } else {
		try (PreparedStatement ps = conn.prepareStatement(q)) {
		    for (String pattern: patterns) {
			ps.setString(1, pattern.toUpperCase());
			try (ResultSet rs = ps.executeQuery()) {
			    while (rs.next()) {
				if (full) {
				    Vector<Object> row = new Vector<Object>(2);
				    int carrierID = rs.getInt(1);
				    row.add(carrierID);
				    row.add(new CarrierLabeledID
					    (carrierID,
					     rs.getObject(2, String.class)));
				    vector.add(row);
				} else {
				    Vector<Object> row = new Vector<Object>(1);
				    row.add(rs.getObject(1));
				    vector.add(row);
				}
			    }
			}
		    }
		}
	    }
	}
	return vector;
    }

    /**
     * List carriers given their IDs.
     * The vector returned represents a table, each row ofwhich
     * contains a carrier ID followed optionally by a string. Each
     * carrier ID is an integer and each carrier name is a string.
     * @param conn the database connection
     * @param ids an array of carrier IDs.
     * @param full true if if the table rows contains a carrier ID followed
     *        by the carrier name; false if the table contains
     *        only a carrier ID in each row
     * @return a table.
     */
    public Vector<Vector<Object>>
	listCarriers(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listCarriers"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listCarrier"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1, Integer.class));
			row.add(rs.getObject(2, String.class));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1, Integer.class));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	return vector;
    }
  
    /**
     * Get a carrier ID given a carrier name.
     * @param conn the database connection
     * @param carrier the carrier name
     * @return the carrier ID corresponding to a carrier name; -1 if there
     *         is none.
     */
    int findCarrier(Connection conn, String carrier)
	throws SQLException, IllegalArgumentException
    {
	if (carrier == null || carrier.trim().length() == 0) {
	    throw new IllegalArgumentException("no carrier provided");
	}

	String query = getSQLProperty("findCarrier");
	try (PreparedStatement ps = conn.prepareStatement(query)) {
	    ps.setString(1, carrier);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    return rs.getInt(1);
		} else {
		    return -1;
		}
	    }
	}
    }

    /**
     * Set the name of a carrier.
     * @param conn the database connection
     * @param carrierID a carrierID
     * @param carrierName the new carrier name
     */
    public void setCarrier(Connection conn, int carrierID, String carrierName)
	throws IllegalArgumentException, SQLException
    {
	setCarrier(conn, carrierID, carrierName, true);
    }

    /**
     * Set the name of a carrier, with control over database commits.
     * @param conn the database connection
     * @param carrierID a carrierID
     * @param carrierName the new carrier name
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setCarrier(Connection conn, int carrierID, String carrierName,
			   boolean commit)
	throws IllegalArgumentException, SQLException
    {
	if (carrierID == -1) {
	    throw new IllegalArgumentException();
	}

	if (carrierName != null) {
	    carrierName = carrierName.trim();
	    if (carrierName.length() == 0) carrierName = null;
	}
	if (carrierName != null) {
	    carrierName = carrierName.toUpperCase();
	}
	String q = getSQLProperty("updateCarrier");
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(q)) {
		if (carrierName == null) {
		    ps.setNull(1, Types.VARCHAR);
		} else {
		    ps.setString(1, carrierName);
		}
		ps.setInt(2, carrierID);
		ps.executeUpdate();
		if (commit) conn.commit();
	    } catch (SQLException e) {
		try {
		    System.err.println("Rolling back setCarrier");
		    if (commit) conn.rollback();
		} catch (SQLException ee) {
		    System.err.println("SQL exception during rollback");
		}
		throw e;
	    }
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * List a carrier map.
     * The return value represents a table, each row of which contains
     * a country prefix, a carrierID or a carrier labeled-ID, and an
     * email domain of a gateway to the MMS service.
     * @param conn the database connection
     * @param countryPrefix the country prefix or code
     * @param carrier the name of a carrier
     * @param full true if the second row in the table is a carrier labeled-ID;
     *             false if it is a carrier ID
     * @return a table containing matching entries
     */
    Vector<Vector<Object>> listCarrierMap(Connection conn,
					  String countryPrefix,
					  String carrier,
					  boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	boolean usesID = false;
	int carrierID = 0;
	if (countryPrefix != null && countryPrefix.length() == 0) {
	    countryPrefix = null;
	}
	if (carrier != null && carrier.length() == 0) {
	    carrier = null;
	}
	if (carrier != null && carrier.matches("[1-9][0-9]+")) {
	    usesID = true;
	    try {
		carrierID = Integer.parseInt(carrier);
	    } catch (Exception e) {}
	}
	boolean all = countryPrefix == null && carrier == null;
	String query = all? getSQLProperty("sortedCarrierMap"):
	    (usesID? getSQLProperty("findCarrierMapByID"):
	     getSQLProperty("findCarrierMap"));

	try (PreparedStatement ps = conn.prepareStatement(query)) {
	    if (all == false) {
		ps.setString(1, (countryPrefix == null)? "%": countryPrefix);
		if (usesID) {
		    ps.setInt(2, carrierID);
		} else {
		    ps.setString(2, ((carrier == null)? "%": carrier));
		}
	    }
	    ResultSet rs = ps.executeQuery();
	    while (rs.next()) {
		Vector<Object> row = new Vector<Object>(3);
		row.add(rs.getObject(1, Object.class));
		if (full) {
		    int cid = (Integer)rs.getObject(2, Object.class);
		    carrier = (String)rs.getObject(3, Object.class);
		    row.add(new CarrierLabeledID(cid, carrier));
		    /*
		    row.add(cid + " (" + rs.getObject(3, Object.class)
			    + ")");
		    */
		} else {
		    row.add(rs.getObject(2, Object.class));
		}
		row.add(rs.getObject(4, Object.class));
		vector.add(row);
	    }
	}
	return vector;
    }

    /**
     * List a carrier map.
     * The return value represents a table, each row of which contains
     * a country prefix, a carrierID or a carrier labeled-ID, and an
     * email domain of a gateway to the MMS service.
     * @param conn the database connection
     * @param countryPrefix the country prefix or code
     * @param carrierID the carrier ID
     * @return the email domain used for MMS messages sent via email; null
     *         if there is none
     */
    public String getCarrierDomain(Connection conn, String countryPrefix,
				   int carrierID)
	throws SQLException
    {
	String s = getSQLProperty("getCarrierMapDomain");
	try (PreparedStatement ps = conn.prepareStatement(s)) {
	    ps.setString(1, countryPrefix);
	    ps.setInt(2, carrierID);
	    ResultSet rs = ps.executeQuery();
	    if (rs.next()) {
		String result = rs.getString(1);
		if (result != null) {
		    result = result.trim();
		    if (result.length() == 0) result = null;
		}
		return result;
	    } else {
		return null;
	    }
	}
    }

    /**
     * Set the carrier mapping.
     * @param conn the database connection
     * @param countryPrefix the country prefix or code
     * @param carrierID the carrier ID
     * @param domain the domain name for the email gateway used to forward
     *        an email to a carrier's MMS service
     */
    public void setCarrierMapping(Connection conn, String countryPrefix,
				  int carrierID, String domain)
	throws SQLException, IllegalArgumentException
    {
	setCarrierMapping(conn, countryPrefix, carrierID, domain, true);
    }

    /**
     * Set the carrier mapping.
     * @param conn the database connection
     * @param countryPrefix the country prefix or code
     * @param carrierID the carrier ID
     * @param domain the domain name for the email gateway used to forward
     *        an email to a carrier's MMS service
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setCarrierMapping(Connection conn, String countryPrefix,
				  int carrierID, String domain,
				  boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (countryPrefix == null) {
	    throw new IllegalArgumentException
		("No countryPrefix (country calling code)");
	}
	if (domain == null) {
	    throw new IllegalArgumentException("No domain");
	}
	if (carrierID == 1) {
	    throw new IllegalArgumentException("'OTHER' carrier reserved");
	}
	if  (countryPrefix.length() == 0 || countryPrefix.startsWith("0")) {
	    throw new IllegalArgumentException
		("No countryPrefix (country calling code): " + countryPrefix);
	}
	if (!countryPrefix.matches("[1-9][0-9]*")) {
	    throw new IllegalArgumentException("bad country calling code");
	}

	if (domain.length() == 0) {
	    domain = null;
	}

	PreparedStatement ps = null;
	try {
	    if (commit) conn.setAutoCommit(false);
	    String d = getCarrierDomain(conn, countryPrefix, carrierID);
	    if (d == null && domain == null) {
		// special case: trying to delete a non-existent entry.
		return;
	    }
	    ps = (domain == null)?
		conn.prepareStatement(getSQLProperty("deleteFromCarrierMap")):
		((d == null)?
		 conn.prepareStatement(getSQLProperty("addToCarrierMap")):
		 conn.prepareStatement(getSQLProperty("updateCarrierDomain")));
	    int ind = 1;
	    if (domain != null) {
		ps.setString(ind++, domain);
	    }
	    ps.setString(ind++, countryPrefix);
	    ps.setInt(ind++, carrierID);
	    ps.executeUpdate();
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setCarrierMapping");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (ps != null) ps.close();
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a new user.
     * @param conn the database connection
     * @param firstName the user's first name
     * @param lastName the user's last name
     * @param title the user's title; null if there is none
     * @param lastNameFirst true if the first name follows the last
     *        name when printed; false if the last name follows the
     *        first name
     * @param emailAddr the user's email address
     * @param countryPrefix the user's country prefix (1 for the U.S.)
     * @param cellNumber the user's cell phone number
     * @param carrierID the ID for the user's cell phone carrier
     */
    public void addUserInfo(Connection conn,
			    String firstName, String lastName,
			    boolean lastNameFirst, String title,
			    String emailAddr, String countryPrefix,
			    String cellNumber, int carrierID)
	throws SQLException
    {
	addUserInfo(conn, firstName, lastName, lastNameFirst, title,
		    emailAddr, countryPrefix, cellNumber, carrierID, true);
    }

    /**
     * Add a new user, indicating if the new table entry should be committed.
     * @param conn the database connection
     * @param firstName the user's first name
     * @param lastName the user's last name
     * @param title the user's title; null if there is none
     * @param lastNameFirst true if the first name follows the last
     *        name when printed; false if the last name follows the
     *        first name
     * @param emailAddr the user's email address
     * @param countryPrefix the user's country prefix (1 for the U.S.)
     * @param cellNumber the user's cell phone number
     * @param carrierID the ID for the user's cell phone carrier
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addUserInfo(Connection conn,
			    String firstName, String lastName,
			    boolean lastNameFirst, String title,
			    String emailAddr, String countryPrefix,
			    String cellNumber, int carrierID,
			    boolean commit)
	throws SQLException
    {
	if  (countryPrefix == null || countryPrefix.trim().length() == 0) {
	    countryPrefix = "0";
	}
	if (firstName != null) {
	    firstName = firstName.trim().replaceAll("\\s+\\s*", " ");
	    if (firstName.length() == 0) firstName = null;
	}
	if (lastName != null) {
	    lastName = lastName.trim(). replaceAll("\\s+\\s*", " ");
	    if (lastName.length() == 0) lastName = null;
	}
	if (cellNumber != null) {
	    cellNumber = cellNumber.trim().replaceAll("\\s|[()-]", "");
	    if (cellNumber.length() == 0) cellNumber = null;
	    if (cellNumber != null && !cellNumber.matches("[0-9]*")) {
		throw new IllegalArgumentException("bad cell-phone number");
	    }
	}
	if (emailAddr != null) {
	    emailAddr = emailAddr.trim();
	    if (emailAddr.length() == 0) {
		emailAddr = null;
	    }
	}
	if (title != null) {
	    title = title.trim();
	    if (title.length() == 0) {
		title = null;
	    }
	}
	String s = getSQLProperty("insertUserInfo");

	try {
	    if (commit) conn.setAutoCommit(true);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		if (carrierID == -1) carrierID = findCarrier(conn, "OTHER");
		ps.setString(1, firstName);
		ps.setString(2, lastName);
		ps.setBoolean(3, lastNameFirst);
		ps.setString(4, title);
		ps.setString(5, emailAddr);
		ps.setString(6, countryPrefix);
		ps.setString(7, cellNumber);
		ps.setInt(8, carrierID);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addUserInfo");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(false);
	}
    }
			    
    /**
     *
     */
    public void deleteUserInfo(Connection conn, int userID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteUserInfoByID"))) {
		ps2.setInt(1, userID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteUserInfo");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete user data.
     * Each deletion must be confirmed.
     * @param conn the database connection
     * @param pattern a pattern used to find users to delete
     */
    public void deleteUserInfo(Connection conn, String pattern)
	throws SQLException
    {
	deleteUserInfo(conn, pattern, false);
    }

    /**
     * Delete user data optionally asking for confirmation.
     * @param conn the database connection
     * @param pattern a pattern used to find users to delete
     * @param force true if all matching users should be deleted; false if
     *        each deletion must be confirmed
     */
    public void deleteUserInfo(Connection conn, String pattern, boolean force)
	throws SQLException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s\\s+", "");
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty("findUserInfo"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteUserInfoByID"))) {
		    // System.out.format("finding user(s) \"%s\"\n", pattern);
		    ps1.setString(1, pattern);
		    ps1.setString(2, pattern);
		    ps1.setString(3, pattern);
		    ps1.setString(4, pattern);
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    int userID = rs.getInt(1);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    boolean lnf = rs.getBoolean(3);
				    String response = console.readLine
					("deleting user %d: %s %s<%s>"
					 + "[Yn!<ESC>]:",

					 userID,
					 rs.getString(lnf? 3:2),
					 rs.getString(lnf? 2:3),
					 rs.getString(4)).trim();
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, userID);
			    ps2.executeUpdate();
			} while (rs.next());
		    } else {
			System.err.format("User matching '%s' does not exist\n",
					  pattern);
			throw new SQLException("no such entry in "
					       + "UserInfo table: "
					       + pattern);
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteUserInfo");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete user data based on user IDs.
     * @param conn the database connection
     * @param userIDs the user IDs for the users that are to be deleted
     */
    public void deleteUserInfo(Connection conn, int[] userIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteUserInfoByID"))) {
		for (int userID: userIDs) {
		    ps2.setInt(1, userID);
		    ps2.executeUpdate();
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteUserInfo");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Get a user labeled ID for a specified user
     * @param conn the database connection
     * @param userID the user ID
     */
    public UserLabeledID getUserLabeledID(Connection conn, int userID)
	throws IllegalArgumentException, SQLException
    {
	if (userID == -1) throw new IllegalArgumentException();
	int[] ids = {userID};
	Vector<Vector<Object>> vector = listUserInfo(conn, ids, true);
	if (vector != null && vector.size() == 1) {
	    Vector<Object> row = vector.get(0);
	    String firstName = (String) row.get(1);
	    String lastName = (String) row.get(2);
	    boolean lnf = (Boolean) row.get(3);
	    String email = (String) row.get(5);
	    String cellPhone = (String) row.get(7);
	    String label = (lnf? lastName + " " + firstName:
			    firstName + " " + lastName)
		+ ((email != null)? " <" + email + ">":
		   (cellPhone != null)? " (" + cellPhone + ")": "");
	    return new UserLabeledID(userID, label);
	}
	return null;
    }

    /**
     * list the user labeled IDs for a specified pattern
     * @param conn the database connection
     * @param pattern the pattern
     */
    public UserLabeledID[] listUserLabeledIDs(Connection conn, String pattern)
	throws SQLException
    {
	Vector<Vector<Object>> vector = listUserInfo(conn, pattern, true);
	UserLabeledID[] labeledIDs = new UserLabeledID[vector.size()];
	int i = 0;
	for (Vector<Object>row: vector) {
	    int userID = (Integer)row.get(0);
	    String firstName = (String) row.get(1);
	    String lastName = (String) row.get(2);
	    boolean lnf = (Boolean) row.get(3);
	    String email = (String) row.get(5);
	    String cellPhone = (String) row.get(7);
	    String label = (lnf? lastName + " " + firstName:
			    firstName + " " + lastName)
		+ ((email != null)? " <" + email + ">":
		   (cellPhone != null)? " (" + cellPhone + ")": "");
	    labeledIDs[i++] = new UserLabeledID(userID, label);
	}
	return labeledIDs;
    }

    /**
     * List users matching a pattern.
     * A full row lists the user ID, first name, last name, last-name
     * first flag, title, email address, country prefix, cell-phone
     * number, carrier, and status.
     * @param conn the database connection
     * @param pattern the pattern
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        user ID
     */
    public Vector<Vector<Object>>
	listUserInfo(Connection conn, String pattern, boolean full)
	throws SQLException
    {
	return listUserInfo(conn, pattern.split("\\|"), full);
    }

    /**
     * List users matching a pattern.
     * A full row lists the user ID, first name, last name, last-name
     * first flag, title, email address, country prefix, cell-phone
     * number, carrier, and status.
     * @param conn the database connection
     * @param patterns the patterns
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        user ID
     */
    public Vector<Vector<Object>>
	listUserInfo(Connection conn, String[] patterns, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    String q = null;
	    String q2 = null;
	    String q3 = null;
	    if (patterns == null || patterns.length == 0) {
		q = getSQLProperty("listUserInfo");
		patterns = null;
	    } else {
		for (String pattern: patterns) {
		    if (pattern == null) continue;
		    pattern = pattern.trim();
		    if (pattern.length() == 0) continue;
		    if (pattern.startsWith("+")) {
			if (q3 == null) {
			    q3 = getSQLProperty("listUserInfoMatchingIntlCell");
			}
		    } else if (pattern.matches("[0-9]+")) {
			if (q2 == null) {
			    q2 = getSQLProperty("listUserInfoMatchingCell");
			}
		    } else {
			if (q == null) {
			    q = getSQLProperty("listUserInfoMatching");
			}
		    }
		}
	    }
	    if (patterns == null) {
		try (ResultSet rs = statement.executeQuery(q)) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1, Integer.class));
			    row.add(rs.getObject(2, String.class));
			    row.add(rs.getObject(3, String.class));
			    row.add(rs.getObject(4, Boolean.class));
			    row.add(rs.getObject(5, String.class));
			    row.add(rs.getObject(6, String.class));
			    row.add(rs.getObject(7, String.class));
			    row.add(rs.getObject(8, String.class));
			    row.add(rs.getObject(9, String.class));
			    String s = rs.getObject(10, String.class);
			    UserStatus status = null;
			    if (s.equals("ACTIVE")) {
				status = UserStatus.ACTIVE;
			    } else if (s.equals("NOTACTIVE")) {
				status = UserStatus.NOTACTIVE;
			    } else if (s.equals("CANCELLED")) {
				status = UserStatus.CANCELLED;
			    }
			    row.add(status);
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1, Integer.class));
			    vector.add(row);
			}
		    }
		}
	    } else {
		PreparedStatement ps = null;
		PreparedStatement ps1 = null;
		PreparedStatement ps2 = null;
		PreparedStatement ps3 = null;
		try {
		    if (q != null) ps1 = conn.prepareStatement(q);
		    if (q2 != null) ps2 = conn.prepareStatement(q2);
		    if (q3 != null) ps3 = conn.prepareStatement(q3);
		    for (String pattern: patterns) {
			if (pattern == null) continue;
			pattern = pattern.trim();
			if (pattern.length() == 0) continue;
			if (pattern.startsWith("+")) {
			    ps  = ps3;
			    pattern = pattern.substring(1);
			    ps.setString(1, pattern);
			} else if (pattern.matches("[0-9]+")) {
			    ps = ps2;
			    ps.setString(1, pattern);
			} else {
			    ps = ps1;
			    ps.setString(1, pattern);
			    ps.setString(2, pattern);
			    ps.setString(3, pattern);
			}
			try (ResultSet rs = ps.executeQuery()) {
			    while (rs.next()) {
				if (full) {
				    Vector<Object> row = new Vector<Object>(2);
				    row.add(rs.getObject(1, Integer.class));
				    row.add(rs.getObject(2, String.class));
				    row.add(rs.getObject(3, String.class));
				    row.add(rs.getObject(4, Boolean.class));
				    row.add(rs.getObject(5, String.class));
				    row.add(rs.getObject(6, String.class));
				    row.add(rs.getObject(7, String.class));
				    row.add(rs.getObject(8, String.class));
				    row.add(rs.getObject(9, String.class));
				    String s = rs.getObject(10, String.class);
				    UserStatus stat = null;
				    if (s.equals("ACTIVE")) {
					stat = UserStatus.ACTIVE;
				    } else if (s.equals("NOTACTIVE")) {
					stat = UserStatus.NOTACTIVE;
				    } else if (s.equals("CANCELLED")) {
					stat = UserStatus.CANCELLED;
				    }
				    row.add(stat);
				    vector.add(row);
				} else {
				    Vector<Object> row = new Vector<Object>(1);
				    row.add(rs.getObject(1));
				    vector.add(row);
				}
			    }
			}
		    }
		} finally {
		    if (ps1 != null) ps1.close();
		    if (ps2 != null) ps2.close();
		    if (ps3 != null) ps3.close();
		}
	    }
	}
	return vector;
    }

    static TemplateProcessor.KeyMap emptymap = new TemplateProcessor.KeyMap();

    /**
     * Get a user's keymap.
     * Keymaps are used by template processors.
     * the keymap's keys are firstName, lastName, noFirstName, lastName,
     * noLastName, lastNameFirst, firstNameLast, title, and noTitle
     * @param conn the database connection
     * @param userID the user's user id
     * @return the user's key map
     * @see TemplateProcessor
     */
    public TemplateProcessor.KeyMap getUserKeyMap(Connection conn, int userID)
	throws IllegalArgumentException, SQLException
    {
	if (userID == -1) throw new IllegalArgumentException();
	String q = getSQLProperty("getUserInfoDataForKeymap");
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    ps.setInt(1, userID);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    String firstName = rs.getString(1);
		    String lastName = rs.getString(2);
		    boolean lastNameFirst = rs.getBoolean(3);
		    String title = rs.getString(4);
		    TemplateProcessor.KeyMap kmap =
			new TemplateProcessor.KeyMap();
		    if (firstName != null) {
			kmap.put("firstName", firstName.trim());
			// kmap.put("hasFirstName", emptymap);
		    } else {
			kmap.put("noFirstName", emptymap);
		    }
		    if (lastName != null) {
			kmap.put("lastName", lastName.trim());
			// kmap.put("hasLastName", emptymap);
		    } else {
			kmap.put("noLastName", emptymap);
		    }
		    if (lastNameFirst) {
			kmap.put("lastNameFirst", emptymap);
		    } else {
			kmap.put("lastNameLast", emptymap);
		    }
		    if (title != null) {
			kmap.put("title", title.trim());
			// kmap.put("hasTitle", emptymap);
		    } else {
			kmap.put("noTitle", emptymap);
		    }
		    return kmap;
		} else {
		    return null;
		}
	    }
	}
    }

    /**
     * List users matching a list of user Ids.
     * A full row lists the user ID, first name, last name, last-name
     * first flag, title, email address, country prefix, cell-phone
     * number, carrier, and status.
     * @param conn the database connection
     * @param ids the user IDs
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        user ID
     */
    public Vector<Vector<Object>>
	listUserInfo(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listUserInfo"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listUserInfoForID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(8);
			row.add(rs.getObject(1, Integer.class));
			row.add(rs.getObject(2, String.class));
			row.add(rs.getObject(3, String.class));
			row.add(rs.getObject(4, Boolean.class));
			row.add(rs.getObject(5, String.class));
			row.add(rs.getObject(6, String.class));
			row.add(rs.getObject(7, String.class));
			row.add(rs.getObject(8, String.class));
			row.add(rs.getObject(9, String.class));
			String s = rs.getObject(10, String.class);
			UserStatus status = null;
			if (s.equals("ACTIVE")) {
			    status = UserStatus.ACTIVE;
			} else if (s.equals("NOTACTIVE")) {
			    status = UserStatus.NOTACTIVE;
			} else if (s.equals("CANCELLED")) {
			    status = UserStatus.CANCELLED;
			}
			row.add(status);
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1, Integer.class));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1, Integer.class));
			    row.add(rs.getObject(2, String.class));
			    row.add(rs.getObject(3, String.class));
			    row.add(rs.getObject(4, Boolean.class));
			    row.add(rs.getObject(5, String.class));
			    row.add(rs.getObject(6, String.class));
			    row.add(rs.getObject(7, String.class));
			    row.add(rs.getObject(8, String.class));
			    row.add(rs.getObject(9, String.class));
			    String s = rs.getObject(10, String.class);
			    UserStatus status = null;
			    if (s.equals("ACTIVE")) {
				status = UserStatus.ACTIVE;
			    } else if (s.equals("NOTACTIVE")) {
				status = UserStatus.NOTACTIVE;
			    } else if (s.equals("CANCELLED")) {
				status = UserStatus.CANCELLED;
			    }
			    row.add(status);
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	return vector;
    }
  
    /**
     * Find the user ID for a user matching a pattern.
     * @param conn the database connection
     * @param pattern a pattern to match
     * @return the user ID
     */
    int findUserInfo(Connection conn, String pattern)
	throws SQLException, IllegalArgumentException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	if (pattern.contains("|")) {
	    throw new IllegalArgumentException("pattern contains \"|\"");
	}

	String q = null;
	String q2 = null;
	String q3 = null;
	if (pattern.startsWith("+")) {
	    if (q3 == null) {
		q3 = getSQLProperty("findUserInfoMatchingIntlCell");
	    }
	} else if (pattern.matches("[0-9]+")) {
	    if (q2 == null) {
		q2 = getSQLProperty("findUserInfoMatchingCell");
	    }
	} else {
	    if (q == null) {
		q = getSQLProperty("findUserInfoMatching");
	    }
	}
	PreparedStatement ps = null;
	PreparedStatement ps1 = null;
	PreparedStatement ps2 = null;
	PreparedStatement ps3 = null;
	try {
	    if (q != null) ps1 = conn.prepareStatement(q);
	    if (q2 != null) ps2 = conn.prepareStatement(q2);
	    if (q3 != null) ps3 = conn.prepareStatement(q3);
	    if (pattern.startsWith("+")) {
		ps  = ps3;
		pattern = pattern.substring(1);
		ps.setString(1, pattern);
	    } else if (pattern.matches("[0-9]+")) {
		ps = ps2;
		ps.setString(1, pattern);
	    } else {
		ps = ps1;
		ps.setString(1, pattern);
		ps.setString(2, pattern);
		ps.setString(3, pattern);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    int result = rs.getObject(1, Integer.class);
		    if (rs.next() == false) {
			return result;
		    }
		    throw new SQLException("multiple user IDs match pattern:"
					   + pattern);
		} else {
		    return -1;
		}
	    }
	} finally {
	    if (ps1 != null) ps1.close();
	    if (ps2 != null) ps2.close();
	    if (ps3 != null) ps3.close();
	}
    }

    /**
     * Get the email address for a user's MMS gateway.
     * @param conn the database connection
     * @param userID the userID
     * @param full true if the email address includes the user's name
     *        with the email address in angle brackets
     *        false if the email address alone is returned
     * @return the email address
     */
    public String getUserCellphoneEmail(Connection conn, int userID,
					boolean full)
	throws IllegalArgumentException, SQLException
    {
	if (userID == -1) {
	    throw new IllegalArgumentException("no user ID");
	}
	String firstName = null;
	String lastName = null;
	boolean lastNameFirst = false;
	String prefix = null;
	String cellNumber = null;
	int carrierID = 1;
	try (PreparedStatement ps
	     = conn.prepareStatement(getSQLProperty("getUserInfoData"))) {
	    ps.setInt(1, userID);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    if (full) {
			firstName = rs.getString(2);
			lastName = rs.getString(3);
			lastNameFirst = rs.getBoolean(4);
		    }
		    prefix = rs.getString(6);
		    cellNumber = rs.getString(7);
		    carrierID = rs.getInt(8);
		} else {
		    return null;
		}
	    }
	}
	String address = CellEmailFinder.lookup(this, conn, prefix, cellNumber,
						carrierID);
	if (full) {
	    address = ((lastNameFirst)? lastName + " " + firstName:
		       firstName + " " + lastName)
		+ "<" + address + ">";
	    address = address.trim();
	}
	return address;
    }


    /**
     * Set the table entry for a user.
     * @param conn the database connection
     * @param userID the userID
     * @param firstName the user's first name; null if ignored
     * @param lastName the user's last name; null if ignored
     * @param lastNameFirst true if the user's last name is followed by
     *        the user's first name; false if the user's first name is
     *        followed by theuser's last name; null if ignored
     * @param title the user's title; null if ignored
     * @param emailAddr the user's email address; null if ignored
     * @param countryPrefix the country prefix for the user's cell-phone
     *         number; null if ignored
     * @param cellNumber the user's cell-phone number; null if ignored
     * @param carrierID the carrierID for the user's cell-phone carrier;
     *        -1 if ignored
     * @param status the user's status - ACTIVE, NOTACTIVE, CANCELLED;
     *        null if ignored
     */
    public void setUserInfo(Connection conn, int userID,
			    String firstName, String lastName,
			    Boolean lastNameFirst, String title,
			    String emailAddr, String countryPrefix,
			    String cellNumber, int carrierID,
			    String status)
	throws SQLException
    {
	setUserInfo(conn, userID, firstName, lastName, lastNameFirst, title,
		    emailAddr, countryPrefix, cellNumber, carrierID,
		    status, true);
    }
    

    /**
     * Set the table entry for a user, indicating if the table change
     * should be committed.
     * @param conn the database connection
     * @param userID the userID
     * @param firstName the user's first name; null if ignored
     * @param lastName the user's last name; null if ignored
     * @param lastNameFirst true if the user's last name is followed by
     *        the user's first name; false if the user's first name is
     *        followed by theuser's last name; null if ignored
     * @param title the user's title; null if ignored
     * @param emailAddr the user's email address; null if ignored
     * @param countryPrefix the country prefix for the user's cell-phone
     *        number; null if ignored
     * @param cellNumber the user's cell-phone number; null if ignored
     * @param carrierID the carrierID for the user's cell-phone carrier;
     *        -1 if ignored
     * @param status the user's status - ACTIVE, NOTACTIVE, CANCELLED;
     *        null if ignored
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setUserInfo(Connection conn, int userID,
			    String firstName, String lastName,
			    Boolean lastNameFirst, String title,
			    String emailAddr, String countryPrefix,
			    String cellNumber, int carrierID,
			    String status,
			    boolean commit)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	String setselectf = getSQLProperty("setselectFormat");
	boolean useFN = false;
	boolean useLN = false;
	boolean useLNF = false;
	boolean useT = false;
	boolean useEA = false;
	boolean useCP = false;
	boolean useCN = false;
	boolean useCID = false;
	boolean useS = false;
	
	boolean first = true;

	if (firstName != null) {
	    firstName = firstName.trim().replaceAll("\\s+\\s*", " ");
	    if (firstName.length() == 0)firstName = null;
	    useFN = true; sb.append("SET firstName = ?");
	    first = false;
	}
	if (lastName != null) {
	    lastName = lastName.trim(). replaceAll("\\s+\\s*", " ");
	    if (lastName.length() == 0) lastName = null;
	    useLN = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("lastName = ?");
	    first = false;
	}
	if (lastNameFirst != null) {
	    useLNF = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("lastNameFirst = ?");
	    first = false;
	}
	if (title != null) {
	    title = title.trim().replaceAll("\\s+\\s*", " ");
	    if (title.length() == 0) title = null;
	    useT = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("title = ?");
	}

	if (emailAddr != null) {
	    emailAddr = emailAddr.trim();
	    if (emailAddr.length() == 0) emailAddr = null;
	    useEA = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("emailAddr = ?");
	    first = false;
	}
	if (countryPrefix != null && countryPrefix.trim().length() == 0) {
	    countryPrefix = "0";
	    useCP = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("countryPrefix = ?");
	    first = false;
	} else if (countryPrefix != null) {
	    useCP = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("countryPrefix = ?");
	    first = false;
	}
	if (cellNumber != null) {
	    cellNumber = cellNumber.trim();
	    if (cellNumber.length() != 0) {
		cellNumber = cellNumber.replaceAll("\\s|[()-]", "");
		if (!cellNumber.matches("[0-9]*")) {
		    throw new IllegalArgumentException("bad cell-phone number");
		}
	    } else {
		cellNumber = null;
	    }
	    useCN = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("cellNumber = ?");
	    first = false;
	    
	}
	if (carrierID != -1) {
	    if (first == false) sb.append(","); else sb.append("SET ");
	    useCID = true;
	    sb.append("carrierID = ?");
	    first = false;
	}

	if (status != null) {
	    if (first == false) sb.append(","); else sb.append("SET ");
	    useS = true;
	    sb.append("status = ?");
	    first = false;
	}

	String s = getSQLProperty("setUserData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useFN) {
		    if (firstName == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, firstName);
		    }
		}
		if (useLN) {
		    if (lastName == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, lastName);
		    }
		}
		if (useLNF) {
		    ps.setBoolean(ind++, lastNameFirst);
		}
		if (useT) {
		    if (title == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, title);
		    }
		}
		if (useEA) {
		    if (emailAddr == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, emailAddr);
		    }
		}
		if (useCP) {
		    if (countryPrefix == null) countryPrefix = "1";
		    ps.setString(ind++, countryPrefix);
		}
		if (useCN) {
		    if (cellNumber == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, cellNumber);
		    }
		}
		if (useCID) {
		    ps.setInt(ind++, carrierID);
		}
		if (useS) {
		    ps.setString(ind++, status);
		}
		ps.setInt(ind++, userID);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setUserInfo");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
 
    /**
     * Add a new owner to the owner table.
     * @param conn the database connection
     * @param label a short string naming the owner
     * @param summary a description of the owner
     * @param idomain the internet domain name associated with the
     *        owner
     */
    public void addOwner(Connection conn,
			String label, String summary,
			String idomain)
	throws SQLException
    {
	addOwner(conn, label, summary, idomain, true);
    }


    /**
     * Add a new owner to the owner table, indicating if database changes
     * should be committed.
     * @param conn the database connection
     * @param label a short string naming the owner
     * @param summary a description of the owner
     * @param idomain the internet domain name associated with the
     *        owner
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addOwner(Connection conn,
			 String label, String summary,
			 String idomain, boolean commit)
	throws SQLException
    {
	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    if (label.length() == 0) label = null;
	}
	if (summary != null) {
	    summary = summary.trim(). replaceAll("\\s+\\s*", " ");
	    if (summary.length() == 0) summary = null;
	}

	if (idomain != null) {
	    idomain = idomain.trim().replaceAll("\\s+", "");
	    if (idomain.length() == 0) idomain = null;
	}

	String s = getSQLProperty("insertOwner");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setString(1, label);
		ps.setString(2, summary);
		ps.setString(3, idomain);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addOwner");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
	       
    }
			    
    /**
     * Delete an entry from the owner table.
     * @param conn the database connection
     * @param ownerID the owner ID for the entry
     */
    public void deleteOwner(Connection conn, int ownerID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteOwnerByID"))) {
		ps2.setInt(1, ownerID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteOwner");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete an entry from the owner table given a search pattern.
     * @param conn the database connection
     * @param pattern a search pattern for the owner
     */
    public void deleteOwner(Connection conn, String pattern)
	throws SQLException
    {
	deleteOwner(conn, pattern, false);
    }

    /**
     * Delete an entry from the owner table given a search pattern, indicating
     * if the deletion is interactive.
     * @param conn the database connection
     * @param pattern a search pattern for the owner
     * @param force true if all matching users should be deleted; false if
     *        each deletion must be confirmed
     */
    public void deleteOwner(Connection conn, String pattern, boolean force)
	throws SQLException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s\\s+", "").toUpperCase();
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty
					 ("listOwnersMatching"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteOwnerByID"))) {
		    // System.out.format("finding owner(s) \"%s\"\n", pattern);
		    ps1.setString(1, pattern);
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    int ownerID = rs.getInt(1);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					("deleting owner %d: %s [Yn!<ESC>]:",
					 ownerID, rs.getString(2)).trim();
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, ownerID);
			    ps2.executeUpdate();
			} while (rs.next());
		    } else {
			System.err.format("Owner matching '%s' "
					  + "does not exist\n", pattern);
			throw new SQLException("no such entry in "
					       + "Owner table: "
					       + pattern);
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteOwner");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }


    /**
     * Delete an entry from the owner table a list of owner IDs.
     * @param conn the database connection
     * @param ownerIDs the owner IDs for the entries
     */
    public void deleteOwner(Connection conn, int[] ownerIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteOwnerByID"))) {
		for (int ownerID: ownerIDs) {
		    ps2.setInt(1, ownerID);
		    ps2.executeUpdate();
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteOwner");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /*
     * List the owner labeled IDs for every owner.
     * This method is useful for creating a combo box allowing a user
     * to select an owner.
     * @param conn the database connection
     * @return a list, starting with "[ All ]", followed by the labels
     *         for every owner
     */
    public Object[] listOwnerLabels(Connection conn) throws SQLException {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	int[] ids = null;
	Vector<Vector<Object>> rows = listOwners(conn, ids, true);
	Object[] results = new Object[rows.size() + 1];
	results[0] = "[ All ]";
	int i = 1;
	for (Vector<Object> row: rows) {
	    results[i++] = row.get(1);
	}
	return results;
    }

    /*
     * List the owner labeled IDs for every owner.
     * This method is useful for creating a combo box allowing a user
     * to select an owner.
     * @param conn the database connection
     * @return a list containing owner labeled IDs for every owner
     */
    public OwnerLabeledID[] listOwnerLabeledIDs(Connection conn)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	int[] ids = null;
	Vector<Vector<Object>> rows = listOwners(conn, ids, true);
	OwnerLabeledID[] results = new OwnerLabeledID[rows.size()];
	int i = 0;
	for (Vector<Object> row: rows) {
	    results[i++] = new OwnerLabeledID((Integer)row.get(0),
					      (String)row.get(1));
	}
	return results;
    }

    /**
     * List owners matching a series of patterns
     * When the argument full has the value true, each row contains
     * an owner ID, followed by a label, followed by a summary,
     * followed by an Internet domain name.
     * @param conn the database connection
     * @param patterns a list of patterns
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only an
     *        owner ID
     */
    public Vector<Vector<Object>>
	listOwners(Connection conn, String[] patterns, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    String q = null;
	    if (patterns == null || patterns.length == 0) {
		q = getSQLProperty("listOwners");
		patterns = null;
	    } else {
		q = getSQLProperty("listOwnersMatching");
		for (String pattern: patterns) {
		    if (pattern == null || pattern.trim().length() == 0) {
			q = getSQLProperty("listOwners");
			patterns = null;
			break;
		    } else {
			if (pattern.trim().equals("%")) {
			    q = getSQLProperty("listOwners");
			    patterns = null;
			    break;
			}
		    }
		}
	    }
	    if (patterns == null) {
		try (ResultSet rs = statement.executeQuery(q)) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    row.add(rs.getObject(5));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		}
	    } else {
		PreparedStatement ps = null;
		try {
		    ps = conn.prepareStatement(q);
		    for (String pattern: patterns) {
			pattern = pattern.trim().replace("\\s+", " ")
			    .toUpperCase();
			ps.setString(1, pattern);
			try (ResultSet rs = ps.executeQuery()) {
			    while (rs.next()) {
				if (full) {
				    Vector<Object> row = new Vector<Object>(2);
				    row.add(rs.getObject(1));
				    row.add(rs.getObject(2));
				    row.add(rs.getObject(3));
				    row.add(rs.getObject(5));
				    vector.add(row);
				} else {
				    Vector<Object> row = new Vector<Object>(1);
				    row.add(rs.getObject(1));
				    vector.add(row);
				}
			    }
			}
		    }
		} finally {
		    if (ps != null) ps.close();
		}
	    }
	}
	return vector;
    }

    /**
     * List owners matching each in series of owner IDs.
     * When the argument full has the value true, each row contains
     * an owner ID, followed by a label, followed by a summary,
     * followed by an Internet domain name.
     * @param conn the database connection
     * @param ids a list of owner IDs
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only an
     *        owner ID
     */
    public Vector<Vector<Object>>
	listOwners(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listOwners"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listOwnersByID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(5));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    row.add(rs.getObject(5));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	return vector;
    }
  
    /**
     * Get the owner ID for an owner that matches a pattern
     * @param conn the database connection
     * @param pattern the pattern
     * @return the corresponding ownerID; -1 if there is no match
     */
    int findOwner(Connection conn, String pattern)
	throws SQLException, IllegalArgumentException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s+", " ").toUpperCase();
	String q = getSQLProperty("findOwnerMatching");
	PreparedStatement ps = null;
	try {
	    ps = conn.prepareStatement(q);
	    ps.setString(1, pattern);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    int result = rs.getObject(1, Integer.class);
		    if (rs.next() == false) {
			return result;
		    }
		    throw new SQLException("multiple owner IDs match pattern:"
					   + pattern);
		} else {
		    return -1;
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
    }

    /**
     * Set fields in the owner table.
     * @param conn the database connection
     * @param ownerID the owner ID for the fields to set
     * @param label the label; null if ignored
     * @param summary the summary; null if ignored
     * @param idomain the internet domain name; null if ignored
     */
    public void setOwner(Connection conn, int ownerID,
			 String label, String summary, String idomain)
	throws SQLException
    {
	setOwner(conn, ownerID, label, summary, idomain, true);
    }

    /**
     * Set fields in the owner table, indicating if changes should be committed.
     * @param conn the database connection
     * @param ownerID the owner ID for the fields to set
     * @param label the label; null if ignored
     * @param summary the summary; null if ignored
     * @param idomain the internet domain name; null if ignored
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setOwner(Connection conn, int ownerID,
			 String label, String summary,
			 String idomain, boolean commit)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useL = false;
	boolean useS = false;
	boolean useI = false;
	
	boolean first = true;

	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    useL = true;
	    sb.append("SET label = ?");
	    first = false;
	}
	if (summary != null) {
	    summary = summary.trim(). replaceAll("\\s+", " ");
	    if (summary.length() == 0) summary = null;
	    useS = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("summary = ?");
	    first = false;
	}
	if (idomain != null) {
	    idomain = idomain.trim();
	    useI = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("SET idomain = ?");
	    first = false;
    	}

	String s = getSQLProperty("setOwnerData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useL) {
		    ps.setString(ind++, label);
		}
		if (useS) {
		    ps.setString(ind++, summary);
		}
		if (useI) {
		    ps.setString(ind++, idomain);
		}
		ps.setInt(ind++, ownerID);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setOwner");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(false);
	}
    }

    /**
     * Add an entry to the location table.
     * @param conn the database connection
     * @param label the label
     * @param location a description of the location
     */
    public void addLocation(Connection conn,
			    String label, String location)
	throws SQLException
    {
	addLocation(conn, label, location, true);
    }

    /**
     * Add an entry to the location table, indicating if the database
     * change should be committed.
     * @param conn the database connection
     * @param label the label
     * @param location a description of the location
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addLocation(Connection conn, String label, String location,
			    boolean commit)
	throws SQLException
    {
	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    if (label.length() == 0) label = null;
	}
	if (location != null) {
	    location = location.trim(). replaceAll("\\s+\\s*", " ");
	    if (location.length() == 0) location = null;
	}

	String s = getSQLProperty("insertLocation");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setString(1, label);
		ps.setString(2, location);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addLocation");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
			    
    /**
     * Delete a row from the location table
     * @param conn the database connection
     * @param locationID the location ID
     */
    public void deleteLocation(Connection conn, int locationID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteLocationByID"))) {
		ps2.setInt(1, locationID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteLocation");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows from the location table given a pattern.
     * @param conn the database connection
     * @param pattern a search pattern
     */
    public void deleteLocation(Connection conn, String pattern)
	throws SQLException
    {
	deleteLocation(conn, pattern, false);
    }

    /**
     * Delete a row from the location table given a pattern and indicating
     * if the deletion is interactive.
     * @param conn the database connection
     * @param pattern a search pattern
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteLocation(Connection conn, String pattern, boolean force)
	throws SQLException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s\\s+", "").toUpperCase();
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty
					 ("listLocationsMatching"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteLocationByID"))) {
		    /*
		    System.out.format("finding location(s) \"%s\"\n", pattern);
		    */
		    ps1.setString(1, pattern);
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    int locationID = rs.getInt(1);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					("deleting location %d: %s [Yn!<ESC>]:",
					 locationID, rs.getString(2)).trim();
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, locationID);
			    ps2.executeUpdate();
			} while (rs.next());
		    } else {
			System.err.format("Location matching '%s' "
					  + "does not exist\n", pattern);
			throw new SQLException("no such entry in "
					       + "Location table: "
					       + pattern);
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteLocation");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows from the location table given a list of location IDs
     * @param conn the database connection
     * @param locationIDs the IDs of the locations to be deleted
     */
    public void deleteLocation(Connection conn, int[] locationIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteLocationByID"))) {
		for (int locationID: locationIDs) {
		    ps2.setInt(1, locationID);
		    ps2.executeUpdate();
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteLocation");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * List the location labels in the location table
     * This method is intended for building combo boxes and similar
     * GUI components. The initial entry indicates that all locations
     * should be selected.
     * @param conn the database connection
     * @return the location labels, prefaced with the "[ All ]"
     */
    public Object[] listLocationLabels(Connection conn) throws SQLException {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	int[] ids = null;
	Vector<Vector<Object>> rows = listLocations(conn, ids, true);
	Object[] results = new Object[rows.size() + 1];
	results[0] = "[ All ]";
	int i = 1;
	for (Vector<Object> row: rows) {
	    results[i++] = row.get(1);
	}
	return results;
    }

    /**
     * List the location labeled IDs for all locations in the location table.
     * @param conn the database connection
     * @return the location labeled IDs
     */
    public LocationLabeledID[] listLocationLabeledIDs(Connection conn)
	throws SQLException
    {
	return listLocationLabeledIDs(conn, false);
    }

    /**
     * List the location labeled IDs for all locations in the location table,
     * specifying whether an 'all' entry should appear first. The 'all'
     * labeled ID is an ID whose label is "[ All ]" and whose location id
     * is -1, which is used as a signal that all IDs are wanted.
     * @param conn the database connection
     * @param all true if an 'all' labeled ID should appear first; false if
     *        this ID never appears
     * @return the location labeled IDs
     */
    public LocationLabeledID[] listLocationLabeledIDs(Connection conn,
						      boolean all)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	int[] ids = null;
	Vector<Vector<Object>> rows = listLocations(conn, ids, true);
	int sz = rows.size();
	LocationLabeledID[] results = new LocationLabeledID[all? (sz+1): sz];
	int i = 0;
	if (all) results[i++] = new LocationLabeledID(-1, "[ ALL ]");
	for (Vector<Object> row: rows) {
	    results[i++] = new LocationLabeledID((Integer)row.get(0),
						 (String)row.get(1));
	}
	return results;
    }

    /**
     * List rows from the location table matching a pattern.
     * For the full listing, each row contains the location ID,
     * followed by the label, followed by the location.
     * @param conn the database connection
     * @param patterns  patterns, one of which should match a labels
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        location ID
     */
    public Vector<Vector<Object>>
	listLocations(Connection conn, String[] patterns, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    String q = null;
	    if (patterns == null || patterns.length == 0) {
		q = getSQLProperty("listLocations");
		patterns = null;
	    } else {
		q = getSQLProperty("listLocationsMatching");
		for (String pattern: patterns) {
		    if (pattern == null || pattern.trim().length() == 0) {
			q = getSQLProperty("listLocations");
			patterns = null;
			break;
		    } else {
			if (pattern.trim().equals("%")) {
			    q = getSQLProperty("listLocations");
			    patterns = null;
			    break;
			}
		    }
		}
	    }
	    if (patterns == null) {
		try (ResultSet rs = statement.executeQuery(q)) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		}
	    } else {
		PreparedStatement ps = null;
		try {
		    ps = conn.prepareStatement(q);
		    for (String pattern: patterns) {
			pattern = pattern.trim().replace("\\s+", " ")
			    .toUpperCase();
			ps.setString(1, pattern);
			try (ResultSet rs = ps.executeQuery()) {
			    while (rs.next()) {
				if (full) {
				    Vector<Object> row = new Vector<Object>(2);
				    row.add(rs.getObject(1));
				    row.add(rs.getObject(2));
				    row.add(rs.getObject(3));
				    vector.add(row);
				} else {
				    Vector<Object> row = new Vector<Object>(1);
				    row.add(rs.getObject(1));
				    vector.add(row);
				}
			    }
			}
		    }
		} finally {
		    if (ps != null) ps.close();
		}
	    }
	}
	return vector;
    }

    /**
     * List rows from the location table matching specified ids.
     * For the full listing, each row contains the location ID,
     * followed by the label, followed by the location.
     * @param conn the database connection
     * @param ids the location IDs to match
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        location ID
     * @return a table whose rows are described above
     */
    public Vector<Vector<Object>>
	listLocations(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listLocations"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listLocationsByID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	return vector;
    }
  
    /**
     * Get the location ID for the location whose label matches a pattern.
     * @param conn the database connection
     * @param pattern the pattern
     * @return the location ID; -1 if there is no match
     */
    int findLocation(Connection conn, String pattern)
	throws SQLException, IllegalArgumentException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s+", " ");
	String q = getSQLProperty("findLocationMatching");
	PreparedStatement ps = null;
	try {
	    ps = conn.prepareStatement(q);
	    ps.setString(1, pattern);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    int result = rs.getObject(1, Integer.class);
		    if (rs.next() == false) {
			return result;
		    }
		    throw new SQLException("multiple location IDs "
					   + "match pattern:" + pattern);
		} else {
		    return -1;
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
    }

    /**
     * Set fields in the location table.
     * @param conn the database connection
     * @param locationID the location ID of the fields to set
     * @param label the label; null if this field is not changed
     * @param location the location description; null if this field is
     *        not changed
     */
    public void setLocation(Connection conn, int locationID,
			    String label, String location)
	throws SQLException
    {
	setLocation(conn, locationID, label, location, true);
    }

    /**
     * Set fields in the location table, indicating if the database changes
     * should be committed.
     * @param conn the database connection
     * @param locationID the location ID of the fields to set
     * @param label the label; null if this field is not changed
     * @param location the location description; null if this field is
     *        not changed
     */
    public void setLocation(Connection conn, int locationID,
			    String label, String location,
			    boolean commit)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useL = false;
	boolean UseLoc = false;
	
	boolean first = true;

	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    useL = true;
	    sb.append("SET label = ?");
	    first = false;
	}
	if (location != null) {
	    location = location.trim(). replaceAll("\\s+", " ");
	    if (location.length() == 0) location = null;
	    UseLoc = true;
	    if (first == false) sb.append(","); else sb.append("SET ");
	    sb.append("location = ?");
	    first = false;
	}

	String s = getSQLProperty("setLocationData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useL) {
		    ps.setString(ind++, label);
		}
		if (UseLoc) {
		    ps.setString(ind++, location);
		}
		ps.setInt(ind++, locationID);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setLocation");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a row to the first-alarm table.
     * @param conn the database connection
     * @param userID the ID of the user to which the new row applies
     * @param ownerID the ID of the owner to which the new row applies
     * @param locationID the ID of the location to which the new row applies
     * @param eventTime a possible time for an event
     * @param weekday true if eventTime refers to a weekday; false for a
     *        weekend
     * @param alarmTime the corresponding time for an alarm
     * @param forEmail true if the alarm applies calendar appointments that
     *        will be sent to the user's email address; false if no message
     *        is to be sent
     * @param forPhone true if the alarm applies calendar appointments that
     *        will be sent to the user's cell phone; false if no message
     *        is to be sent
     */
    public void addFirstAlarm(Connection conn, int userID, int ownerID,
			      int locationID, Time eventTime, boolean weekday,
			      Time alarmTime,
			      boolean forEmail, boolean forPhone)
    throws SQLException, IllegalArgumentException
    {
	addFirstAlarm(conn, userID, ownerID, locationID, eventTime, weekday,
		      alarmTime, forEmail, forPhone, true);
    }

    /**
     * Add a row to the first-alarm table, indicating if the database changes
     * should be committed.
     * @param conn the database connection
     * @param userID the ID of the user to which the new row applies
     * @param ownerID the ID of the owner to which the new row applies
     * @param locationID the ID of the location to which the new row applies
     * @param eventTime a possible time for an event
     * @param weekday true if eventTime refers to a weekday; false for a
     *        weekend
     * @param alarmTime the corresponding time for an alarm
     * @param forEmail true if the alarm applies calendar appointments that
     *        will be sent to the user's email address; false if no message
     *        is to be sent
     * @param forPhone true if the alarm applies calendar appointments that
     *        will be sent to the user's cell phone; false if no message
     *        is to be sent
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addFirstAlarm(Connection conn, int userID, int ownerID,
			      int locationID, Time eventTime, boolean weekday,
			      Time alarmTime,
			      boolean forEmail, boolean forPhone,
			      boolean commit)
    throws SQLException, IllegalArgumentException
    {

        if (eventTime == null) {
	    throw new IllegalArgumentException("null argument");
	}
        if (alarmTime == null) {
	    throw new IllegalArgumentException("null argument");
	}

	String s = getSQLProperty("insertFirstAlarm");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, userID);
		ps.setInt(2, ownerID);
		ps.setInt(3, locationID);
		ps.setTime(4, eventTime);
		ps.setBoolean(5, weekday);
		ps.setTime(6, alarmTime);
		ps.setBoolean(7, forEmail);
		ps.setBoolean(8, forPhone);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addFirstAlarm");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a row from the first-alarm table.
     * @param conn the database connection
     * @param userID the ID of the user to which the new row applies
     * @param ownerID the ID of the owner to which the new row applies
     * @param locationID the ID of the location to which the new row applies
     * @param eventTime a possible time for an event
     * @param weekday true if eventTime refers to a weekday; false for a
     *        weekend
     */
    public void deleteFirstAlarm(Connection conn, int userID, int ownerID,
			      int locationID, Time eventTime, boolean weekday)
	throws SQLException, IllegalArgumentException
    {
        if (eventTime == null) {
	    throw new IllegalArgumentException("null argument");
	}
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteFirstAlarm"))) {
		ps2.setInt(1, userID);
		ps2.setInt(2, ownerID);
		ps2.setInt(3, locationID);
		ps2.setTime(4, eventTime);
		ps2.setBoolean(5, weekday);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteFirstAlarm");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows from the first-alarm table.
     * The rows deleted are those that match the criteria set by the
     * userID, ownerID, locationID, eventTime, and weekday arguments.
     * @param conn the database connection
     * @param userID the ID of the user to which the new row applies; -1
     *        to match all user IDs
     * @param ownerID the ID of the owner to which the new row applies; -1
     *        to match all owner IDs
     * @param locationID the ID of the location to which the new row applies;
     *        to match all locations
     * @param eventTime a possible time for an event; null for any time
     * @param weekday true if eventTime refers to a weekday; false for a
     *        weekend; null if not considered; null null for either possibility
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteFirstAlarms(Connection conn, int userID, int ownerID,
				  int locationID, Time eventTime,
				  Boolean weekday, boolean force)
	throws SQLException, IllegalArgumentException
    {
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("deleteFirstAlarms");
	if (ownerID != -1) {
	    s = s + " AND ownerID = ?";
	}
	if (locationID != -1) {
	    s = s + " AND locationID = ?";
	}
	if (eventTime != null) {
	    s = s + " AND eventTime = ?";
	}
	if (weekday != null) {
	    s = s + " AND weekday = ?";
	}
	String s2 = getSQLProperty("deleteFirstAlarm");

	// force the deletion if at most one item will be deleted
	if (ownerID != -1 && locationID != -1 && eventTime != null &&
	    weekday != null) {
	    force = true;
	}

	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1 = conn.prepareStatement(s)) {
		int ind = 1;
		ps1.setInt(ind++, userID);
		if (ownerID != -1) {
		    ps1.setInt(ind++, ownerID);
		}
		if (locationID != -1) {
		    ps1.setInt(ind++, locationID);
		}
		if (eventTime != null) {
		    ps1.setTime(ind++, eventTime);
		}
		if (weekday != null) {
		    ps1.setBoolean(ind++, weekday);
		}
		try (ResultSet rs = ps1.executeQuery()) {
		    try (PreparedStatement ps2 = conn.prepareStatement(s2)) {
			while (rs.next()) {
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					(String.format
					 ("deleting first alarm entry "
					  + "u=%d, o=%d, l=%d, t=%s, %s "
					  + "[Yn!<ESC>]:",
					  rs.getInt(1), rs.getInt(2),
					  rs.getInt(3), rs.getTime(4),
					  (rs.getBoolean(5)? "weekday":
					   "weekend"))).trim();
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, rs.getInt(1));
			    ps2.setInt(2, rs.getInt(2));
			    ps2.setInt(3, rs.getInt(3));
			    ps2.setTime(4, rs.getTime(4));
			    ps2.setBoolean(5, rs.getBoolean(5));
			    ps2.executeUpdate();
			}
		    }
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteFirstAlarm");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    private String fullUserNameSQL = null;

    private String getFullUserName(Connection conn, int userID) {
	String schema = getSQLProperty("theSchema");
	if (fullUserNameSQL == null) {
	    fullUserNameSQL = "SELECT firstName, lastName, lastNameFirst FROM "
		+ schema + ".UserInfo WHERE userID = ?";
	}
	try {
	    try (PreparedStatement ps
		 = conn.prepareStatement(fullUserNameSQL)) {
		ps.setInt(1, userID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			String firstName = rs.getString(1);
			String lastName = rs.getString(2);
			boolean lnf = rs.getBoolean(3);
			if (lnf) {
			    return lastName + " " + firstName;
			} else {
			    return firstName + " " + lastName;
			}
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	return null;
    }

    private String ownerLabelSQL = null;

    /**
     * Get the  owner labeled ID for an owner table entry
     * @param conn the database connection
     * @param ownerID the ownerID
     * @return the matching owner labeled ID; null if there is no match
     */
    public OwnerLabeledID getOwnerLabeledID(Connection conn, int ownerID)
    {
	return new OwnerLabeledID(ownerID, getOwnerLabel(conn, ownerID));
    }

    private String getOwnerLabel(Connection conn, int ownerID) {
	String schema = getSQLProperty("theSchema");
	if (ownerLabelSQL == null) {
	    ownerLabelSQL  = "SELECT label FROM "
		+ schema + ".Owner WHERE ownerID = ?";
	}
	try {
	    try (PreparedStatement ps = conn.prepareStatement(ownerLabelSQL)) {
		ps.setInt(1, ownerID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			return rs.getString(1).trim();
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	return null;
    }

    private String locationLabelSQL = null;

    /**
     * Get the location labeled ID for an owner table entry
     * @param conn the database connection
     * @param locationID the location ID
     * @return the matching location labeled ID; null if there is no match
     */
    public LocationLabeledID getLocationLabeledID(Connection conn,
						  int locationID)
    {
	return new LocationLabeledID(locationID,
				     getLocationLabel(conn, locationID));
    }

    private String getLocationLabel(Connection conn, int locationID) {
	String schema = getSQLProperty("theSchema");
	if (locationLabelSQL == null) {
	    locationLabelSQL  = "SELECT label FROM "
		+ schema + ".Location WHERE locationID = ?";
	}
	try {
	    try (PreparedStatement ps =
		 conn.prepareStatement(locationLabelSQL)) {
		ps.setInt(1, locationID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			return rs.getString(1).trim();
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	return null;
    }

    private String eventLabelSQL = null;

    private String getEventLabel(Connection conn, int eventID) {
	String schema = getSQLProperty("theSchema");
	if (eventLabelSQL == null) {
	    eventLabelSQL  = "SELECT tbl1.ownerID, tbl2.label FROM "
		+ schema + ".Owner AS tbl1, " 
		+ schema + ".Event AS tbl2 WHERE tbl1.ownerID = tbl2.ownerID "
		+ "AND tbl2.eventID = ?";
	}
	int ownerID = -1;
	String label = null;
	try {
	    try (PreparedStatement ps = conn.prepareStatement(eventLabelSQL)) {
		ps.setInt(1, eventID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			ownerID = rs.getInt(1);
			label = rs.getString(2).trim();
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	return getOwnerLabel(conn, ownerID) + ": " + label;
    }

    private int getEventOwnerID(Connection conn, int eventID) {
	String schema = getSQLProperty("theSchema");
	if (eventLabelSQL == null) {
	    eventLabelSQL  = "SELECT tbl1.ownerID, tbl2.label FROM "
		+ schema + ".Owner AS tbl1, " 
		+ schema + ".Event AS tbl2 WHERE tbl1.ownerID = tbl2.ownerID "
		+ "AND tbl2.eventID = ?";
	}
	try {
	    try (PreparedStatement ps = conn.prepareStatement(eventLabelSQL)) {
		ps.setInt(1, eventID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			return rs.getInt(1);
		    } else {
			return -1;
		    }
		}
	    }
	} catch (SQLException e) {}
	return  -1;
    }


    private String eventInstanceLabelSQL = null;

    private String getEventInstanceLabel(Connection conn, int instanceID,
					 boolean full) {
	String schema = getSQLProperty("theSchema");
	if (eventInstanceLabelSQL == null) {
	    eventInstanceLabelSQL  =
		"SELECT eventID, locationID, startDate, startTime"
		+ ", preEventType, preEventOffset FROM "
		+ schema + ".EventInstance WHERE instanceID = ?";
	}
	int eventID = -1;
	int locationID = -1;
	LocalDate date = null;
	LocalTime time = null;
	String preEventType = null;
	int preEventOffset = 0;
	try {
	    try (PreparedStatement ps =
		 conn.prepareStatement(eventInstanceLabelSQL)) {
		ps.setInt(1, instanceID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			eventID = rs.getInt(1);
			locationID = rs.getInt(2);
			java.sql.Date d = rs.getDate(3);
			date = (d == null)? null: d.toLocalDate();
			java.sql.Time t = rs.getTime(4);
			time = (t == null)? null: t.toLocalTime();
			preEventType = rs.getString(5);
			preEventOffset = rs.getInt(6);
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	FormatStyle fs = FormatStyle.MEDIUM;
	DateTimeFormatter df = DateTimeFormatter.ofLocalizedDate(fs);
	DateTimeFormatter tf = DateTimeFormatter.ofLocalizedTime(fs);
	LocalTime ptime = (time == null)? null:
	    time.minus(preEventOffset, ChronoUnit.MINUTES);
	String tag = (full && preEventOffset > 0)?
	    String.format(", %s at %s", preEventType, ptime.format(tf)): "";
	return String.format("%s at %s on %s at %s%s)",
			     getEventLabel(conn, eventID),
			     getLocationLabel(conn, locationID),
			     ((date == null)? "TBD":
			      date.format(full? df: sdf)),
			     ((time == null)? "TBD": time.format(tf)),
			     tag);
    }

    private InstanceLabeledID
	getInstanceLabeledID(Connection conn, int instanceID)
    {
	String schema = getSQLProperty("theSchema");
	if (eventInstanceLabelSQL == null) {
	    eventInstanceLabelSQL  =
		"SELECT eventID, locationID, startDate, startTime"
		+ ", preEventType, preEventOffset FROM "
		+ schema + ".EventInstance WHERE instanceID = ?";
	}
	int eventID = -1;
	int locationID = -1;
	LocalDate date = null;
	LocalTime time = null;
	String preEventType = null;
	int preEventOffset = 0;
	try {
	    try (PreparedStatement ps =
		 conn.prepareStatement(eventInstanceLabelSQL)) {
		ps.setInt(1, instanceID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			eventID = rs.getInt(1);
			locationID = rs.getInt(2);
			java.sql.Date d = rs.getDate(3);
			date = (d == null)? null: d.toLocalDate();
			java.sql.Time t = rs.getTime(4);
			time = (t == null)? null: t.toLocalTime();
			preEventType = rs.getString(5);
			preEventOffset = rs.getInt(6);
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	FormatStyle fs = FormatStyle.MEDIUM;
	DateTimeFormatter df = DateTimeFormatter.ofLocalizedDate(fs);
	DateTimeFormatter tf = DateTimeFormatter.ofLocalizedTime(fs);
	LocalTime ptime = (time == null)? null:
	    time.minus(preEventOffset, ChronoUnit.MINUTES);
	String label =  String.format("%s at %s: on %s at %s",
				      getEventLabel(conn, eventID),
				      getLocationLabel(conn, locationID),
				      ((date == null)? "TBD": date.format(sdf)),
				      ((time == null)? "TBD": time.format(tf)));
	return new InstanceLabeledID(instanceID, label);
    }


    private int getEventInstanceEventID(Connection conn, int instanceID) {
	String schema = getSQLProperty("theSchema");
	if (eventInstanceLabelSQL == null) {
	    eventInstanceLabelSQL  =
		"SELECT eventID, locationID, startDate, startTime"
		+ ", preEventType, preEventOffset FROM "
		+ schema + ".EventInstance WHERE instanceID = ?";
	}
	int eventID = -1;
	int locationID = -1;
	LocalDate date = null;
	LocalTime time = null;
	String preEventType = null;
	int preEventOffset = 0;
	try {
	    try (PreparedStatement ps =
		 conn.prepareStatement(eventInstanceLabelSQL)) {
		ps.setInt(1, instanceID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			return rs.getInt(1);
		    } else {
			return -1;
		    }
		}
	    }
	} catch (SQLException e) {}
	return -1;
    }


    private String seriesLabelSQL = null;

    private String getSeriesLabel(Connection conn, int seriesID) {
	String schema = getSQLProperty("theSchema");
	if (seriesLabelSQL == null) {
	    seriesLabelSQL  =
		"SELECT label FROM "
		+ schema + ".Series WHERE seriesID = ?";
	}
	try {
	    try (PreparedStatement ps =
		 conn.prepareStatement(seriesLabelSQL)) {
		ps.setInt(1, seriesID);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			return  rs.getString(1);
		    } else {
			return null;
		    }
		}
	    }
	} catch (SQLException e) {}
	return null;
    }

    /**
     * List rows from the first alarm table.
     * The full listing for a row includes the userID, followed by the
     * location ID, followed by the event time, followed by the weekday
     * flag, followed by the alarm time, followed by the 'for email' flag,
     * followed by the 'for phone' flag.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param locationID the location ID; -1 for any location
     * @param eventTime the time of the event; null for any time
     * @param weekday true if the event occurs on a weekday; false for
     *        a weekend; null for either
     * @param full true if a row contains all of the the publicly
     *        accessible columns; false if a rows contains only a
     *        location ID
     */
    public Vector<Vector<Object>>
	listFirstAlarms(Connection conn, int userID, int ownerID,
				  int locationID, Time eventTime,
				  Boolean weekday, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("listFirstAlarms");
	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	}
	if (locationID != -1) {
	    sb.append(" AND locationID = ?");
	}
	if (eventTime != null) {
	    sb.append(" AND eventTime = ?");
	}
	if (weekday != null) {
	    sb.append(" AND weekday = ?");
	}
	s = String.format(s, sb.toString());
	try (PreparedStatement ps1 = conn.prepareStatement(s)) {
	    int ind = 1;
	    ps1.setInt(ind++, userID);
	    if (ownerID != -1) {
		ps1.setInt(ind++, ownerID);
	    }
	    if (locationID != -1) {
		ps1.setInt(ind++, locationID);
	    }
	    if (eventTime != null) {
		ps1.setTime(ind++, eventTime);
	    }
	    if (weekday != null) {
		ps1.setBoolean(ind++, weekday);
	    }
	    try (ResultSet rs = ps1.executeQuery()) {
		while (rs.next()) {
		    Vector<Object>row = new Vector<>();
		    row.add(rs.getInt(1));
		    row.add(rs.getInt(2));
		    row.add(rs.getInt(3));
		    row.add(rs.getTime(4));
		    row.add(rs.getBoolean(5));
		    row.add(rs.getTime(6));
		    row.add(rs.getBoolean(7));
		    row.add(rs.getBoolean(8));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		userID = (Integer)row.get(0);
		ownerID = (Integer)row.get(1);
		locationID = (Integer)row.get(2);
		row.set(0, getUserLabeledID(conn, userID));
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
		row.set(2, new LocationLabeledID
			(locationID, getLocationLabel(conn,locationID)));
		/*
		row.set(0, String.format("%d (%s)", userID,
					 getFullUserName(conn, userID)));
		row.set(1, String.format("%d (%s)", ownerID,
					 getOwnerLabel(conn, ownerID)));
		row.set(2, String.format("%d (%s)", locationID,
					 getLocationLabel(conn, locationID)));
		*/
		/*
		row.set(3,
			((java.sql.Time)row.get(3)).toLocalTime().format(tf));
		row.set(5,
			((java.sql.Time)row.get(5)).toLocalTime().format(tf));
		*/
	    }
	}
	return vector;
    }

    /**
     * Set fields in a row from the first alarm table.
     * The fields that can be set are the ones for the alarmTime,
     * forEmail, and forPhone arguments.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param locationID the location ID
     * @param eventTime the time of the event
     * @param weekday true if the event occurs on a weekday; false for
     *        a weekend
     * @param alarmTime the alarm time; null to ignore
     * @param forEmail the 'for email' flag; null to ignore
     * @param forPhone the 'for phone' flag; null to ignore
     */
    public void setFirstAlarm(Connection conn, int userID, int ownerID,
			      int locationID, Time eventTime, boolean weekday,
			      Time alarmTime,
			      Boolean forEmail, Boolean forPhone)

	throws SQLException
    {
	setFirstAlarm(conn, userID, ownerID, locationID, eventTime, weekday,
		      alarmTime, forEmail, forPhone, true);
    }

    /**
     * Set fields in a row from the first alarm table, indicating if the
     * database changes should be committed.
     * The fields that can be set are the ones for the alarmTime,
     * forEmail, and forPhone arguments.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param locationID the location ID
     * @param eventTime the time of the event
     * @param weekday true if the event occurs on a weekday; false for
     *        a weekend
     * @param alarmTime the alarm time; null to ignore
     * @param forEmail the 'for email' flag; null to ignore
     * @param forPhone the 'for phone' flag; null to ignore
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setFirstAlarm(Connection conn, int userID, int ownerID,
			      int locationID, Time eventTime, boolean weekday,
			      Time alarmTime,
			      Boolean forEmail, Boolean forPhone,
			      boolean commit)

	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useT = false;
	boolean useForE = false;
	boolean useForC = false;
	
	boolean first = true;

	if (alarmTime != null) useT = true;
	if (forEmail != null) useForE = true;
	if (forPhone != null) useForC = true;

	String s = getSQLProperty("setFirstAlarmData");
	if (useT) {
	    sb.append("alarmTime = ?");
	    first = false;
	}
	if (useForE) {
	    if (first == false) sb.append(", ");
	    sb.append("forEmail = ?");
	    first = false;
	}
	if (useForC) {
	    if (first == false) sb.append(", ");
	    sb.append("forPhone = ?");
	}
	s = String.format(s, sb.toString());
	// System.out.println("statement = " + s);
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useT) {
		    ps.setTime(ind++, alarmTime);
		}
		if (useForE) {
		    ps.setBoolean(ind++, forEmail);
		}
		if (useForC) {
		    ps.setBoolean(ind++, forPhone);
		}
		ps.setInt(ind++, userID);
		ps.setInt(ind++, ownerID);
		ps.setInt(ind++, locationID);
		ps.setTime(ind++, eventTime);
		ps.setBoolean(ind++, weekday);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setFirstAlarm");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a row to the second-alarm table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param locationID the location ID
     * @param offset the number of minutes before the event for this
     *        alarm
     * @param forEmail the 'for email' flag
     * @param forPhone the 'for phone' flag
     */
    public void addSecondAlarm(Connection conn, int userID, int ownerID,
			       int locationID, int offset,
			       boolean forEmail, boolean forPhone)
    throws SQLException, IllegalArgumentException
    {
	addSecondAlarm(conn, userID, ownerID, locationID, offset,
		       forEmail, forPhone, true);
    }

    /**
     * Add a row to the second-alarm table, indicating if the database changes
     * should be committed.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param locationID the location ID
     * @param offset the number of minutes before the event for this
     *        alarm
     * @param forEmail the 'for email' flag
     * @param forPhone the 'for phone' flag
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addSecondAlarm(Connection conn, int userID, int ownerID,
			       int locationID, int offset,
			       boolean forEmail, boolean forPhone,
			       boolean commit)
    throws SQLException, IllegalArgumentException
    {
	String s = getSQLProperty("insertSecondAlarm");
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, userID);
		ps.setInt(2, ownerID);
		ps.setInt(3, locationID);
		ps.setInt(4, offset);
		ps.setBoolean(5, forEmail);
		ps.setBoolean(6, forPhone);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addSecondAlarm");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a row from the second alarm table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param locationID the location ID
     */
    public void deleteSecondAlarm(Connection conn, int userID, int ownerID,
			      int locationID)
	throws SQLException, IllegalArgumentException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteSecondAlarm"))) {
		ps2.setInt(1, userID);
		ps2.setInt(2, ownerID);
		ps2.setInt(3, locationID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSecondAlarm");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows from the second alarm table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param locationID the location ID; -1 for any location
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteSecondAlarms(Connection conn, int userID, int ownerID,
				   int locationID, boolean force)
	throws SQLException, IllegalArgumentException
    {
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("listSecondAlarms");
	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	}
	if (locationID != -1) {
	    sb.append(" AND locationID = ?");
	}
	s = String.format(s, sb.toString());
	String s2 = getSQLProperty("deleteSecondAlarm");

	// force the deletion if we have at most one item to delete
	if (ownerID != -1 && locationID != -1) force = true;

	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1 = conn.prepareStatement(s)) {
		int ind = 1;
		ps1.setInt(ind++, userID);
		if (ownerID != -1) {
		    ps1.setInt(ind++, ownerID);
		}
		if (locationID != -1) {
		    ps1.setInt(ind++, locationID);
		}
		try (ResultSet rs = ps1.executeQuery()) {
		    try (PreparedStatement ps2 = conn.prepareStatement(s2)) {
			while (rs.next()) {
			       if (force == false) {
				   Console console = System.console();
				   if (console != null) {
				       String response = console.readLine
					   (String.format
					    ("deleting second alarm entry "
					     + "u=%d, o=%d, l=%d "
					     + "[Yn!<ESC>]:",
					     rs.getInt(1), rs.getInt(2),
					     rs.getInt(3)).trim());
				       if (response.equals("!")) {
					   force = true;
				       } else if (response.equals("\033")) {
					   break;
				       } else if (!response.equals("Y")) {
					   continue;
				       }
				   }
			       }
			       ps2.setInt(1, rs.getInt(1));
			       ps2.setInt(2, rs.getInt(2));
			       ps2.setInt(3, rs.getInt(3));
			       ps2.executeUpdate();
					  
			}
		    }
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSecondAlarms");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }


    /**
     * List matching rows from the second-alarm table.
     * Each row contains the userID, the ownerID, the locationID,
     * the offset, the 'forEmail' flag, and the 'forPhone' flag
     * in that order.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param locationID the location ID; -1 for any location
     * @param full true if the userID, ownerID, and locationID
     *       are represented by labeled IDs; false if they are
     *       represented by their integer values
     */
    public Vector<Vector<Object>>
	listSecondAlarms(Connection conn, int userID, int ownerID,
			 int locationID, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("listSecondAlarms");
	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	}
	if (locationID != -1) {
	    sb.append(" AND locationID = ?");
	}
	s = String.format(s, sb.toString());
	try (PreparedStatement ps1 = conn.prepareStatement(s)) {
	    int ind = 1;
	    ps1.setInt(ind++, userID);
	    if (ownerID != -1) {
		ps1.setInt(ind++, ownerID);
	    }
	    if (locationID != -1) {
		ps1.setInt(ind++, locationID);
	    }
	    try (ResultSet rs = ps1.executeQuery()) {
		while (rs.next()) {
		    Vector<Object>row = new Vector<>();
		    row.add(rs.getInt(1));
		    row.add(rs.getInt(2));
		    row.add(rs.getInt(3));
		    row.add(rs.getInt(4));
		    row.add(rs.getBoolean(5));
		    row.add(rs.getBoolean(6));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		userID = (Integer)row.get(0);
		ownerID = (Integer)row.get(1);
		locationID = (Integer)row.get(2);
		row.set(0, getUserLabeledID(conn, userID));
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
		row.set(2, new LocationLabeledID
			(locationID, getLocationLabel(conn,locationID)));
	    }
	}
	return vector;
    }

    /**
     * Set fields in the second-alarm table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param locationID the location ID; -1 for any location
     * @param offset the difference in minutes between the event and
     *        the alarm; -1 for no change
     * @param forEmail true if this alarm appears in a calendar sent to
     *        an email address; false if it does not; null indicates no change
     * @param forPhone true if this alarm appears in a calendar sent to
     *        a cell phone; false if it does not; null indicates no change
     */
    public void setSecondAlarm(Connection conn, int userID, int ownerID,
			       int locationID, int offset,
			       Boolean forEmail, Boolean forPhone)

	throws SQLException
    {
	setSecondAlarm(conn, userID, ownerID, locationID, offset,
		       forEmail, forPhone, true);
    }

    /**
     * Set fields in the second-alarm table, indicating if the changes should
     * be committed to a database.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param locationID the location ID; -1 for any location
     * @param offset the difference in minutes between the event and
     *        the alarm; -1 for no change
     * @param forEmail true if this alarm appears in a calendar sent to
     *        an email address; false if it does not; null indicates no change
     * @param forPhone true if this alarm appears in a calendar sent to
     *        a cell phone; false if it does not; null indicates no change
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setSecondAlarm(Connection conn, int userID, int ownerID,
			       int locationID, int offset,
			       Boolean forEmail, Boolean forPhone,
			       boolean commit)

	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useO = false;
	boolean useForE = false;
	boolean useForC = false;
	
	boolean first = true;

	if (offset != -1) useO = true;
	if (forEmail != null) useForE = true;
	if (forPhone != null) useForC = true;

	String s = getSQLProperty("setSecondAlarm");
	if (useO) {
	    sb.append("offset = ?");
	    first = false;
	}
	if (useForE) {
	    if (first == false) sb.append(", ");
	    sb.append("forEmail = ?");
	    first = false;
	}
	if (useForC) {
	    if (first == false) sb.append(", ");
	    sb.append("forPhone = ?");
	}
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useO) {
		    ps.setInt(ind++, offset);
		}
		if (useForE) {
		    ps.setBoolean(ind++, forEmail);
		}
		if (useForC) {
		    ps.setBoolean(ind++, forPhone);
		}
		ps.setInt(ind++, userID);
		ps.setInt(ind++, ownerID);
		ps.setInt(ind++, locationID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setSecondAlarm");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a rew to the pre-event default table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for any owner
     * @param peDefault true if the user normally attends pre-events for
     *        the owner; false otherwise
     */
    public void addPreEventDefault(Connection conn, int userID, int ownerID,
				   boolean peDefault)
	throws SQLException, IllegalArgumentException
    {
	addPreEventDefault(conn, userID, ownerID, peDefault, true);
    }

    /**
     * Add a rew to the pre-event default table, indicating if database changes
     * should be committed.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @param peDefault true if the user normally attends pre-events for
     *        the owner; false otherwise
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addPreEventDefault(Connection conn, int userID, int ownerID,
				   boolean peDefault, boolean commit)
    throws SQLException, IllegalArgumentException
    {

	String s = getSQLProperty("insertPreEventDefault");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, userID);
		ps.setInt(2, ownerID);
		ps.setBoolean(3, peDefault);
		ps.executeUpdate();
		if (commit) conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addPreEventDefault");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
			    
    /**
     * Delete a row from the pre-event default table.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     */
    public void deletePreEventDefault(Connection conn, int userID, int ownerID)
	throws SQLException, IllegalArgumentException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deletePreEventDefault"))) {
		ps2.setInt(1, userID);
		ps2.setInt(2, ownerID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deletePreEventDefault");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows from the pre-event default table, indicating if the
     * deletions are interactive.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for all owners
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deletePreEventDefaults(Connection conn, int userID, int ownerID,
				   boolean force)
	throws SQLException, IllegalArgumentException
    {
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("listPreEventDefaults");
	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	}
	s = String.format(s, sb.toString());
	String s2 = getSQLProperty("deletePreEventDefault");

	if (ownerID != -1) force = true; // at most one item to delete

	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1 = conn.prepareStatement(s)) {
		int ind = 1;
		ps1.setInt(ind++, userID);
		if (ownerID != -1) {
		    ps1.setInt(ind++, ownerID);
		}
		try (ResultSet rs = ps1.executeQuery()) {
		    try (PreparedStatement ps2 = conn.prepareStatement(s2)) {
			while (rs.next()) {
			       if (force == false) {
				   Console console = System.console();
				   if (console != null) {
				       String response = console.readLine
					   (String.format
					    ("deleting pre-event default entry "
					     + "u=%d, o=%d "
					     + "[Yn!<ESC>]:",
					     rs.getInt(1),
					     rs.getInt(2)).trim());
				       if (response.equals("!")) {
					   force = true;
				       } else if (response.equals("\033")) {
					   break;
				       } else if (!response.equals("Y")) {
					   continue;
				       }
				   }
			       }
			       ps2.setInt(1, rs.getInt(1));
			       ps2.setInt(2, rs.getInt(2));
			       ps2.executeUpdate();
					  
			}
			conn.commit();
		    }
		}
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deletePreEventDefaults");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * List rows from the pre-event default table.
     * Each row consists of a user ID, followed by an owner ID,
     * followed by a boolean value indicating fi the pre-event default is
     * true or false.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID; -1 for all owners
     * @param full true if the userID, ownerID, and locationID
     *       are represented by labeled IDs; false if they are
     *       represented by their integer values
     */
   public Vector<Vector<Object>>
	listPreEventDefaults(Connection conn, int userID, int ownerID,
			     boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
        if (userID == -1) {
	    throw new IllegalArgumentException("no user id");
	}
	String s = getSQLProperty("listPreEventDefaults");
	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	}
	s = String.format(s, sb.toString());
	try (PreparedStatement ps1 = conn.prepareStatement(s)) {
	    int ind = 1;
	    ps1.setInt(ind++, userID);
	    if (ownerID != -1) {
		ps1.setInt(ind++, ownerID);
	    }
	    try (ResultSet rs = ps1.executeQuery()) {
		while (rs.next()) {
		    Vector<Object>row = new Vector<>();
		    row.add(rs.getInt(1));
		    row.add(rs.getInt(2));
		    row.add(rs.getBoolean(3));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		userID = (Integer)row.get(0);
		ownerID = (Integer)row.get(1);
		row.set(0, new UserLabeledID(userID,
					     getFullUserName(conn,userID)));
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
		/*
		row.set(0, String.format("%d (%s)", userID,
					 getFullUserName(conn,userID)));
		row.set(1, String.format("%d (%s)", ownerID,
					 getOwnerLabel(conn, ownerID)));
		*/
	    }
	}
	return vector;
    }

    /**
     * Set the pre-event default field in a row in the pre-event default table.
     * Each row consists of a user ID, followed by an owner ID,
     * followed by a boolean value indicating fi the pre-event default is
     * true or false.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID;
     * @param peDefault true if the user normally attends pre-events for
     *        the owner; false otherwise
     */
    public void setPreEventDefault(Connection conn, int userID, int ownerID,
				   boolean peDefault)

	throws SQLException
    {
	setPreEventDefault(conn, userID, ownerID, peDefault, true);
    }

    /**
     * Set the pre-event default field in a row in the pre-event default table,
     * indicating if data base changes should be committed.
     * Each row consists of a user ID, followed by an owner ID,
     * followed by a boolean value indicating fi the pre-event default is
     * true or false.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID;
     * @param peDefault true if the user normally attends pre-events for
     *        the owner; false otherwise
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setPreEventDefault(Connection conn, int userID, int ownerID,
				   boolean peDefault, boolean commit)

	throws SQLException
    {
	String s = getSQLProperty("setPreEventDefault");
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setBoolean(1, peDefault);
		ps.setInt(2, userID);
		ps.setInt(3, ownerID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setPreEventDefault");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /*
     * Get the pre-event default.
     * @param conn the database connection
     * @param userID the userID
     * @param ownerID the owner ID
     * @return true if the prev-event default is true; false otherwise
     */
    public boolean getPreEventDefault(Connection conn, int userID, int ownerID)
	throws SQLException, IllegalArgumentException
    {
	if (userID == -1) {
	    throw new IllegalArgumentException("no userID");	
	}
	if (ownerID == -1) {
	    throw new IllegalArgumentException("no ownerID");
	}
	try (PreparedStatement ps =
	     conn.prepareStatement(getSQLProperty("getPreEventDefault"))) {
	    ps.setInt(1, userID);
	    ps.setInt(2, ownerID);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    return rs.getObject(1, Boolean.class);
		}
		return false;
	    }
	}
    }

    /**
     * Add a row to the event table.
     * @param conn the database connection
     * @param ownerID the owner ID
     * @param label the event's label
     * @param description the event's description
     */
    public void addEvent(Connection conn, int ownerID,
			    String label, String description)
	throws SQLException, IllegalArgumentException
    {
	addEvent(conn, ownerID, label, description, true);
    }

    /**
     * Add a row to the event table, indicating if database changes should
     * be committed.
     * @param conn the database connection
     * @param ownerID the owner ID
     * @param label the event's label
     * @param description the event's description
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addEvent(Connection conn, int ownerID,
			 String label, String description,
			 boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (ownerID == -1) {
	    throw new IllegalArgumentException("no ownerID");
	}
	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    if (label.length() == 0) label = null;
	}
	if (description != null) {
	    description = description.trim().replaceAll("\\s+\\s*", " ");
	    if (description.length() == 0) description = null;
	}

	String s = getSQLProperty("insertEvent");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, ownerID);
		ps.setString(2, label);
		ps.setString(3, description);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addEvent");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
			    
    /**
     * Delete a row from the event table.
     * @param conn the database connection
     * @param eventID  the event ID for the row
     */
    public void deleteEvent(Connection conn, int eventID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteEventByID"))) {
		ps2.setInt(1, eventID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEvent");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete rows whose labels match a patter from the event table.
     * @param conn the database connection
     * @param pattern a pattern for the event's label
     */
    public void deleteEvent(Connection conn, String pattern)
	throws SQLException
    {
	deleteEvent(conn, pattern, false);
    }

    /**
     * Delete rows whose labels match a patter from the event table,
     * indicating if the deletion is interactive or if all matching rows
     * are to be deleted.
     * @param conn the database connection
     * @param pattern a pattern for the event's label
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteEvent(Connection conn, String pattern, boolean force)
	throws SQLException
    {
	if (pattern == null || pattern.trim().length() == 0) {
	    throw new IllegalArgumentException("no pattern provided");
	}
	pattern = pattern.trim().replaceAll("\\s\\s+", "").toUpperCase();
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1
		 = conn.prepareStatement(getSQLProperty
					 ("listEventsMatching"))) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteEventByID"))) {
		    // System.out.format("finding events(s) \"%s\"\n", pattern);
		    ps1.setString(1, pattern);
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    int eventID = rs.getInt(1);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					("deleting event %d: %s [Yn!<ESC>]:",
					 eventID, rs.getString(2)).trim();
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, eventID);
			    ps2.executeUpdate();
			} while (rs.next());
		    } else {
			System.err.format("User matching '%s' does not exist\n",
					  pattern);
			throw new SQLException("no such entry in "
					       + "Event table: "
					       + pattern);
		    }
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEvent");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }


    /**
     * Delete rows from the event table.
     * @param conn the database connection
     * @param eventIDs  the event IDs that the rows will match
     */
    public void deleteEvent(Connection conn, int[] eventIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteEventByID"))) {
		for (int eventID: eventIDs) {
		    ps2.setInt(1, eventID);
		    ps2.executeUpdate();
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEvent");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * List the labels for rows in the event table with specified owners
     * @param conn the database connection
     * @param ownerID the owner ID; -1 for all owners
     * @return an array whose contents are string containing labels, after
     *         an initial element equal to "[ All ]"
     */
    public Object[] listEventLabels(Connection conn, int ownerID)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	Vector<Vector<Object>> rows;
	if (ownerID == -1) {
	    int[] ids = null;
	    rows = listEvents(conn, ids, true);
	} else {
	    rows = listEventsForOwner(conn, ownerID, true);
	}
	Object[] results = new Object[rows.size() + 1];
	results[0] = "[ All ]";
	int i = 1;
	for (Vector<Object> row: rows) {
	    results[i++] = row.get(2);
	}
	return results;
    }

    /**
     * List the event labeled IDs for rows in the event table with
     * specified owners
     * @param conn the database connection
     * @param ownerID the owner ID; -1 for all owners
     * @return an array provides the matching event labeled IDs
     */
    public EventLabeledID[] listEventLabeledIDs(Connection conn, int ownerID)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	Vector<Vector<Object>> rows;
	if (ownerID == -1) {
	    int[] ids = null;
	    rows = listEvents(conn, ids, true);
	} else {
	    rows = listEventsForOwner(conn, ownerID, true);
	}
	EventLabeledID[] results = new EventLabeledID[rows.size()];
	int i = 0;
	for (Vector<Object> row: rows) {
	    OwnerLabeledID owner = (OwnerLabeledID) row.get(1);
	    results[i++] = new EventLabeledID((Integer)row.get(0),
					      owner + ": "
					      + (String)row.get(2));
	}
	return results;
    }


    /**
     * List the rows in the event table matching specified owner IDs.
     * Each row's entries are in the following order: the event ID,
     * followed by an owner-labeled ID, followed by a label, followed by a
     * description.
     * @param conn the database connection
     * @param ownerID the owner ID; -1 for all owners
     * @param full true if full rows are returned; false if each row
     *        contains only an event ID
     * @return the table rows matching the specified owners
     */
    public Vector<Vector<Object>>
	listEventsForOwner(Connection conn, int ownerID, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	String q = (ownerID == -1)? getSQLProperty("listEvents"):
	    getSQLProperty("listEventsForOwner");
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    if (ownerID != -1) {
		ps.setInt(1, ownerID);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(4));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		ownerID = (Integer) row.get(1);
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
	    }
	}
	return vector;
    }

    /**
     * List the rows in the event table matching patterns.
     * Each row's entries are in the following order: the event ID,
     * followed by an owner-labeled ID, followed by a label, followed by a
     * description.
     * @param conn the database connection
     * @param patterns patterns used to match an owner's label
     * @param full true if full rows are returned; false if each row
     *        contains only an event ID
     * @return the table rows matching the specified patterms
     */
    public Vector<Vector<Object>>
	listEvents(Connection conn, String[] patterns, boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    String q = null;
	    if (patterns == null || patterns.length == 0) {
		q = getSQLProperty("listEvents");
		patterns = null;
	    } else {
		q = getSQLProperty("listEventsMatching");
		for (String pattern: patterns) {
		    if (pattern == null || pattern.trim().length() == 0) {
			q = getSQLProperty("listEvents");
			patterns = null;
			break;
		    } else {
			if (pattern.trim().equals("%")) {
			    q = getSQLProperty("listEvents");
			    patterns = null;
			    break;
			}
		    }
		}
	    }
	    if (patterns == null) {
		try (ResultSet rs = statement.executeQuery(q)) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    row.add(rs.getObject(4));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		}
	    } else {
		PreparedStatement ps = null;
		try {
		    ps = conn.prepareStatement(q);
		    for (String pattern: patterns) {
			pattern = pattern.trim().replace("\\s+", " ")
			    .toUpperCase();
			ps.setString(1, pattern);
			try (ResultSet rs = ps.executeQuery()) {
			    while (rs.next()) {
				if (full) {
				    Vector<Object> row = new Vector<Object>(2);
				    row.add(rs.getObject(1));
				    row.add(rs.getObject(2));
				    row.add(rs.getObject(3));
				    row.add(rs.getObject(4));
				    vector.add(row);
				} else {
				    Vector<Object> row = new Vector<Object>(1);
				    row.add(rs.getObject(1));
				    vector.add(row);
				}
			    }
			}
		    }
		} finally {
		    if (ps != null) ps.close();
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		int ownerID = (Integer) row.get(1);
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
	    }
	}
	return vector;
    }

    /**
     * List the rows in the event table matching event IDs.
     * Each row's entries are in the following order: the event ID,
     * followed by an owner-labeled ID, followed by a label, followed by a
     * description.
     * @param conn the database connection
     * @param ids the event IDs to match
     * @param full true if full rows are returned; false if each row
     *        contains only an event ID
     * @return the table rows matching the specified event IDs
     */
    public Vector<Vector<Object>>
	listEvents(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listEvents"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listEventsByID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(4));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    row.add(rs.getObject(4));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		int ownerID = (Integer) row.get(1);
		row.set(1, new OwnerLabeledID(ownerID,
					      getOwnerLabel(conn, ownerID)));
	    }
	}
	return vector;
    }
  
    int findEvent(Connection conn, int ownerID, String pattern)
	throws SQLException, IllegalArgumentException
    {
	StringBuilder sb = new StringBuilder();
	boolean useO = false;
	boolean useE = false;
	boolean first = true;
	if (ownerID != -1) {
	    sb.append("ownerID = ?");
	    useO = true;
	    first = false;
	}
	if (pattern == null || pattern.trim().length() == 0) {
	    pattern = null;
	} else {
	    pattern = pattern.trim().replaceAll("\\s+", " ");
	    if (first == false) sb.append(" AND ");
	    sb.append("TRIM(label) LIKE UPPER(?)");
	    useE = true;
	    first = false;
	}
	String q = String.format(getSQLProperty("findEventMatching"),
				 sb.toString()).trim();
	PreparedStatement ps = null;
	try {
	    ps = conn.prepareStatement(q);
	    int ind  = 1;
	    if (useO) ps.setInt(ind++, ownerID);
	    if (useE) ps.setString(ind++, pattern);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    int result = rs.getObject(1, Integer.class);
		    if (rs.next() == false) {
			return result;
		    }
		    throw new SQLException("multiple event IDs match pattern:"
					   + pattern);
		} else {
		    return -1;
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
    }

    /**
     * Set an event table field.
     * @param conn the database connection
     * @param eventID the event ID
     * @param ownerID the new owner ID; -1 to ignore
     * @param label the new label; null to ignore
     * @param description the new description; null to ignore
     */
    public void setEvent(Connection conn, int eventID, int ownerID,
			 String label, String description)
	throws SQLException
    {
	setEvent(conn, eventID, ownerID, label, description, true);
    }

    /**
     * Set an event table field, indicating if database changes should
     * be committed.
     * @param conn the database connection
     * @param eventID the event ID
     * @param ownerID the new owner ID; -1 to ignore
     * @param label the new label; null to ignore
     * @param description the new description; null to ignore
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setEvent(Connection conn, int eventID, int ownerID,
			 String label, String description,
			 boolean commit)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useL = false;
	boolean useD = false;
	boolean first = true;

	if (ownerID != -1) {
	    sb.append("ownerID = ?");
	    first = false;
	}

	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ").toUpperCase();
	    useL = true;
	    if (first == false) sb.append(", ");
	    sb.append("label = ?");
	    first = false;
	}
	if (description != null) {
	    description = description.trim(). replaceAll("\\s+", " ");
	    if (description.length() == 0) description = null;
	    useD = true;
	    if (first == false) sb.append(", ");
	    sb.append("description = ?");
	    first = false;
	}

	String s = getSQLProperty("setEventData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (ownerID != -1) {
		    ps.setInt(ind++, ownerID);
		}
		if (useL) {
		    ps.setString(ind++, label);
		}
		if (useD) {
		    ps.setString(ind++, description);
		}
		ps.setInt(ind++, eventID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setEvent");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a row to the event-instance table.
     * @param conn the database connection
     * @param eventID the event ID
     * @param locationID the location ID
     * @param preEventType a short description of what a pre-event is; null
     *        if there is no pre-event
     * @param preEventOffset the pre-event's offset in minutes from the event
     *        itself; 0 or negative if there is no pre-event
     * @param startDate the starting date for this event instance
     * @param startTime the starting time for this event instance
     * @param endDate the ending data for this event instance
     * @param endTime the ending time for this event instance
     * @param status TENTATIVE, CONFIRMED, CANCELLED or null if a status
     *        is not explicitly supplied
     */
    public void addEventInstance(Connection conn,
				 int eventID,
				 int locationID,
				 String preEventType,
				 int preEventOffset,
				 java.sql.Date startDate,
				 java.sql.Time startTime,
				 java.sql.Date endDate,
				 java.sql.Time endTime,
				 String status)
	throws SQLException, IllegalArgumentException
    {
	addEventInstance(conn, eventID, locationID, preEventType,
			 preEventOffset, startDate, startTime,
			 endDate, endTime, status, true);
    }

    /**
     * Add a row to the event-instance table, indicating if database changes
     * should be committed.
     * @param conn the database connection
     * @param eventID the event ID
     * @param locationID the location ID
     * @param preEventType a short description of what a pre-event is; null
     *        if there is no pre-event
     * @param preEventOffset the pre-event's offset in minutes from the event
     *        itself; 0 or negative if there is no pre-event
     * @param startDate the starting date for this event instance
     * @param startTime the starting time for this event instance
     * @param endDate the ending data for this event instance
     * @param endTime the ending time for this event instance
     * @param status TENTATIVE, CONFIRMED, CANCELLED or null if a status
     *        is not explicitly supplied
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addEventInstance(Connection conn,
				 int eventID,
				 int locationID,
				 String preEventType,
				 int preEventOffset,
				 java.sql.Date startDate,
				 java.sql.Time startTime,
				 java.sql.Date endDate,
				 java.sql.Time endTime,
				 String status,
				 boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (eventID == -1) {
	    throw new IllegalArgumentException("no eventID");
	}
	if (locationID == -1) {
	    throw new IllegalArgumentException("no locationID");
	}
	if (preEventType != null && preEventType.trim().length() == 0) {
	    preEventType = null;
	}

	if (preEventOffset <= 0 && preEventType != null) {
	    throw new IllegalArgumentException("pre-event type but no offset");
	} else if (preEventType == null && preEventOffset > 0) {
	    throw new IllegalArgumentException("pre-event offset but no type");
	}

	if (startDate == null) {
	    throw new IllegalArgumentException("no startDate");
	}
	if (startTime == null) {
	    throw new IllegalArgumentException("no startTime");
	}

	if (status == null) {
	    throw new IllegalArgumentException("no status");
	}

	String s = getSQLProperty("insertEventInstance");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, eventID);
		ps.setInt(2, locationID);
		if (preEventOffset == -1) {
		    ps.setNull(3, Types.VARCHAR);
		    ps.setNull(4, Types.INTEGER);
		} else {
		    ps.setString(3, preEventType);
		    ps.setInt(4, preEventOffset);
		}
		ps.setDate(5, startDate);
		ps.setTime(6, startTime);
		if (endDate == null) {
		    ps.setNull(7, Types.DATE);
		} else {
		    ps.setDate(7, endDate);
		}
		if (endTime == null) {
		    ps.setNull(8, Types.TIME);
		} else {
		    ps.setTime(8, endTime);
		}
		ps.setString(9, status);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addEventInstance");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a row from the event-instance table.
     * @param conn the database connection
     * @param instanceID the event-instance ID for the row to delete
     */
    public boolean deleteEventInstance(Connection conn, int instanceID)
	throws SQLException
    {
	int count = 0;
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteEventInstanceByID"))) {
		ps2.setInt(1, instanceID);
		count = ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEventInstance");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
	return (count > 0);
    }

    /**
     * Delete all rows from the event-instance table whose state is
     * "CANCELLED".
     * @param conn the database connection
     */
    public void deleteCancelledEventInstances(Connection conn)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteCanceledEventInstances"))) {
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEventInstance");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }


    /**
     * Delete rows from the event-instance table.
     * @param conn the database connection
     * @param instanceIDs the event-instance IDs for the rows to delete
     */
    public int deleteEventInstances(Connection conn, int[] instanceIDs)
	throws SQLException
    {
	int count = 0;
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteEventInstanceByID"))) {
		for (int instanceID: instanceIDs) {
		    ps2.setInt(1, instanceID);
		    count += ps2.executeUpdate();
		}
		conn.commit();
	    }
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteEventInstance");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
	return count;
    }

    /**
     * List instance labeled IDs for the event-instance table.
     * Normally either the ownerID or the eventID (or both) will be -1:
     * an eventID matches only a single ownerID, so providing
     * both is redundant.
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @return an array of instance labeled IDs
     */
    public InstanceLabeledID[] listInstanceLabeledIDs(Connection conn,
						      int ownerID,
						      int eventID,
						      int locationID)
	throws SQLException
    {
	return listInstanceLabeledIDs(conn, ownerID, eventID, locationID,
				      false);
    }

    /**
     * List instance labeled IDs for the event-instance table, optionally
     * starting with an element matching all event instances.
     * Normally either the ownerID or the eventID (or both) will be -1:
     * an eventID matches only a single ownerID, so providing
     * both is redundant. An instance labeled ID that represents all
     * event instances has an ID equal to -1 and a label with some
     * appropraite text ("[ All Instances ]").
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @param all true if the first instance indicates all instances;
     *        false otherwise
     * @return an array of instance labeled IDs
     */
    public InstanceLabeledID[] listInstanceLabeledIDs(Connection conn,
						      int ownerID,
						      int eventID,
						      int locationID,
						      boolean all)
	throws SQLException
    {
	Vector<Vector<Object>> rows;
	rows = listEventInstances(conn, ownerID, eventID, locationID, true);
	int sz = rows.size();
	InstanceLabeledID[] results = new InstanceLabeledID[all? (1+sz): sz];
	int i = 0;
	if (all) {
	    results[i++] = new InstanceLabeledID(-1, "[ All Instances ]");
	}
	for (Vector<Object>row: rows) {
	    int instanceID = (Integer) row.get(0);
	    EventLabeledID elid = (EventLabeledID)row.get(1);
	    LocationLabeledID llid = (LocationLabeledID)row.get(2);
	    java.sql.Date startDate = (java.sql.Date)row.get(5);
	    java.sql.Time startTime = (java.sql.Time)row.get(6);
	    String dateText = (startDate == null)? "TBD":
		startDate.toLocalDate().format(sdf);
	    String timeText = (startTime == null)? "TBD":
		startTime.toLocalTime().format(tf);
	    results[i++] = new InstanceLabeledID(instanceID,
						 elid + " at " + llid
						 + " on " + dateText
						 + " at " + timeText);
	    
	}
	return results;
    }

    /**
     * List rows from the event-instance table.
     * Each row of the table contains the following publiclly readable
     * fields: an instance ID, an event labeled ID, a location labeled
     * ID, a pre-event type, a pre-event offset, a start data, a start
     * time, and end date, and end time, and a status field
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return a vector of rows
     */
    public Vector<Vector<Object>>
	listEventInstances(Connection conn, int ownerID,
			   int eventID, int locationID,
			   boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useO = false;
	boolean useE = false;
	boolean useL = false;

	if (ownerID != -1) {
	    first = false;
	    sb.append(" AND tble.ownerID = ?");
	    useO = true;
	}
	if (eventID != -1) {
	    first = false;
	    sb.append(" AND tbli.eventID = ?");
	    useE = true;
	}
	if (locationID != -1) {
	    first = false;
	    sb.append(" AND tbli.locationID = ?");
	    useL = true;
	}
	String q;
	if (first) {
	    q = getSQLProperty("listEventInstances");
	} else {
 	    q = getSQLProperty("listEventInstancesMatching");
	    q = String.format(q, sb.toString());
	}
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useO) {
		ps.setInt(ind++, ownerID);
	    }
	    if (useE) {
		ps.setInt(ind++, eventID);
	    }
	    if (useL) {
		ps.setInt(ind++, locationID);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(4));
			row.add(rs.getObject(5));
			row.add(rs.getObject(6));
			row.add(rs.getObject(7));
			row.add(rs.getObject(8));
			row.add(rs.getObject(9));
			String s = rs.getObject(10, String.class);
			if (s == null) {
			    row.add(null);
			} else {
			    CalendarStatus status = null;
			    if (s.equals("TENTATIVE")) {
				status = CalendarStatus.TENTATIVE;
			    } else if (s.equals("CONFIRMED")) {
				status = CalendarStatus.CONFIRMED;
			    } else if (s.equals("CANCELLED")) {
				status = CalendarStatus.CANCELLED;
			    }
			    row.add(status);
			}
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
	    }
	}
	if (full) {
	    for (Vector<Object>row: vector) {
		eventID = (Integer) row.get(1);
		locationID = (Integer) row.get(2);
		row.set(1, new EventLabeledID(eventID,
					      getEventLabel(conn, eventID)));
		row.set(2, new LocationLabeledID(locationID,
						 getLocationLabel(conn,
								  locationID)));
	    }
	}
	return vector;
    }

    /**
     * List rows from the event-instance table matching additional criteria.
     * Each row of the table contains the following publiclly readable
     * fields: an instance ID, an event labeled ID, a location labeled
     * ID, a pre-event type, a pre-event offset, a start data, a start
     * time, and end date, and end time, and a status field
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param locationID an location ID; -1 for any location
     * @param startDate the start date for an event instance; null for any
     * @param startTime the start time for an event instance; null for any
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return a vector of rows
     */
    public Vector<Vector<Object>>
	listEventInstances(Connection conn, int ownerID, int locationID,
			   java.sql.Date startDate, java.sql.Time startTime,
			   String status,
			   boolean full)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useO = false;
	boolean useL = false;
	boolean useSD = false;
	boolean useST = false;
	boolean useS = false;
	    
	if (ownerID != -1) {
	    sb.append(" AND ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (locationID != -1) {
	    first = false;
	    sb.append(" AND locationID = ?");
	    useL = true;
	}
	if (startDate != null) {
	    first = false;
	    sb.append(" AND startDate = ?");
	    useSD = true;
	}
	if (startTime != null) {
	    first = false;
	    sb.append(" AND startTime = ?");
	    useST = true;
	}
	if (status != null) {
	    first = false;
	    sb.append("AND status = upper(?)");
	    useS = true;
	}
	String q;
	if (first) {
	    q = getSQLProperty("listEventInstances");
	} else {
 	    q = getSQLProperty("listEventInstancesMatching");
	    q = String.format(q, sb.toString());
	}
	
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useO) {
		ps.setInt(ind++, ownerID);
	    }
	    if (useL) {
		ps.setInt(ind++, locationID);
	    }
	    if (useSD) {
		ps.setDate(ind++, startDate);
	    }
	    if (useST) {
		ps.setTime(ind++, startTime);
	    }
	    if (useS) {
		ps.setString(ind++, status.trim().toUpperCase());
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(4));
			row.add(rs.getObject(5));
			row.add(rs.getObject(6));
			row.add(rs.getObject(7));
			row.add(rs.getObject(8));
			row.add(rs.getObject(9));
			String s = rs.getObject(10, String.class);
			if (s == null) {
			    row.add(null);
			} else {
			    CalendarStatus stat = null;
			    if (s.equals("TENTATIVE")) {
				stat = CalendarStatus.TENTATIVE;
			    } else if (s.equals("CONFIRMED")) {
				stat = CalendarStatus.CONFIRMED;
			    } else if (s.equals("CANCELLED")) {
				stat = CalendarStatus.CANCELLED;
			    }
			    row.add(stat);
			}
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
	    }
	}
	if (full) {
	    for (Vector<Object>row: vector) {
		int eventID = (Integer) row.get(1);
		locationID = (Integer) row.get(2);
		row.set(1, new EventLabeledID(eventID,
					      getEventLabel(conn, eventID)));
		row.set(2, new LocationLabeledID(locationID,
						 getLocationLabel(conn,
								  locationID)));
	    }
	}
	return vector;
    }

    /**
     * List rows from the event-instance table for a series of instance IDs.
     * Each row of the table contains the following publiclly readable
     * fields: an instance ID, an event labeled ID, a location labeled
     * ID, a pre-event type, a pre-event offset, a start data, a start
     * time, and end date, and end time, and a status field
     * @param conn the database connection
     * @param ids the instance IDs of the rows to include
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return a vector of rows
     */
    public Vector<Vector<Object>>
	listEventInstances(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		ps = conn.prepareStatement(getSQLProperty("listEventInstances"));
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listEventInstancesByID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			row.add(rs.getObject(4));
			row.add(rs.getObject(5));
			row.add(rs.getObject(6));
			row.add(rs.getObject(7));
			row.add(rs.getObject(8));
			row.add(rs.getObject(9));
			String s = rs.getObject(10, String.class);
			if (s == null) {
			    row.add(null);
			} else {
			    CalendarStatus status = null;
			    if (s.equals("TENTATIVE")) {
				status = CalendarStatus.TENTATIVE;
			    } else if (s.equals("CONFIRMED")) {
				status = CalendarStatus.CONFIRMED;
			    } else if (s.equals("CANCELLED")) {
				status = CalendarStatus.CANCELLED;
			    }
			    row.add(status);
			}
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    row.add(rs.getObject(4));
			    row.add(rs.getObject(5));
			    row.add(rs.getObject(6));
			    row.add(rs.getObject(7));
			    row.add(rs.getObject(8));
			    row.add(rs.getObject(9));
			    String s = rs.getObject(10, String.class);
			    if (s == null) {
				row.add(null);
			    } else {
				CalendarStatus status = null;
				if (s.equals("TENTATIVE")) {
				    status = CalendarStatus.TENTATIVE;
				} else if (s.equals("CONFIRMED")) {
				    status = CalendarStatus.CONFIRMED;
				} else if (s.equals("CANCELLED")) {
				    status = CalendarStatus.CANCELLED;
				}
				row.add(status);
			    }
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	if (full) {
	    for (Vector<Object>row: vector) {
		int eventID = (Integer) row.get(1);
		int locationID = (Integer) row.get(2);
		row.set(1, new EventLabeledID(eventID,
					      getEventLabel(conn, eventID)));
		row.set(2, new LocationLabeledID(locationID,
					       getLocationLabel(conn,
								locationID)));
	    }
	}
	return vector;
    }

    /**
     * List rows from the event-instance table for a series of instance IDs.
     * Each row of the table contains the following publiclly readable
     * fields: an instance ID, an event labeled ID, a location labeled
     * ID, a pre-event type, a pre-event offset, a start data, a start
     * time, and end date, and end time, and a status field
     * @param conn the database connection
     * @param instanceID the instance ID the row to include.
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return a vector of rows
     */
   public Vector<Vector<Object>>
	listEventInstance(Connection conn, int instanceID, boolean full)
	throws SQLException, IllegalArgumentException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (instanceID == -1) {
		throw new IllegalArgumentException("instance ID missing");
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listEventInstance"));
	    }

	    ps.setInt(1, instanceID);
	    rs = ps.executeQuery();
	    while (rs.next()) {
		if (full) {
		    Vector<Object> row = new Vector<Object>(2);
		    row.add(rs.getObject(1));
		    row.add(rs.getObject(2));
		    row.add(rs.getObject(3));
		    row.add(rs.getObject(4));
		    row.add(rs.getObject(5));
		    row.add(rs.getObject(6));
		    row.add(rs.getObject(7));
		    row.add(rs.getObject(8));
		    row.add(rs.getObject(9));
		    String s = rs.getObject(10, String.class);
		    if (s == null) {
			row.add(null);
		    } else {
			CalendarStatus status = null;
			if (s.equals("TENTATIVE")) {
			    status = CalendarStatus.TENTATIVE;
			} else if (s.equals("CONFIRMED")) {
			    status = CalendarStatus.CONFIRMED;
			} else if (s.equals("CANCELLED")) {
			    status = CalendarStatus.CANCELLED;
			}
			row.add(status);
		    }
		    vector.add(row);
		} else {
		    Vector<Object> row = new Vector<Object>(1);
		    row.add(rs.getObject(1));
		    vector.add(row);
		}
	    }
	    rs.close();
	} finally {
	    if (ps != null) ps.close();
	}
	if (full) {
	    for (Vector<Object>row: vector) {
		int eventID = (Integer) row.get(1);
		int locationID = (Integer) row.get(2);
		row.set(1, new EventLabeledID(eventID,
					      getEventLabel(conn, eventID)));
		row.set(2, new LocationLabeledID(locationID,
						 getLocationLabel(conn,
								  locationID)));
	    }
	}
	return vector;
    }

    /**
     * Find and instance ID  given an event, location, start date, and start
     * time.
     * @param conn the database connection
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @param startDate the start date for an event instance; null for any
     * @param startTime the start time for an event instance; null for any
     * @return and instance ID; -1 if there is no match
     */
    int findEventInstance(Connection conn, int eventID, int locationID,
			   java.sql.Date startDate, java.sql.Time startTime)
	throws SQLException
    {
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useE = false;
	boolean useL = false;
	boolean useSD = false;
	boolean useST = false;
	    
	if (eventID != -1) {
	    sb.append("eventID = ?");
	    first = false;
	    useE = true;
	}
	if (locationID != -1) {
	    if (first == false) sb.append(" AND ");
	    first = false;
	    sb.append("locationID = ?");
	    useL = true;
	}
	if (startDate != null) {
	    if (first == false) sb.append(" AND ");
	    first = false;
	    sb.append("startDate = ?");
	    useSD = true;
	}
	if (startTime != null) {
	    if (first == false) sb.append(" AND ");
	    first = false;
	    sb.append("startTime = ?");
	    useST = true;
	}
	if (first) return -1;

	String q = String.format(getSQLProperty("findEventInstancesMatching"),
				 sb.toString());
	
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useE) {
		ps.setInt(ind++, eventID);
	    }
	    if (useL) {
		ps.setInt(ind++, locationID);
	    }
	    if (useSD) {
		ps.setDate(ind++, startDate);
	    }
	    if (useST) {
		ps.setTime(ind++, startTime);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    int result = rs.getObject(1, Integer.class);
		    if (rs.next() == false) {
			return result;
		    }
		    throw new SQLException("multiple instance IDs "
					   + "match pattern:" + pattern);
		} else {
		    return -1;
		}
	    }
	}
    }

    /**
     * Set fields in a row of the event table.
     * @param conn the database connection
     * @param instanceID an instance ID for the row to modified
     * @param eventID an event; -1 if ignored
     * @param locationID an location ID; -1 if ignored
     * @param preEventType a descriptive type for a pre-event; null if ignored
     * @param preEventOffset the pre-event offset in minutes; -1 if ignored
     * @param startDate the start date for an event instance; null if ignored
     * @param startTime the start time for an event instance; null if ignored
     * @param endDate the end date for an event instance; null if ignored
     * @param endTime the end time for an event instance; null if ignored
     */
    public void setEventInstance(Connection conn, int instanceID,
				 int eventID, int locationID,
				 String preEventType,
				 int preEventOffset,
				 java.sql.Date startDate,
				 java.sql.Time startTime,
				 java.sql.Date endDate,
				 java.sql.Time endTime,
				 String status)
	throws SQLException, IllegalArgumentException
    {
	setEventInstance(conn, instanceID, eventID, locationID,
			 preEventType, preEventOffset,
			 startDate, startTime, endDate, endTime, status,
			 true);
    }

    /**
     * Set fields in a row of the event table, indicating if database
     * changes should be committed.
     * @param conn the database connection
     * @param instanceID an instance ID for the row to modified
     * @param eventID an event; -1 if ignored
     * @param locationID an location ID; -1 if ignored
     * @param preEventType a descriptive type for a pre-event; null if ignored
     * @param preEventOffset the pre-event offset in minutes; -1 if ignored
     * @param startDate the start date for an event instance; null if ignored
     * @param startTime the start time for an event instance; null if ignored
     * @param endDate the end date for an event instance; null if ignored
     * @param endTime the end time for an event instance; null if ignored
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setEventInstance(Connection conn, int instanceID,
				 int eventID, int locationID,
				 String preEventType,
				 int preEventOffset,
				 java.sql.Date startDate,
				 java.sql.Time startTime,
				 java.sql.Date endDate,
				 java.sql.Time endTime,
				 String status,
				 boolean commit)
	throws SQLException, IllegalArgumentException
    {
	StringBuilder sb = new StringBuilder();
	boolean useE = false;
	boolean useL = false;
	boolean usePT = false;
	boolean useP = false;
	boolean useSD = false;
	boolean useST = false;
	boolean useED = false;
	boolean useET = false;
	boolean useS = false;

	boolean first = true;
	
	if (instanceID == -1) {
	    throw new IllegalArgumentException("instanceID not set");
	}

	if (eventID != -1) {
	    sb.append("eventID = ?");
	    first = false;
	    useE = true;
	}

	if (locationID != -1) {
	    useL = true;
	    if (first == false) sb.append(", ");
	    sb.append("locationID = ?");
	    first = false;
	}

	if (preEventType != null) {
	    usePT = true;
	    if (first == false) sb.append(", ");
	    sb.append("preEventType = ?");
	    first = false;
	    preEventType = preEventType.trim();
	    if (preEventType.length() == 0)  preEventType = null;
	}

	if (preEventOffset != -1) {
	    useP = true;
	    if (first == false) sb.append(", ");
	    sb.append("preEventOffset = ?");
	    first = false;
	}
	if (startDate != null) {
	    useSD = true;
	    if (first == false) sb.append(", ");
	    sb.append("startDate = ?");
	    first = false;
	}
	if (startTime != null) {
	    useST = true;
	    if (first == false) sb.append(", ");
	    sb.append("startTime = ?");
	    first = false;
	}
	if (endDate != null) {
	    useED = true;
	    if (first == false) sb.append(", ");
	    sb.append("endDate = ?");
	    first = false;
	}
	if (endTime != null) {
	    useET = true;
	    if (first == false) sb.append(", ");
	    sb.append("endTime = ?");
	    first = false;
	}
	if (status != null) {
	    useS = true;
	    if (first == false) sb.append(", ");
	    sb.append("status = ?");
	    first = false;
	}
	if (first == true) return;

	String s = getSQLProperty("setEventInstanceData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useE) {
		    ps.setInt(ind++, eventID);
		}
		if (useL) {
		    ps.setInt(ind++, locationID);
		}
		if (usePT) {
		    if (preEventType == null) {
			ps.setNull(ind++, Types.VARCHAR);
		    } else {
			ps.setString(ind++, preEventType);
		    }
		}
		if (useP) {
		    ps.setInt(ind++, preEventOffset);
		}
		if (useSD) {
		    ps.setDate(ind++, startDate);
		}
		if (useST){
		    ps.setTime(ind++, startTime);
		}
		if (useED) {
		    ps.setDate(ind++, endDate);
		}
		if (useET){
		    ps.setTime(ind++, endTime);
		}
		if (useS) {
		    ps.setString(ind++, status);
		}
		ps.setInt(ind++, instanceID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setEventInstance");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a series to the series table.
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param label a short string naming the series
     */
    public void addSeries(Connection conn, int ownerID, String label)
	throws SQLException, IllegalArgumentException
    {
	addSeries(conn, ownerID, label, true);
    }

    /**
     * Add a series to the series table, indicating if database changes should
     * be committed.
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param label a short string naming the series
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addSeries(Connection conn, int ownerID, String label,
			  boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (label != null) {
	    label = label.trim();
	    if (label.length() == 0) label = null;
	}
	if (ownerID == -1) {
	    throw new IllegalArgumentException("no owner");
	}

	String s = getSQLProperty("insertSeries");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, ownerID);
		ps.setString(2, label);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addSeries");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
			    
    /**
     * Delete a series from the series table.
     * @param conn the database connection
     * @param seriesID the series ID
     */
    public void deleteSeries(Connection conn, int seriesID)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteSeriesByID"))) {
		ps2.setInt(1, seriesID);
		ps2.executeUpdate();
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSeries");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a series from the series table.
     * If there are multiple matches, the deletion will be interactive.
     * @param conn the database connection
     * @param ownerID  an ownerID used to search for a series
     * @param pattern a pattern used to match the label for the series
     */
    public void deleteSeries(Connection conn, int ownerID, String pattern)
	throws SQLException
    {
	deleteSeries(conn, ownerID, pattern, false);
    }

    /**
     * Delete a series from the series table, indicating if the deletions
     * are interactive.
     * @param conn the database connection
     * @param ownerID  an ownerID used to search for a series; -1 for any
     * @param pattern a pattern used to match the label for the series; null
     *        for any label
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteSeries(Connection conn, int ownerID, String pattern,
			     boolean force)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	if (ownerID != -1) {
	    if (first) sb.append("WHERE");
	    sb.append("ownerID = ?");
	    first = false;
	}
	if (pattern == null || pattern.trim().length() == 0) {
	    pattern  = null;
	} else {
	    pattern = pattern.trim().replaceAll("\\s\\s+", "");
	    if (first)sb.append("WHERE");
	    else sb.append(" AND ");
	    sb.append("TRIM(label) LIKE UPPER(?)");
	}
	String q = String.format(getSQLProperty("listSeries"), sb.toString())
	    .trim();
				 
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1 = conn.prepareStatement(q)) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteSeriesByID"))) {
		    // System.out.format("finding owner(s) \"%s\"\n", pattern);
		    int ind = 1;
		    if (ownerID != -1) {
			ps1.setInt(ind++, ownerID);
		    }
		    if (pattern != null) {
			ps1.setString(ind++, pattern);
		    }
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    int seriesID = rs.getInt(1);
			    ownerID = rs.getInt(2);
			    pattern = rs.getString(3);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					("deleting series %s (ownerID = %d)"
					 + " [Yn!<ESC>]:",
					 pattern.trim(), ownerID);
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, seriesID);
			    ps2.executeUpdate();
			} while (rs.next());
		    } else {
			System.err.format("Series matching '%s'"
					  + "does not exist\n", pattern);
			throw new SQLException("no such entry in "
					       + "Series table: "
					       + pattern);
		    }
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSeries");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * Delete a series from the series table.
     * @param conn the database connection
     * @param ownerIDs a list of owner IDs for those series that
     *        should be deleted
     */
    public void deleteSeries(Connection conn, int[] ownerIDs)
	throws SQLException
    {
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps2 = conn.prepareStatement
		 (getSQLProperty("deleteSeriesByID"))) {
		for (int ownerID: ownerIDs) {
		    ps2.setInt(1, ownerID);
		    ps2.executeUpdate();
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSeries");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     *  List the labels for a series.
     * @param conn the database connection
     * @param ownerID the ownerID for the series that should be listed; -1
     *        for any owner
     * @param all true if there is an entry for all series matching the
     *        specified owner (or all owners); false if there is no entry
     *        for all series
     */
    public Object[] listSeriesLabels(Connection conn, int ownerID, boolean all)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	Vector<Vector<Object>> rows;
	rows = listSeries(conn, ownerID, null, true);
	Object[] results = new Object[rows.size() + (all? 1: 0)];
	int i = 0;
	if (all) {
	    results[i++] = "[ All ]";
	}
	if (ownerID == -1) {
	    for (Vector<Object> row: rows) {
		results[i++] = (OwnerLabeledID)row.get(1)
		    + ": " + row.get(2);
	    }	    
	} else {
	    for (Vector<Object> row: rows) {
		results[i++] = row.get(2);
	    }
	}
	return results;
    }

    /**
     *  List the series label IDs for a series.
     * @param conn the database connection
     * @param ownerID the ownerID for the series that should be listed; -1
     *        for any owner
     */
    public SeriesLabeledID[] listSeriesLabeledIDs(Connection conn, int ownerID)
	throws SQLException
    {
	// This returns a string because JOptionPane 'show' methods
	// do not take vectors as arguments, whereas JCombobox can
	// handle both a vector and an array.
	Vector<Vector<Object>> rows;
	rows = listSeries(conn, ownerID, null, true);
	SeriesLabeledID[] results = new SeriesLabeledID[rows.size()];
	int i = 0;
	if (ownerID == -1) {
	    for (Vector<Object> row: rows) {
		String tag = (OwnerLabeledID)row.get(1)
		    + ":" + (String)row.get(2);
		results[i++] = new SeriesLabeledID((Integer)row.get(0), tag);
	    }
	} else {
	    for (Vector<Object> row: rows) {
		results[i++] = new SeriesLabeledID((Integer)row.get(0),
						   (String)row.get(2));
	    }
	}
	return results;
    }

    /**
     * List rows from the series table.
     * A row may contain the following fields: a series ID, followed by
     * an owner ID, followed by a label.
     * @param conn the database connection
     * @param ownerID the ownerID for the series that should be listed; -1
     *        for any owner
     * @param pattern a pattern used to match the label for the series; null
     *        for any label
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return selected rows from the series table
     */
    public Vector<Vector<Object>>
	listSeries(Connection conn, int ownerID, String pattern, boolean full)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useO = false;
	boolean useL = false;
	if (ownerID != -1) {
	    if (first) sb.append("WHERE ");
	    sb.append(" ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (pattern != null) {
	    pattern = pattern.trim().replaceAll("\\s\\s+", " ");
	    if (pattern.length() == 0) pattern = null;
	}
	if (pattern != null) {
	    if (first) sb.append("WHERE ");
	    if (!first) sb.append(" AND ");
	    sb.append("TRIM(label) LIKE UPPER(?)");
	    useL = true;
	    first = false;
	}
	String q = String.format(getSQLProperty("listSeries"), sb.toString());
	if (first) q = q.trim();

	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (Statement statement = conn.createStatement()) {
	    try (PreparedStatement ps = conn.prepareStatement(q)) {
		int ind = 1;
		if (useO) ps.setInt(ind++, ownerID);
		if (useL) ps.setString(ind++, pattern);
		try (ResultSet rs = ps.executeQuery()) {
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		}
	    }
	}
	if (full) {
	    if (ownerID == -1) {
		for (Vector<Object> row: vector) {
		    ownerID = (Integer)row.get(1);
		    String owner = getOwnerLabel(conn, ownerID);
		    row.set(1, new OwnerLabeledID(ownerID, owner));
		}
	    } else {
		for (Vector<Object> row: vector) {
		    ownerID = (Integer)row.get(1);
		    String owner = getOwnerLabel(conn, ownerID);
		    row.set(1, new OwnerLabeledID(ownerID, owner));
		}
	    }
	}
	return vector;
    }
    /**
     * Get the series labeled ID for an entry in the series table.
     * @param conn the database connection
     * @param seriesID the series ID
     * @param withOwner true if the owner label should be inlcuded in
     *        the labeled ID; false if just the series label should be
     *        included
     * @return a series labeled ID for the specified series ID
     */
    public SeriesLabeledID getSeriesLabeledID(Connection conn, int seriesID,
					      boolean withOwner)
	throws SQLException
    {
	String q = getSQLProperty("listSeriesByID");
	String label = null;
	int ownerID = -1;
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    ps.setInt(1, seriesID);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    label = rs.getString(3);
		    if (withOwner) {
			ownerID = rs.getInt(1);
		    }
		} else {
		    return null;
		}
	    }
	}
	if (label != null) {
	    if (withOwner) {
		if (ownerID != -1) {
		    String owner = getOwnerLabel(conn, ownerID);
		    return new SeriesLabeledID(seriesID, 
					       owner + ": " + label);
		}
	    } else {
		return new SeriesLabeledID(seriesID, label);
	    }
	}
	return null;
    }

    /** List rows in the series table that match specific series IDs.
     * @param conn the database connection
     * @param ids the series IDs for the series to list
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return a series labeled ID for the specified series ID
     */
    public Vector<Vector<Object>>
	listSeries(Connection conn, int[] ids, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (ids == null || ids.length == 0) {
		String q = String.format(getSQLProperty("listSeries"), "")
		    .trim();
		ps = conn.prepareStatement(q);
		ids = null;
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listSeriesByID"));
	    }
	    if (ids == null) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		for (int id: ids) {
		    ps.setInt(1, id);
		    rs = ps.executeQuery();
		    while (rs.next()) {
			if (full) {
			    Vector<Object> row = new Vector<Object>(2);
			    row.add(rs.getObject(1));
			    row.add(rs.getObject(2));
			    row.add(rs.getObject(3));
			    vector.add(row);
			} else {
			    Vector<Object> row = new Vector<Object>(1);
			    row.add(rs.getObject(1));
			    vector.add(row);
			}
		    }
		    rs.close();
		}
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		int ownerID = (Integer)row.get(1);
		String owner = getOwnerLabel(conn, ownerID);
		row.set(1, new OwnerLabeledID(ownerID, owner));
	    }
	}
	return vector;
    }
  
    /** List rows in the series table that match a specific series ID, or
     *  optionally all series IDs.
     * @param conn the database connection
     * @param seriesID the series ID; -1 for all series IDs
     * @param full true if all publicly readable fields are include; false
     *        if a row contains only the instance ID field
     * @return rows that match the specified series IDs
     */
    public Vector<Vector<Object>>
	listSeries(Connection conn, int seriesID, boolean full)
	throws SQLException
    {
	PreparedStatement ps = null;
	ResultSet rs = null;
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try {
	    if (seriesID == -1) {
		String q = String.format(getSQLProperty("listSeries"), "")
		    .trim();
		ps = conn.prepareStatement(q);
	    } else {
		ps = conn.prepareStatement
		    (getSQLProperty("listSeriesByID"));
	    }
	    if (seriesID == -1) {
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    } else {
		ps.setInt(1, seriesID);
		rs = ps.executeQuery();
		while (rs.next()) {
		    if (full) {
			Vector<Object> row = new Vector<Object>(2);
			row.add(rs.getObject(1));
			row.add(rs.getObject(2));
			row.add(rs.getObject(3));
			vector.add(row);
		    } else {
			Vector<Object> row = new Vector<Object>(1);
			row.add(rs.getObject(1));
			vector.add(row);
		    }
		}
		rs.close();
	    }
	} finally {
	    if (ps != null) ps.close();
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		int ownerID = (Integer)row.get(1);
		String owner = getOwnerLabel(conn, ownerID);
		row.set(1, new OwnerLabeledID(ownerID, owner));
	    }
	}
	return vector;
    }
  
    /**
     * Find a series ID given an owner ID and a pattern used to match labels.
     * @param conn the database connection
     * @param ownerID the ownerID for the series that should be listed; -1
     *        if ignored
     * @param pattern a pattern used to match labels
     * @return the series ID; -1 if there was not a match
     */
    public int findSeries(Connection conn, int ownerID, String pattern)
	throws SQLException, IllegalArgumentException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useO = false;
	boolean useL = false;
	if (ownerID != -1) {
	    if (first) sb.append("WHERE ");
	    sb.append(" ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (pattern != null) {
	    pattern = pattern.trim().replaceAll("\\s\\s+", " ");
	    if (pattern.length() == 0) pattern = null;
	}
	if (pattern != null) {
	    if (first) sb.append("WHERE ");
	    if (!first) sb.append(" AND ");
	    sb.append("TRIM(label) LIKE UPPER(?)");
	    useL = true;
	    first = false;
	}
	String q = String.format(getSQLProperty("findSeries"), sb.toString());
	if (first) q = q.trim();

	try (Statement statement = conn.createStatement()) {
	    try (PreparedStatement ps = conn.prepareStatement(q)) {
		int ind = 1;
		if (useO) ps.setInt(ind++, ownerID);
		if (useL) ps.setString(ind++, pattern);
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			int result = rs.getInt(1);
			if (rs.next() == false) {
			    return result;
			}
			throw new SQLException
			    ("multiple series IDs match: ownerid = "
			     + ownerID + ", pattern = \""
				       + pattern  + "\"");
		    } else {
			return -1;
		    }
		}
	    }
	}
    }

    /**
     * Set fields for a row in the series table.
     * @param conn the database connection
     * @param seriesID the series ID for the row
     * @param ownerID the new ownerID; -1 if ignored
     * @param label the new label; null if ignored
     */
    public void setSeries(Connection conn, int seriesID,
			  int ownerID, String label)
	throws SQLException
    {
	setSeries(conn, seriesID, ownerID, label, true);
    }

    /**
     * Set fields for a row in the series table, optionally committing
     * database changes.
     * @param conn the database connection
     * @param seriesID the series ID for the row
     * @param ownerID the new ownerID; -1 if ignored
     * @param label the new label; null if ignored
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setSeries(Connection conn, int seriesID,
			  int ownerID, String label, boolean commit)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean useO = false;
	boolean useL = false;
	
	boolean first = true;

	if (ownerID != -1) {
	    sb.append("ownerID = ?");
	    useO = true;
	    first = false;
	}

	if (label != null) {
	    label = label.trim().replaceAll("\\s+\\s*", " ");
	    if (label.length() == 0) {
		label = null;
	    } else {
		useL = true;
		if (first == false) sb.append(", ");
		sb.append("label = ?");
		first = false;
	    }
	}
	if (first) return;
	String s = getSQLProperty("setSeries");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (useO) {
		    ps.setInt(ind++, ownerID);
		}
		if (useL) {
		    ps.setString(ind++, label);
		}
		ps.setInt(ind++, seriesID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setSeries");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Add a row to the series-instance table.
     * @param conn the database connection
     * @param seriesID the series ID for the new row
     * @param instanceID the instanceID for the new row
     */
    public void addSeriesInstance(Connection conn, int seriesID, int instanceID)
	throws SQLException, IllegalArgumentException
    {
	addSeriesInstance(conn, seriesID, instanceID, true);
    }

    /**
     * Add a row to the series-instance table, indicating if database changes
     * should be committed..
     * @param conn the database connection
     * @param seriesID the series ID for the new row
     * @param instanceID the instanceID for the new row
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addSeriesInstance(Connection conn, int seriesID, int instanceID,
				  boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (seriesID == -1) {
	    throw new IllegalArgumentException("no series");
	}
	if (instanceID == -1) {
	    throw new IllegalArgumentException("no event instance");
	}

	String s = getSQLProperty("insertSeriesInstance");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, seriesID);
		ps.setInt(2, instanceID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addSeriesInstance");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }
			    

    /**
     * Delete rows from the series-instance table.
     * @param conn the database connection
     * @param seriesID the series ID for the row; -1 for any
     * @param instanceID the instanceID for the row; -1 for any
     */
    public void deleteSeriesInstance(Connection conn, int seriesID,
				     int instanceID)
	throws SQLException
    {
	deleteSeriesInstance(conn, seriesID, instanceID, false);
    }

    /**
     * Delete rows from the series-instance table, indicating if multiple
     * deletions should be done interactively.
     * @param conn the database connection
     * @param seriesID the series ID for the row; -1 for any
     * @param instanceID the instanceID for the row; -1 for any
     * @param force true if all entries that match the pattern should be
     *             automatically deleted; false if the deletion is interactive
     */
    public void deleteSeriesInstance(Connection conn, int seriesID,
				     int instanceID, boolean force)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	if (seriesID != -1) {
	    sb.append("seriesID = ?");
	    first = false;
	}
	if (instanceID != -1) {
	    if  (first == false) sb.append(" AND ");
	    sb.append("instanceID = ?");
	    first = false;
	}
	if (first) return;
	String q = String.format(getSQLProperty("listSeriesInstance"),
				 sb.toString());
	try {
	    conn.setAutoCommit(false);
	    try (PreparedStatement ps1 = conn.prepareStatement(q)) {
		try (PreparedStatement ps2 = conn.prepareStatement
		     (getSQLProperty("deleteSeriesInstanceByID"))) {
		    // System.out.format("finding owner(s) \"%s\"\n", pattern);
		    int ind = 1;
		    if (seriesID != -1) {
			ps1.setInt(ind++, seriesID);
		    }
		    if (instanceID != -1) {
			ps1.setInt(ind++, instanceID);
		    }
		    ResultSet rs = ps1.executeQuery();
		    if (rs.next()) {
			do {
			    seriesID = rs.getInt(1);
			    instanceID = rs.getInt(2);
			    if (force == false) {
				Console console = System.console();
				if (console != null) {
				    String response = console.readLine
					("deleting series %d, instance %d"
					 + " [Yn!<ESC>]:",
					 seriesID, instanceID);
				    if (response.equals("!")) {
					force = true;
				    } else if (response.equals("\033")) {
					break;
				    } else if (!response.equals("Y")) {
					continue;
				    }
				}
			    }
			    ps2.setInt(1, seriesID);
			    ps2.executeUpdate();
			} while (rs.next());
			
		    } else {
			System.err.format("SeriesInstance matching '%s'"
					  + "does not exist\n", pattern);
			throw new SQLException("no such entry in "
					       + "SeriesInstance table: "
					       + pattern);
		    }
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteSeriesInstance");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }

    /**
     * List rows from the series-instance table.
     * Each row in the table contains a series ID followed by am
     * instance ID.
     * @param conn the database connection
     * @param seriesID the series ID for a row; -1 if ignored
     * @param instanceID the instanceID for a row; -1 if ignored
     * @param full true if the IDs are returned as labeled ids; false
     *        if returned as integers
     * @return a table of rows
     */
    public Vector<Vector<Object>>
	listSeriesInstance(Connection conn, int seriesID, int instanceID,
			   boolean full)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useS = false;
	boolean useI = false;
	if (seriesID != -1) {
	    if (first) sb.append("WHERE ");
	    sb.append(" seriesID = ?");
	    first = false;
	    useS = true;
	}
	if (instanceID != -1) {
	    if (first) sb.append("WHERE ");
	    sb.append(" instanceID = ?");
	    first = false;
	    useI = true;
	}

	String q = String.format(getSQLProperty("listSeriesInstance"),
				 sb.toString());
	if (first) q = q.trim();
	// System.out.println("q = " + q);
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useS) ps.setInt(ind++, seriesID);
	    if (useI) ps.setInt(ind++, instanceID);
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    Vector<Object> row = new Vector<Object>(2);
		    row.add(rs.getObject(1));
		    row.add(rs.getObject(2));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		seriesID = (Integer)row.get(0);
		// String series = getSeriesLabel(conn, seriesID);
		row.set(0, getSeriesLabeledID(conn, seriesID, true));
		instanceID = (Integer)row.get(1);
		String instance = 
		    getEventInstanceLabel(conn, instanceID, false);
		row.set(1, new InstanceLabeledID(instanceID,
						 instance));
	    }
	}
	return vector;
    }

    /**
     * List rows from the series-instance table by owner.
     * Each row in the table contains a series ID followed by am
     * instance ID.
     * @param conn the database connection
     * @param seriesID the series ID for a row; -1 if ignored
     * @param ownerID the owner id for a row; -1 if ignored
     * @param full true if the IDs are returned as labeled ids; false
     *        if returned as integers
     * @return a table of rows
     */
    public Vector<Vector<Object>>
	listSeriesInstanceByOwner(Connection conn, int seriesID, int ownerID,
			   boolean full)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;
	boolean useS = false;
	boolean useO = false;
	if (seriesID != -1) {
	    sb.append(" AND stbl.seriesID = ?");
	    first = false;
	    useS = true;
	}
	if (ownerID != -1) {
	    sb.append(" AND etbl.ownerID = ?");
	    first = false;
	    useO = true;
	}

	String q = String.format(getSQLProperty("listSeriesInstanceByOwner"),
				 sb.toString());
	if (first) q = q.trim();
	// System.out.println("q = " + q);
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useS) ps.setInt(ind++, seriesID);
	    if (useO) ps.setInt(ind++, ownerID);
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    Vector<Object> row = new Vector<Object>(2);
		    row.add(rs.getObject(1));
		    row.add(rs.getObject(2));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		seriesID = (Integer)row.get(0);
		row.set(0, getSeriesLabeledID(conn, seriesID, true));
		int instanceID = (Integer)row.get(1);
		String instance = 
		    getEventInstanceLabel(conn, instanceID, false);
		row.set(1, new InstanceLabeledID(instanceID,
						 instance));
	    }
	}
	return vector;
    }

    /**
     * Add a row to the attendee table.
     * @param conn the database connection
     * @param userID the userID for the new row
     * @param instanceID the instance ID for the new
     * @param attendingPreEvent true if the user will attend any pre-event
     *        for the instance; false otherwise
     * @param seriesID the ID; -1 if there is none for the new row
     */
    public void addAttendee(Connection conn, int userID, int instanceID,
			    boolean attendingPreEvent, int seriesID)
	throws SQLException, IllegalArgumentException
    {
	addAttendee(conn, userID, instanceID, attendingPreEvent, seriesID,
		    true);
    }


    /**
     * Add a row to the attendee table, indicating if database changes
     * should be commmitted.
     * @param conn the database connection
     * @param userID the userID for the new row
     * @param instanceID the instance ID for the new
     * @param attendingPreEvent true if the user will attend any pre-event
     *        for the instance; false otherwise
     * @param seriesID the ID; -1 if there is none for the new row
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void addAttendee(Connection conn, int userID, int instanceID,
			    boolean attendingPreEvent, int seriesID,
			    boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (userID == -1) {
	    throw new IllegalArgumentException("no userID");
	}
	if (instanceID == -1) {
	    throw new IllegalArgumentException("no instanceID");
	}

	String s = getSQLProperty("insertAttendee");

	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		ps.setInt(1, userID);
		ps.setInt(2, instanceID);
		ps.setBoolean(3, attendingPreEvent);
		if (seriesID == -1) {
		    ps.setNull(4, Types.INTEGER);
		} else {
		    ps.setInt(4, seriesID);
		}
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back addAttendee");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * Delete one or more rows from the attendee table.
     * @param conn the database connection
     * @param userID the userID for a row; -1 for any
     * @param instanceID the instance ID for a row; -1 for any
     * @param attendeeState the state of an attendee (ACTIVE,
     *        CANCELLING, or CANCELLED) with null implying CANCELLED.
     * @param seriesID the ID; -1 for any
     * @param force true if all entries that match should be
     *        automatically deleted; false if the deletion is interactive
     *        when more than one rows were selected
     */
    public void deleteAttendee(Connection conn, int userID,
			       int instanceID,
			       String attendeeState,
			       int seriesID,
			       boolean force)
	throws SQLException
    {
	StringBuilder sb = new StringBuilder();
	sb.append("WHERE attendeeState = ?");
	if (userID != -1) {
	    sb.append(" AND userID = ?");
	}
	if (instanceID != -1) {
	    sb.append(" AND instanceID = ?");
	    
	}
	if (seriesID != -1) {
	    sb.append(" AND seriesID = ?");
	}
	// in this case, we have at most only one row to delete
	if (userID != -1 && instanceID != -1) force = true;

	if (attendeeState == null) attendeeState = "CANCELLED";
	String s = String.format(getSQLProperty("listAttendees"),
				 sb.toString());
	
	try {
	    conn.setAutoCommit(false);
	    ArrayList<int[]> todoList = new ArrayList<>(32);
	    try (PreparedStatement ps1 = conn.prepareStatement(s)) {
		int ind  = 1;
		ps1.setString(ind++, attendeeState);
		if (userID != -1) {
		    ps1.setInt(ind++, userID);
		}
		if (instanceID != -1) {
		    ps1.setInt(ind++, instanceID);
		}
		if (seriesID != -1) {
		    ps1.setInt(ind++, seriesID);
		}
		try (ResultSet rs = ps1.executeQuery()) {
		    while (rs.next()) {
			userID = rs.getInt(1);
			instanceID = rs.getInt(2);
			String state = rs.getString(3);
			seriesID = rs.getInt(5);
			if (force == false) {
			    Console console = System.console();
			    String request =
				String.format("userID %d, instanceID %d, "
					      + "state %s, seriesID %d "
					      + " [Yn!<ESC>]:",
					      userID, instanceID, state,
					      seriesID);
			    String response = console.readLine(request);
			    if (response.equals("!")) {
				force = true;
			    } else if (response.equals("\033")) {
				break;
			    } else if (!response.equals("Y")) {
				continue;
			    }
			}
			int[] row = new int[2];
			row[0] = userID;
			row[1] = instanceID;
			todoList.add(row);
			/*
			  ps2.setInt(1, userID);
			  ps2.setInt(2, instanceID);
			  ps2.executeUpdate();
			*/
		    }
		}
	    }
	    try (PreparedStatement ps2
		 = conn.prepareStatement(getSQLProperty("deleteAttendee"))) {
		for (int[] row: todoList) {
		    userID = row[0];
		    instanceID = row[1];
		    System.out.format("deleting attendee: "
				      + "userid=%d, instanceID=%d\n",
				      userID, instanceID);
		    ps2.setInt(1, userID);
		    ps2.setInt(2, instanceID);
		    ps2.executeUpdate();
		}
	    }
	    conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back deleteAttendee");
		conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    conn.setAutoCommit(true);
	}
    }
    /**
     * List attendees
     * The fields listed are in the following order: userID,
     * instanceID, attendeeState, attendingPreEvent, and seriesID
     * @param conn the database connection
     * @param userID the userID for a row; -1 for any
     * @param instanceID the instance ID for a row; -1 for any
     * @param seriesID the ID; -1 for any
     * @param attendeeState the state of an attendee (ACTIVE,
     *        CANCELLING, or CANCELLED) with null implying CANCELLED.
     * @param full true if integer values are represented as labeled ids
     *        and the attendeeState as an enum; false if the values are
     *        integers and strings .
     */
    public Vector<Vector<Object>>
	listAttendees(Connection conn, int userID, int instanceID,
		      int seriesID, String attendeeState,
		      boolean full)
	throws SQLException
    {
	boolean first = true;
	StringBuilder sb = new StringBuilder();
	if (userID != -1) {
	    if (first) sb.append("WHERE "); else sb.append(" AND ");
	    first = false;
	    sb.append("userID = ?");
	}
	if (instanceID != -1) {
	    if (first) sb.append("WHERE "); else sb.append(" AND ");
	    first = false;
	    sb.append("instanceID = ?");
	}
	if (seriesID != -1) {
	    if (first) sb.append("WHERE "); else sb.append(" AND ");
	    first = false;
	    sb.append("seriesID = ?");
	}
	if (attendeeState != null) {
	    if (first) sb.append("WHERE "); else sb.append(" AND ");
	    first = false;
	    sb.append("attendeeState = ?");
	}
	String q = String.format(getSQLProperty("listAttendees"),
				 sb.toString());
	Vector<Vector<Object>> vector = new Vector<Vector<Object>>();
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (userID != -1) {
		ps.setInt(ind++, userID);
	    }
	    if (instanceID != -1) {
		ps.setInt(ind++, instanceID);
	    }
	    if (seriesID != -1) {
		ps.setInt(ind++, seriesID);
	    }
	    if (attendeeState != null) {
		ps.setString(ind++, attendeeState);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    Vector<Object> row = new Vector<Object>();
 		    row.add(rs.getObject(1));
		    row.add(rs.getObject(2));
		    row.add(rs.getObject(3));
		    row.add(rs.getObject(4));
		    row.add(rs.getObject(5));
		    vector.add(row);
		}
	    }
	}
	if (full) {
	    for (Vector<Object> row: vector) {
		userID = (Integer) row.get(0);
		instanceID = (Integer) row.get(1);
		attendeeState = (String) row.get(2);
		if (attendeeState.equals("ACTIVE")) {
		    row.set(2, AttendeeState.ACTIVE);
		} else if (attendeeState.equals("CANCELLING")) {
		    row.set(2, AttendeeState.CANCELLING);
		} else if (attendeeState.equals("CANCELLED")) {
		    row.set(2, AttendeeState.CANCELLED);
		}
		Boolean attendingPreEvent = (Boolean)row.get(3);
		seriesID = (Integer)row.get(4);
		row.set(0, getUserLabeledID(conn, userID));
		row.set(1, getInstanceLabeledID(conn, instanceID));
		row.set(4, getSeriesLabeledID(conn, seriesID, true));
	    }
	}
	return vector;
    }

    /**
     * Set fields in a row of the attendee table.
     * @param conn the database connection
     * @param userID the userID for a row
     * @param instanceID the instance ID for a row
     * @param seriesID the new series ID; -1 to ignore
     * @param attendeeState the new state of an attendee (ACTIVE,
     *        CANCELLING, or CANCELLED); null to ignore.
     */
    public void setAttendee(Connection conn, int userID, int instanceID,
			    String attendeeState, int seriesID)
	throws SQLException, IllegalArgumentException
    {
	setAttendee(conn, userID, instanceID, attendeeState, seriesID, true);
    }

    /**
     * Set fields in a row of the attendee table.
     * This method can change the attendeeState or the seriesID fields.
     * @param conn the database connection
     * @param userID the userID for a row
     * @param instanceID the instance ID for a row
     * @param seriesID the new series ID; -1 to ignore
     * @param attendeeState the new state of an attendee (ACTIVE,
     *        CANCELLING, or CANCELLED); null to ignore.
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void setAttendee(Connection conn, int userID, int instanceID,
			    String attendeeState, int seriesID,
			    boolean commit)
	throws SQLException, IllegalArgumentException
    {
	StringBuilder sb = new StringBuilder();
	boolean first = true;

	if (userID == -1) {
	    throw new IllegalArgumentException("no user ID");
	}
	if (instanceID == -1) {
	    throw new IllegalArgumentException("no event-instance ID");
	}
	if (attendeeState != null) {
	    sb.append("attendeeState = ?");
	    first = false;
	}
	if (seriesID != -1) {
	    if (first == false) sb.append(", ");
	    sb.append("seriesID = ?");
	    first = false;
	}
	if (first) return;	// nothing to do

	String s = getSQLProperty("setAttendeeData");
	s = String.format(s, sb.toString());
	try {
	    if (commit) conn.setAutoCommit(false);
	    try (PreparedStatement ps = conn.prepareStatement(s)) {
		int ind = 1;
		if (attendeeState != null) {
		    ps.setString(ind++, attendeeState);
		}
		if (seriesID != -1) {
		    ps.setInt(ind++, seriesID);
		}
		ps.setInt(ind++, userID);
		ps.setInt(ind++, instanceID);
		ps.executeUpdate();
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		System.err.println("Rolling back setAttendee");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    static class CalData {
	int userID;
	int ownerID;
	int locationID;
	int instanceID;
	String uid;
	String summary;
	String description;
	String preEventType;
	int preEventOffset;
	java.sql.Date startDate;
	java.sql.Time startTime;
	java.sql.Date  endDate;
	java.sql.Time endTime;
	int emailSeqno;
	int phoneSeqno;
	String location;
	boolean attendingPreEvent = false;
	java.sql.Timestamp modtimeO;
	java.sql.Timestamp modtimeL;
	java.sql.Timestamp modtimeE;
	java.sql.Timestamp modtimeI;
	java.sql.Timestamp modtimeA;
	java.sql.Timestamp modtimeF;
	java.sql.Timestamp modtimeS;
	java.sql.Timestamp createTime;
	java.sql.Timestamp lastEmailTime;
	java.sql.Timestamp lastPhoneTime;
// computed fields
	LocalDateTime fatime = null;
	LocalDateTime satime = null;
	boolean firstAlarmForEmail = false;
	boolean firstAlarmForPhone = false;
	boolean secondAlarmForEmail = false;
	boolean secondAlarmForPhone = false;
    }

    static final int SECOND_PER_DAY = 3600*24;

    /**
     * Calendar data.
     * This class is used to collect calendars and
     * associated data.  Each instance of this class is
     * associated with a single user ID and a boolean value
     * indicating whether or not the calendars are for use with
     * email or text messages.
     */
    public static class  UserCalendars {
	int userID;
	boolean forEmail;
	TemplateProcessor.KeyMap kmap = null;
	Vector<byte[]> calendars = new Vector<>();
	// Vector<CalData> dvector = new Vector<>(32);
    }

    /**
     * Count the number of rows in the attendee with an instance id
     * that matches an event instance with a specified location and
     * event ID, where the corresponding event has a specified owner.
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @param instanceID an instance ID; -1 for any instance
     * @return the number of rows in the attendee with an instance id
     *         that matches and event instance with a specified
     *         location ane event ID, where the corresponding event
     *         has a specified owner
     */
    public int getInstanceCount(Connection conn, int ownerID, int eventID,
				int locationID, int instanceID)
	throws SQLException
    {
	boolean first = true;
	boolean useO = false;
	boolean useE = false;
	boolean useL = false;
	boolean useI = false;

	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append("otbl.ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (eventID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("etbl.eventID = ?");
	    first = false;
	    useE = true;
	}

	if (locationID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("itbl.locationID = ?");
	    first = false;
	    useL = true;
	}

	if (instanceID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("itbl.instanceID = ?");
	    first = false;
	    useI = true;
	}

	if (first == false) sb.append(" AND ");
	
	String q = String.format(getSQLProperty("getInstanceCount"),
				 sb.toString());
	Vector<UserCalendars> vector = new Vector<UserCalendars>(32);
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useO) {
		ps.setInt(ind++, ownerID);
	    }
	    if (useE) {
		ps.setInt(ind++, eventID);
	    }
	    if (useL) {
		ps.setInt(ind++, locationID);
	    }
	    if (useI) {
		ps.setInt(ind++, instanceID);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    return rs.getInt(1);
		} else {
		    return 0;
		}
	    }
	}
    }

    /**
     * Get a list of UserCalendar objects, each associated with a
     * distinct user ID for users not attending a specified set of
     * event instances. The userCalendar objects returned do not
     * contain calendars.
     * @param conn the database connection
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param locationID an location ID; -1 for any location
     * @param instanceID an instance ID; -1 for any instance
     * @param forEmail true for calendars associated with email;
     *        false for text messages
     * @return the UserCalendar objects
     */
    public Vector<UserCalendars>
	getNonAttendees(Connection conn, int ownerID, int eventID,
			int locationID, int instanceID, boolean forEmail)
	throws SQLException
    {
	Vector<UserCalendars> vector = new Vector<UserCalendars>(32);
	if (getInstanceCount(conn, ownerID, eventID,
			     locationID, instanceID) == 0) {
	    return vector;
	}

	boolean first = true;
	boolean useO = false;
	boolean useE = false;
	boolean useL = false;
	boolean useI = false;

	StringBuilder sb = new StringBuilder();
	if (ownerID != -1) {
	    sb.append("otbl.ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (eventID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("etbl.eventID = ?");
	    first = false;
	    useE = true;
	}

	if (locationID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("itbl.locationID = ?");
	    first = false;
	    useL = true;
	}

	if (instanceID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("itbl.instanceID = ?");
	    first = false;
	    useI = true;
	}

	if (first == false) sb.append(" AND ");
	
	String q = String.format(getSQLProperty("nonAttendees"),
				 sb.toString());
	System.out.println("q = " + q);
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useO) {
		ps.setInt(ind++, ownerID);
	    }
	    if (useE) {
		ps.setInt(ind++, eventID);
	    }
	    if (useL) {
		ps.setInt(ind++, locationID);
	    }
	    if (useI) {
		ps.setInt(ind++, instanceID);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    UserCalendars data = new UserCalendars();
		    data.userID = rs.getInt(1);
		    data.forEmail = forEmail;
		    vector.add(data);
		}
	    }
	    for (UserCalendars data: vector) {
		data.kmap = getUserKeyMap(conn, data.userID);
	    }
	}
	return vector;
    }

    /**
     * Get a list of UserCalendar objects, each associated with a
     * distinct user ID for users attending a specified set of
     * event instances. The corresponding calendars are included
     * inthe UserCalendar objects.
     * @param conn the database connection
     * @param userID the userID; -1 for any user
     * @param ownerID an owner ID; -1 for any owner
     * @param eventID an event; -1 for any event
     * @param calendarForEmail true for calendars associated with email;
     *        false for text messages
     * @return the UserCalendar objects
     */
    public Vector<UserCalendars>
	getCalendars(Connection conn, int userID, int ownerID, int eventID,
		     boolean calendarForEmail)
	throws SQLException
    {
	boolean first = true;
	boolean useU = false;
	boolean useO = false;
	boolean useE = false;

	StringBuilder sb = new StringBuilder();
	if (userID != -1) {
	    sb.append("utbl.userID = ?");
	    first = false;
	    useU = true;
	}
	if (ownerID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("otbl.ownerID = ?");
	    first = false;
	    useO = true;
	}
	if (eventID != -1) {
	    if (first == false) sb.append(" AND ");
	    sb.append("etbl.eventID = ?");
	    first = false;
	    useE = true;
	}
	if (first == false) sb.append(" AND ");
	
	String q = String.format(getSQLProperty("calendarData"), sb.toString());
	Vector<UserCalendars> vector = new Vector<UserCalendars>(32);
	Vector<CalData> dvector = new Vector<>(32);
	MessageDigest md = null;
	try {
	    md = MessageDigest.getInstance("SHA-256");
	} catch (NoSuchAlgorithmException nsae) {
	    throw new UnexpectedExceptionError(nsae);
	}
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    int ind = 1;
	    if (useU) {
		ps.setInt(ind++, userID);
	    }
	    if (useO) {
		ps.setInt(ind++, ownerID);
	    }
	    if (useE) {
		ps.setInt(ind++, eventID);
	    }
	    try (ResultSet rs = ps.executeQuery()) {
		while (rs.next()) {
		    CalData data = new CalData();
		    data.userID = rs.getInt(1);
		    data.ownerID = rs.getInt(2);
		    data.locationID = rs.getInt(3);
		    data.instanceID = rs.getInt(4);
		    md.reset();
		    md.update(String.format("%d-%d-%d-", data.userID,
					    data.ownerID, data.instanceID)
			      .getBytes(UTF8));
		    md.update(rs.getString(5).getBytes(UTF8));
		    data.summary = rs.getString(6);
		    data.uid = new String
			(Base64.getEncoder().encode(md.digest()), UTF8)
			+ "@" + rs.getString(7);
		    data.description = rs.getString(8);
		    data.preEventType = rs.getString(9);
		    data.preEventOffset = rs.getInt(10);
		    if (data.preEventOffset < 0) data.preEventOffset = 0;
		    data.startDate = rs.getDate(11);
		    data.startTime = rs.getTime(12);
		    data.endDate = rs.getDate(13);
		    data.endTime = rs.getTime(14);
		    data.emailSeqno = rs.getInt(15);
		    data.phoneSeqno = rs.getInt(16);
		    data.location = rs.getString(17);
		    Boolean ape = rs.getBoolean(18);
		    data.attendingPreEvent = (ape == null)? false: ape;
		    data.modtimeO = rs.getTimestamp(19);
		    data.modtimeL = rs.getTimestamp(20);
		    data.modtimeE = rs.getTimestamp(21);
		    data.modtimeI = rs.getTimestamp(22);
		    data.modtimeA = rs.getTimestamp(23);
		    data.modtimeF = null;
		    data.modtimeS = null;
		    data.createTime = rs.getTimestamp(24);
		    data.lastEmailTime = rs.getTimestamp(25);
		    data.lastPhoneTime = rs.getTimestamp(26);
		    dvector.add(data);
		}
	    }
	}
	try (PreparedStatement ps = conn.prepareStatement
		 (getSQLProperty("calFirstAlarm"))) {
	    for (CalData data: dvector) {
		ArrayList<LocalDateTime> fetimes = new ArrayList<>();
		ArrayList<LocalDateTime> fatimes = new ArrayList<>();
		ArrayList<Boolean> forEmail = new ArrayList<>();
		ArrayList<Boolean> forPhone = new ArrayList<>();

		ps.setInt(1, data.userID);
		ps.setInt(2, data.ownerID);
		ps.setInt(3, data.locationID);
		LocalDate date = data.startDate.toLocalDate();
		DayOfWeek dow = date.getDayOfWeek();
		boolean weekday = !(dow == DayOfWeek.SATURDAY ||
				    dow == DayOfWeek.SUNDAY);
		ps.setBoolean(4, weekday);
		try (ResultSet rs = ps.executeQuery()) {
		    while (rs.next()) {
			LocalDateTime edt = rs.getTime(1).toLocalTime()
			    .atDate(date);
			LocalDateTime adt = rs.getTime(2).toLocalTime()
			    .atDate(date);
			if (adt.isAfter(edt)) {
			    adt = adt.minusDays(1L);
			}
			fetimes.add(edt);
			fatimes.add(adt);
			forEmail.add(rs.getBoolean(3));
			forPhone.add(rs.getBoolean(4));
			java.sql.Timestamp mt = rs.getTimestamp(5);
			if (data.modtimeF == null) {
			    data.modtimeF = mt;
			} else if (mt.after(data.modtimeF)) {
			    data.modtimeF = mt;
			}
		    }
		}
		if (data.startDate != null && data.startTime != null) {
		    LocalDateTime sdt =
			data.startTime.toLocalTime().atDate
			(data.startDate.toLocalDate());
		    if (data.attendingPreEvent) {
			sdt = sdt.minusMinutes(data.preEventOffset);
		    }
		    int len = fetimes.size();
		    LocalDateTime fatime = null;
		    int ind1 = -1;
		    int ind2 = -1;
		    boolean forE = false;
		    boolean forC = false;
		    if (len == 1) {
			fatime = sdt.minusMinutes
			    (ChronoUnit.MINUTES
			     .between(fatimes.get(0), fetimes.get(0)));
			ind1 = 0; ind2 = 0;
		    } else if (len > 0) {
			if (sdt.compareTo(fetimes.get(0)) <= 0) {
			    ind1 = 0; ind2 = 0;
			    fatime = sdt.minusMinutes
				(ChronoUnit.MINUTES
				 .between(fatimes.get(0), fetimes.get(0)));

			} else if (sdt.compareTo(fetimes.get(len-1))
				   >= 0) {
			    ind1 = len-1; ind2 = ind1;
			    fatime = sdt.minusMinutes
				(ChronoUnit.MINUTES.between
				 (fatimes.get(ind1), fetimes.get(ind1)));
			    forE = forEmail.get(ind1);
			    forC = forPhone.get(ind1);
			} else {
			    for (int i = 0; i < len; i++) {
				if (sdt.compareTo(fetimes.get(i))== 0) {
				    ind1 = i; ind2 = i;
				    fatime = fatimes.get(ind1);
				    forE = forEmail.get(ind1);
				    forC = forPhone.get(ind1);
				    break;
				    } else if (sdt.compareTo(fetimes.get(i))
					       > 0) {
				    ind1 = i; ind2 = i+1;
				    int interval1 =
					(int)ChronoUnit.MINUTES.between
					(fetimes.get(ind1), fetimes.get(ind2));
				    int interval2 =
					(int)ChronoUnit.MINUTES.between
					(fatimes.get(ind1), fatimes.get(ind2));
				    double u =
					ChronoUnit.MINUTES.between
					(fetimes.get(ind1), sdt)
					/((double)interval1);
				    fatime = fatimes.get(ind1).plusMinutes
					((int)Math.round(u*interval2));
				    forE = forEmail.get(ind1)
					|| forEmail.get(ind2);
				    forC = forPhone.get(ind1)
					|| forPhone.get(ind2);
				    break;
				}
			    }
			}
		    }
		    if (ind1 != -1) {
			data.fatime = fatime;
			data.firstAlarmForEmail = forE;
			data.firstAlarmForPhone = forC;
		    }
		}
		    
	    }
	}
	try (PreparedStatement ps = conn.prepareStatement
		 (getSQLProperty("calSecondAlarm"))) {
	    for (CalData data: dvector) {
		ps.setInt(1, data.userID);
		ps.setInt(2, data.ownerID);
		ps.setInt(3, data.locationID);
		LocalDateTime sdt =
		    data.startTime.toLocalTime()
		    .atDate(data.startDate.toLocalDate());
		if (data.attendingPreEvent) {
		    sdt = sdt.minusMinutes(data.preEventOffset);
		}
		try (ResultSet rs = ps.executeQuery()) {
		    if (rs.next()) {
			int offset = rs.getInt(1);
			data.secondAlarmForEmail = rs.getBoolean(2);
			data.secondAlarmForPhone = rs.getBoolean(3);
			data.satime =  sdt.minusMinutes(offset);
			java.sql.Timestamp mt = rs.getTimestamp(4);
			if (data.modtimeS == null) {
			    data.modtimeS = mt;
			} else if (mt.after(data.modtimeS)) {
			    data.modtimeS = mt;
			}
		    }
		}
	    }
	}
	String qemail = String.format(getSQLProperty("setAttendeeData"),
				      "emailSeqno = ?");
	String qphone = String.format(getSQLProperty("setAttendeeData"),
				      "phoneSeqno = ?");
	boolean needCommit = false;
	PreparedStatement ps1 = null;
	PreparedStatement ps2 = null;
	PreparedStatement ps1a = null;
	PreparedStatement ps2a = null;
	try {
	    ps1 = conn.prepareStatement(qemail);
	    ps1a = conn.prepareStatement(getSQLProperty("getLastEmailTime"));
	    ps2 = conn.prepareStatement(qphone);
	    ps2a = conn.prepareStatement(getSQLProperty("getLastPhoneTime"));
	    for (CalData data: dvector) {
		if (false) {
		    System.out.format("userID = %d, ownerID = %d,"
				      + "locationID = %d\n",
				      data.userID, data.ownerID,
				      data.locationID);
		    System.out.println("uid = " + data.uid);
		    System.out.println("summary = " + data.summary);
		    System.out.println("description = "
				       + data.description);
		    System.out.println("preEventType = "
				       + data.preEventType);
		    System.out.println("preEventOffset = "
				       + data.preEventOffset);
		    System.out.println("attendingPreEvent = "
				       + data.attendingPreEvent);
		    System.out.println("startDate = " + data.startDate);
		    System.out.println("startTime = " + data.startTime);
		    System.out.println("endDate = " + data.endDate);
		    System.out.println("endTime = " + data.endTime);
		    System.out.println("first alarm = " + data.fatime);
		    System.out.println("second alarm = " + data.satime);
		    System.out.println("firstAlarmForEmail = "
				       + data.firstAlarmForEmail);
		    System.out.println("firstAlarmForPhone = "
				       + data.firstAlarmForPhone);
		    System.out.println("secondAlarmForEmail = "
				       + data.secondAlarmForEmail);
		    System.out.println("second = "
				       + data.secondAlarmForPhone);
		    System.out.println("modtimeO = " + data.modtimeO);
		    System.out.println("modtimeL = " + data.modtimeL);
		    System.out.println("modtimeE = " + data.modtimeE);
		    System.out.println("modtimeI = " + data.modtimeI);
		    System.out.println("modtimeA = " + data.modtimeA);
		    System.out.println("modtimeF = " + data.modtimeF);
		    System.out.println("modtimeS = " + data.modtimeS);
		    System.out.println("emailSeqno = " + data.emailSeqno);
		    System.out.println("phoneSeqno = " + data.phoneSeqno);
		    System.out.println("lastEmailTime = "
				       + data.lastEmailTime);
		    System.out.println("lastPhoneTime = "
				       + data.lastPhoneTime);
		}
		LocalDateTime modtimeO =
		    data.modtimeO.toLocalDateTime();
		LocalDateTime modtimeL =
		    data.modtimeL.toLocalDateTime();
		LocalDateTime modtimeE =
		    data.modtimeE.toLocalDateTime();
		LocalDateTime modtimeI =
		    data.modtimeI.toLocalDateTime();
		LocalDateTime modtimeA =
		    data.modtimeA.toLocalDateTime();
		LocalDateTime modtimeF = (data.modtimeF == null)? null:
		    data.modtimeF.toLocalDateTime();
		LocalDateTime modtimeS = (data.modtimeS == null)? null:
		    data.modtimeS.toLocalDateTime();

		LocalDateTime maxdt = modtimeO;
		if (maxdt.compareTo(modtimeL) < 0) maxdt = modtimeL;
		if (maxdt.compareTo(modtimeE) < 0) maxdt = modtimeE;
		if (maxdt.compareTo(modtimeI) < 0) maxdt = modtimeI;
		if (maxdt.compareTo(modtimeA) < 0) maxdt = modtimeA;
		if (modtimeF != null && maxdt.compareTo(modtimeF) < 0) {
		    maxdt = modtimeF;
		}
		if (modtimeS != null && maxdt.compareTo(modtimeS) < 0) {
		    maxdt = modtimeS;
		}
		LocalDateTime lastEmailTime =
		    (data.lastEmailTime == null)?
		    null: data.lastEmailTime.toLocalDateTime();
		LocalDateTime lastPhoneTime =
		    (data.lastPhoneTime == null)?
		    null: data.lastPhoneTime.toLocalDateTime();
		if (calendarForEmail) {
		    if (lastEmailTime == null ||
			lastEmailTime.compareTo(maxdt) < 0) {
			needCommit = true;
			data.emailSeqno++;
			ps1.setInt(1, data.emailSeqno);
			ps1.setInt(2, data.userID);
			ps1.setInt(3, data.instanceID);
			ps1.executeUpdate();
			ps1a.setInt(1, data.userID);
			ps1a.setInt(2, data.instanceID);
			try (ResultSet rs = ps1a.executeQuery()) {
			    if (rs.next()) {
				data.lastEmailTime = rs.getTimestamp(1);
			    }
			}
		    }
		} else {
		    if (lastPhoneTime == null ||
			lastPhoneTime.compareTo(maxdt) < 0) {
			needCommit = true;
			data.phoneSeqno++;
			ps2.setInt(1, data.phoneSeqno);
			ps2.setInt(2, data.userID);
			ps2.setInt(3, data.instanceID);
			ps2.executeUpdate();
			ps2a.setInt(1, data.userID);
			ps2a.setInt(2, data.instanceID);
			try (ResultSet rs = ps2a.executeQuery()) {
			    if (rs.next()) {
				data.lastPhoneTime  = rs.getTimestamp(1);
			    }
			}
		    }
		}
	    }
	    if (needCommit) conn.commit();
	} catch (SQLException e) {
	    if (needCommit) {
		try {
		    System.err.println("Rolling back getCalendars");
		    conn.rollback();
		} catch (SQLException e3) {
		    System.err.println("SQL exception during rollback");
		}
		throw e;
	    }
	} finally {
	    if (ps1 != null) ps1.close();
	    if (ps2 != null) ps2.close();
	    conn.setAutoCommit(true);
	}
	int lastUserID = -1;
	int ocnt = 0;
	int ecnt = 0;
	UserCalendars output = new UserCalendars();
	TemplateProcessor.KeyMapList kmaplist1 = null;
	TemplateProcessor.KeyMapList kmaplist2 = null;
	TemplateProcessor.KeyMap kmap1 = null;
	for (CalData data: dvector) {
	    if (lastUserID == -1) {
		output.userID = data.userID;
		output.forEmail = calendarForEmail;
		lastUserID = data.userID;
		ownerID = -1;
		output.kmap = getUserKeyMap(conn, data.userID);
		kmaplist1 = new TemplateProcessor.KeyMapList();
		output.kmap.put("owners", kmaplist1);
	    } else if (output.userID != data.userID) {
		// ocnt cannot be 0.
		if (ocnt == 1) {
		    output.kmap.put("singleOwner", emptymap);
		} else {
		    output.kmap.put("multipleOwners", emptymap);
		    output.kmap.put("oli", "<li>");
		    output.kmap.put("ob", "*");
		    output.kmap.put("oindent", "    ");
		}
		vector.add(output);
		output = new UserCalendars();
		output.userID = data.userID;
		output.forEmail = calendarForEmail;
		lastUserID = data.userID;
		ownerID = -1;
		output.kmap = getUserKeyMap(conn, data.userID);
		kmaplist1 = new TemplateProcessor.KeyMapList();
		output.kmap.put("owners", kmaplist1);
		ocnt = 0;
	    }
	    if (ownerID != data.ownerID) {
		if (kmap1 != null) {
		    // ecnt = 0 cannot occur.
		    if (ecnt == 1) {
			kmap1.put("singleEvent", emptymap);
		    } else {
			kmap1.put("multipleEvents", emptymap);
		    }
		}
		kmap1 = new TemplateProcessor.KeyMap();
		kmap1.put("summary", data.summary.trim());
		kmaplist1.add(kmap1);
		kmaplist2 = new TemplateProcessor.KeyMapList();
		kmap1.put("calendars", kmaplist2);
		ownerID = data.ownerID;
		ecnt = 0;
		ocnt++;
	    }
	    TemplateProcessor.KeyMap kmap2 = new TemplateProcessor.KeyMap();
	    kmaplist2.add(kmap2);
	    ecnt++;
	    Instant createTime = data.createTime.toInstant();
	    ICalBuilder icb = new ICalBuilder();
	    int seqno = calendarForEmail? data.emailSeqno: data.phoneSeqno;
	    Instant msgTime = calendarForEmail?
		data.lastEmailTime.toInstant():
		data.lastPhoneTime.toInstant();
	    ICalBuilder.Event ev = new ICalBuilder.Event(data.uid, seqno,
							 createTime, msgTime);
	    kmap2.put("location", data.location.trim());
	    ev.setSummary(data.summary.trim());
	    ev.setLocation(data.location.trim());
	    String description = data.description.trim();
	    kmap2.put("description", description);
	    LocalDateTime sdt = null;
	    if (data.startDate != null && data.startTime != null) {
		 sdt = data.startTime.toLocalTime().atDate
		     (data.startDate.toLocalDate());
		 ev.setStartTime(sdt.atZone(zoneId));
		 kmap2.put("startDate", sdt.toLocalDate().format(df));
		 kmap2.put("startTime", sdt.toLocalTime().format(tf));
		 // kmap2.put("hasStartDateTime", emptymap);
		if (data.attendingPreEvent && data.preEventOffset > 0) {
		    LocalDateTime sdt1 = sdt.minusMinutes(data.preEventOffset);
		    String preEventType = data.preEventType.trim();
		    if (preEventType == null || preEventType.length() == 0) {
			preEventType = "pre-event activity";
		    }
		    String timeString = sdt1.toLocalTime().format(tf);
		    description = String.format("%s (%s at %s)",
						description.trim(),
						preEventType, timeString);
		    kmap2.put("preEvent", String.format("%s at %s",
							preEventType.trim(),
							timeString));
		    // kmap2.put("hasPreEvent", emptymap);

		} else {
		    // kmap2.put("noPreEvent", emptymap);
		}
	    } else {
		// kmap2.put("noStartDateTime", emptymap);
		// kmap2.put("noPreEvent", emptymap);
	    }
	    ev.setDescription(description);
	    if (data.endDate != null && data.endTime != null) {
		LocalDateTime edt =
		    data.endTime.toLocalTime().atDate
		    (data.endDate.toLocalDate());
		ev.setEndTime(edt.atZone(zoneId));
		kmap2.put("endDate", edt.toLocalDate().format(df));
		kmap2.put("endTime", edt.toLocalTime().format(tf));
		// kmap2.put("hasEndDateTime", emptymap);
	    } else {
		//kmap2.put("noEndDateTime", emptymap);
	    }
	    ev.setStatus(ICalBuilder.Status.CONFIRMED);
	    if (data.fatime != null) {
		if ((calendarForEmail && data.firstAlarmForEmail)
		    || (!calendarForEmail && data.firstAlarmForPhone)) {
		    ICalBuilder.AlarmType type = calendarForEmail?
			ICalBuilder.AlarmType.DISPLAY:
			ICalBuilder.AlarmType.AUDIO;
		    int offset = -(int)
			ChronoUnit.MINUTES.between(data.fatime, sdt);
		    new ICalBuilder.Alarm(ev, offset, type, true);
		}
	    }
	    if (data.satime != null) {
		if ((calendarForEmail && data.secondAlarmForEmail)
		    || (!calendarForEmail && data.secondAlarmForPhone)) {
		    ICalBuilder.AlarmType type = calendarForEmail?
			ICalBuilder.AlarmType.DISPLAY:
			ICalBuilder.AlarmType.AUDIO;

		    int offset = -(int)
			ChronoUnit.MINUTES.between(data.satime, sdt);
		    new ICalBuilder.Alarm(ev, offset, type, true);
		}
	    }
	    icb.add(ev);
	    icb.setMethod(ICalBuilder.ITIPMethod.PUBLISH);
	    output.calendars.add(icb.toByteArray());
	}
	if (lastUserID != -1) {
	    if (kmap1 != null) {
		// ecnt = 0 cannot occur.
		if (ecnt == 1) {
		    kmap1.put("singleEvent", emptymap);
		} else {
		    kmap1.put("multipleEvents", emptymap);
		}
	    }
	    if (ocnt == 1) {
		output.kmap.put("singleOwner", emptymap);
	    } else {
		output.kmap.put("multipleOwners", emptymap);
		output.kmap.put("oli", "<li>");
		output.kmap.put("ob", "*");
		output.kmap.put("oindent", "    ");
		output.kmap.put("sp", " ");
	    }
	    vector.add(output);
	}
	for (UserCalendars ucals: vector) {
	    for (TemplateProcessor.KeyMap kmp1:
		     (TemplateProcessor.KeyMapList)(ucals.kmap.get("owners"))) {
		TemplateProcessor.KeyMapList list
		    = (TemplateProcessor.KeyMapList)(kmp1.get("calendars"));
		if (list.size() > 1) {
		    for (TemplateProcessor.KeyMap kmp2: list) {
			kmp2.put("eli", "<li>");
			if (output.kmap.get("multipleOwners") == null) {
			    kmp2.put("eb", "*");
			    kmp2.put("eindent", "    ");
			    kmp2.put("sp", " ");
			} else {
			    kmp2.put("eb", "-");
			    kmp2.put("eindent", "        ");
			    kmp2.put("sp", " ");
			}
		    }
		}
	    }
	}
	return vector;
    }

    /**
     * Apply a series to a user
     * @param conn the database connection
     * @param userID the userID
     * @param seriesID the series ID
     */
    public void applySeries(Connection conn, int userID, int seriesID)
	throws SQLException, IllegalArgumentException
    {
	applySeries(conn, userID, seriesID, true);
    }
    /**
     * Apply a series to a user, indicating if database changes should be
     * committed.
     * @param conn the database connection
     * @param userID the userID
     * @param seriesID the series ID
     * @param commit true if the new changes should be committed; false
     *        otherwise
     */
    public void applySeries(Connection conn, int userID, int seriesID,
			    boolean commit)
	throws SQLException, IllegalArgumentException
    {
	if (userID == -1) {
	    throw new IllegalArgumentException("no userID");
	}
	if (seriesID == -1) {
	    throw new IllegalArgumentException("no seriesID");
	}
	/*
	System.out.println("userID = " + userID + ", seriedID = " + seriesID);
	*/
	String statements[] = {getSQLProperty("applySeries1"),
			       getSQLProperty("applySeries2"),
			       getSQLProperty("applySeries3")};
	try {
	    if (commit) conn.setAutoCommit(false);
	    for (String s: statements) {
		try (PreparedStatement ps = conn.prepareStatement(s)) {
		    ps.setInt(1, userID);
		    ps.setInt(2, seriesID);
		    ps.executeUpdate();
		}
	    }
	    if (commit) conn.commit();
	} catch (SQLException e) {
	    try {
		// System.out.println(" s = " + s);
		System.err.println("Rolling back applySeries");
		if (commit) conn.rollback();
	    } catch (SQLException e3) {
		System.err.println("SQL exception during rollback");
	    }
	    throw e;
  
	} finally {
	    if (commit) conn.setAutoCommit(true);
	}
    }

    /**
     * User status.
     * Provided as a type-safe alternative to string values in the
     * databasse.
     */
    public static enum UserStatus {
	/**
	 * The user is active.
	 */
	ACTIVE,
	/**
	 * The user is not active.
	 */
	NOTACTIVE,
	/**
	 * The user has been cancelled.
	 * This option is provided so that the user ID
	 * can be reused. Alternatively a clean-up process
	 * can delete these entries from the user table.
	 */
	CANCELLED
    }

    /**
     * Calendar status.
     * Provided as a type-safe alternative to string values in the
     * database.  The values match those provided in RFC 5545.
     */
    public static enum CalendarStatus {
	/**
	 * An appointment status is 'tentative'.
	 */
	TENTATIVE,
	/**
	 * An appointment status is 'confirmed'.
	 */
	CONFIRMED,
	/**
	 * An appointment status is 'cancelled'.
	 */
	CANCELLED
    }

    /**
     * Attendee state.
     * Provided as a type-safe alternative to string values in the
     * databasse.
     */
    public static enum AttendeeState {
	/**
	 * An entry in the attendee table is an active entry.
	 */
	ACTIVE,
	/**
	 * An entry in the attendee table is in the process of being
	 * cancelled.
	 */
	CANCELLING,
	/**
	 * An entry in the attendee table has ben cancelled. It may
	 * be subsequently deleted.
	 */
	CANCELLED
    }

    /**
     * Table type.
     */
    public static enum Table {
	/**
	 * This constant indicates the carrier table.
	 */
	CARRIER,
	/**
	 * This constant indicates the carrier-map table.
	 */
	CARRIER_MAP,
	/**
	 * This constant indicates the user table.
	 */
	USER,
	/**
	 * This constant indicates the owner table.
	 */
	OWNER,
	/**
	 * This constant indicates the pre-event-default table.
	 */
	PRE_EVENT_DEFAULT,
	/**
	 * This constant indicates the location table.
	 */
	LOCATION,
	/**
	 * This constant indicates the first-alarm table.
	 */
	FIRST_ALARM,
	/**
	 * This constant indicates the second-alarm table.
	 */
	SECOND_ALARM,
	/**
	 * This constant indicates the event table.
	 */
	EVENT,
	/**
	 * This constant indicates the event-instance table.
	 */
	INSTANCE,
	/**
	 * This constant indicates the series table.
	 */
	SERIES,
	/**
	 * This constant indicates the series-instance table.
	 */
	SERIES_INSTANCE,
	/**
	 * This constant indicates the attendee table.
	 */
	ATTENDEE
    }

    /**
     * Base class for labeled IDs
     * This class represents a binding between an integer ID and
     * a string labeling that ID.
     */
    public static class LabeledID {
	int id;
	String label;
	// Table type;

	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public LabeledID(int id, String label/*, Table type*/) {
	    this.id = id;
	    this.label = label.trim();
	    // this.type = type;
	}

	@Override
	public boolean equals(Object obj) {
	    if (obj instanceof LabeledID && getClass().equals(obj.getClass())) {
		LabeledID other = (LabeledID) obj;
		return id == other.id;
	    } else {
		return false;
	    }
	}


	/**
	 * Get a string representation for this object
	 * showing only the label.
	 * @return the label
	 */
	@Override
	public String toString() {
	    return label;
	}


	/**
	 * Get a string representation for thi object showing both
	 * a label and an integer ID.
	 * @return the string representation
	 */
	public String toFullString() {
	    return String.format("%d (%s)", id, label);
	}

	/**
	 * Get the ID for this object.
	 * @return the ID
	 */
	public int getID() {return id;}

	/**
	 * Get the label for this object.
	 * @return the label
	 */
	public String getLabel() {return label;}

	// public Table getType() {return type;}
    }

    /**
     * LabeledID class representing a carrier.
     */
    public static class CarrierLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public CarrierLabeledID(int id, String label) {
	    super(id, label);
	}
    }


    /**
     * LabeledID class representing a user.
     */
    public static class UserLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public UserLabeledID(int id, String label) {
	    super(id, label);
	}
    }


    /**
     * LabeledID class representing an owner.
     */
    public static class OwnerLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public OwnerLabeledID(int id, String label) {
	    super(id, label);
	}
    }

    /**
     * LabeledID class representing a location.
     */
    public static class LocationLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public LocationLabeledID(int id, String label) {
	    super(id, label);
	}
    }

    /**
     * LabeledID class representing an event.
     */
    public static class EventLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public EventLabeledID(int id, String label) {
	    super(id, label);
	}
    }

    /**
     * LabeledID class representing an event instance.
     */
    public static class InstanceLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public InstanceLabeledID(int id, String label) {
	    super(id, label);
	}
    }


    /**
     * LabeledID class representing a series.
     */
    public static class SeriesLabeledID extends LabeledID {
	/**
	 * Constructor.
	 * @param id the ID
	 * @param label the label
	 */
	public SeriesLabeledID(int id, String label) {
	    super(id, label);
	}
    }


    static String[][] colHeadings = {
	{"carrierID"},				    // carrier [0]
	{"countryPrefix", "carrierID", "idomain"}, // carrierMap [1]
	{"userID",},				   // user [2]
	{"ownerID", "label", "summary", "idomain"}, // owner [3]
	{"userID", "ownerID", "attend"}, // preEventDefault [4]
	{"locationID"},			     // location [5]
	{"userID", "ownerID", "locationID", "eventTime", "weekday",
	 "alarmTime", "forEmail", "forPhone"}, // first alarm [6]
	{"userID", "ownerID", "locationID", "offset",
	 "forEmail", "forPhone"},   // second alarm [7]
	{"eventID"},		    // event [8]
	{"instanceID"},		    // event instance [9]
	{"seriesID"},		    // series [10]
	{"seriesID", "instanceID"}, // series instances [11]
	{"userID", "instanceID", "attendeeState", "attendingPreEvent",
	 "seriesID"},		// attendee [12]
    };

    static String[][] colHeadingsF = {
	{"carrierID", "carrierName"},		   // carrier [0]
	{"countryPrefix", "carrier", "idomain"}, // carrierMap [1]
	{"userID", "firstName", "lastName", "LNF", "title", "emailAddr", 
	 "countryPrefix", "cellNumber", "carrier", "status"}, // user [2]
	{"ownerID", "label", "summary", "idomain"}, // owner [3]
	{"user", "owner", "attend"}, // preEventDefault [4]
	{"locationID", "label", "locationName"}, // location [5]
	{"user", "owner", "location", "eventTime", "weekday", "alarmTime",
	 "forEmail", "forPhone"}, // first alarm [6]
	{"user", "owner", "location", "offset",
	 "forEmail", "forPhone"}, // second alarm [7]
	{"eventID", "owner", "label", "description"}, // event [8]
	{"instanceID", "event", "location",
	 "preEventType", "preEventOffset",
	 "startDate", "startTime", "endDate", "endTime",
	 "status"}, // event instance [9]
	{"seriesID", "owner", "label"}, // series [10]
	{"series", "instance"}, // series instances [11]
	{"user", "instance", "attendeeState", "attendingPreEvent",
	 "series"},		// attendee [12]
    };


    public static Vector<String> getHeadingVector(Table table, boolean full) {
	String[] headings = getHeading(table, full);
	Vector<String> result = new Vector<>(headings.length);
	for (String heading: headings) {
	    result.add(heading);
	}
	return result;
    }

    public static String[] getHeading(Table table, boolean full) {
	if (table == null) return null;
	return full? colHeadingsF[table.ordinal()]:
	    colHeadings[table.ordinal()];
    }



    static void printICals(Vector<byte[]> vector) {
	for (byte[] ical: vector) {
	    System.out.println(new String(ical, UTF8));
	}
    }

    static void printICals(PrintWriter out, Vector<byte[]> vector) {
	for (byte[] ical: vector) {
	    out.println(new String(ical, UTF8));
	}
	out.flush();
    }

    static void printICals(PrintStream out, Vector<byte[]> vector) {
	for (byte[] ical: vector) {
	    out.println(new String(ical, UTF8));
	}
	out.flush();
    }

    static void print(Vector<Vector<Object>> vector, int[] cols,
		      int heading, boolean full) {
	int max = 0;
	for (int i: cols) {
	    if (i < 0) {
		throw new IllegalArgumentException("column index out of range");
	    }
	    if (i > max) max = i;
	}
	for (Vector<Object> row: vector) {
	    if (max >= row.size()) {
		throw new IllegalArgumentException("column index out of range");
	    }
	}
	if (heading >= 0) {
	    String[] headers = full? colHeadingsF[heading]:
		colHeadings[heading];
	    boolean first = true;
	    for (int ind: cols) {
		String s = headers[ind];
		if (first) {
		    first = false;
		} else {
		    System.out.print(" | ");
		}
		System.out.print(s);
	    }
	    System.out.println();
	}
	
	for (Vector<Object> row: vector) {
	    boolean first = true;
	    for (int ind: cols) {
		if (first) {
		    first = false;
		} else {
		    System.out.print(" | ");
		}
		Object item = row.get(ind);
		if (item instanceof LabeledID) {
		    System.out.print(((LabeledID)item).toFullString());
		} else {
		    System.out.print(item);
		}
	    }
	    System.out.println();
	}
    }

    static void print(Vector<Vector<Object>> vector, boolean full) {
	print(vector, -1, full);
    }

    static void print(Vector<Vector<Object>> vector, Table table,
		      boolean full) {
	int heading = (table == null)? -1: table.ordinal();
	print(vector, heading, full);
    }
		      

    static void print(Vector<Vector<Object>> vector, int heading,
		      boolean full) {
	if (heading >= 0) {
	    String[] headers = full? colHeadingsF[heading]:
		colHeadings[heading];
	    boolean first = true;
	    for (String s: headers) {
		if (first) {
		    first = false;
		} else {
		    System.out.print(" | ");
		}
		System.out.print(s);
	    }
	    System.out.println();
	}
	for (Vector<Object> row: vector) {
	    boolean first = true;
	    for (Object entry: row) {
		if (first) {
		    first = false;
		} else {
		    System.out.print(" | ");
		}
		if (entry instanceof LabeledID) {
		    System.out.print(((LabeledID)entry).toFullString());
		} else {
		    System.out.print(entry);
		}
	    }
	    System.out.println();
	}
    }

    private static void hasArgTest(int ind, String[] argv) {
	if (ind >= argv.length) {
	    System.err.println("edbc: missing argument for "
			       + argv[ind-1]);
	    System.exit(1);
	}
    }

    static java.time.format.DateTimeFormatter tfmt1
	= java.time.format.DateTimeFormatter.ofPattern("kk:mm[:ss]");

    static java.time.format.DateTimeFormatter tfmt2
	= java.time.format.DateTimeFormatter.ofPattern("hh:mm[:ss]a");

    static java.time.format.DateTimeFormatter tfmt3
	= java.time.format.DateTimeFormatter.ofPattern("hh:mm[:ss] a");

    static java.sql.Time parseTime(String time)
	throws IllegalArgumentException
    {
	String tm = (time.matches("[0-9]:.*"))? "0"+time: time;
	try {
	    try {
		return Time.valueOf(LocalTime.parse(tm, tfmt1));
	    } catch (Exception e) {
		try {
		    return Time.valueOf(LocalTime.parse(tm, tfmt2));
		} catch (Exception ee) {
		    return Time.valueOf(LocalTime.parse(tm, tfmt3));
		}
	    }
	} catch (Exception e) {
	    throw new IllegalArgumentException("bad time format: " + time);
	}
    }

    /**
     * Copy a series of calendars to the window-system's clipboard.
     * @param calendars the calendars
     * @param headless true if ECDB is being run from the command line
     *        and will not open any windows, even if a window system is
     *        present
     */
    public static void copyToClipboard(Vector<byte[]> calendars,
				       boolean headless)
	throws IOException
    {
	Support.ICalTransferable t = new Support.ICalTransferable(calendars);
	Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
	clipboard.setContents(t, t);
	if (headless) {
	    Console console = System.console();
	    String request = "Press RETURN when operation is complete: ";
	    console.readLine(request);
	}
    }

    /**
     * Copy a series of calendars to a directory.
     * @param dir the directory
     * @param calendars the calendars
     */
    public static void saveToDirectory(File dir, Vector<byte[]> calendars)
	throws IOException
    {
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
	    // System.out.println("fname = " + fname);
	    File ff = new File(dir, fname);
	    InputStream in = new ByteArrayInputStream(calendar);
	    Path p = ff.toPath();
	    Files.copy(in, p);
	}
    }

    private String subject = null;
    private String mediaType = "text/html; charset=UTF-8";
    private URL templateURL = null;
    private String altMediaType = "text/plain; charset=UTF-8";;
    private URL altTemplateURL = null;
    private Boolean preflight = null;

    /**
     * Set the subject for a message (email or MMS via a gateway).
     * @param subject the subject line
     */
    public void setSubject(String subject) {this.subject = subject;}

    /**
     * Get the current subject line.
     * @return the subject line
     */
    public String getSubject() {return subject;}

    /**
     * Set the media type for a message (email or MMS via a gateway).
     * @param mediaType the media type (e.g., text/html)
     */
    public void setMediaType(String mediaType) {this.mediaType = mediaType;}

    /**
     * Get the media type for a message.
     * @return the media type (e.g., text/html)
     */
    public String getMediaType() {return mediaType;}

    /**
     * Set the alternative media type for a message (email or MMS via
     * a gateway).
     * @param mediaType the media type (e.g., text/plain)
     */
    public void setAltMediaType(String mediaType) {
	this.altMediaType = mediaType;
    }

    /**
     * Get the alternative media type for a message.
     * @return the media type (e.g., text/plain)
     */
    public String getAltMediaType() {return altMediaType;}


    /**
     * Set the URL for the message template.
     * @URL the url
     */
    public void setTemplateURL(URL templateURL) {
	this.templateURL = templateURL;
    }

    /**
     * Get the URL for the message template.
     * @return the url
     */
    public URL getTemplateURL() {return templateURL;}

    /**
     * Set the URL for the alternative message template.
     * @URL the url
     */
    public void setAltTemplateURL(URL templateURL) {
	this.altTemplateURL = templateURL;
    }

    /**
     * Get the URL for the alternative message template.
     * @return the url
     */
    public URL getAltTemplateURL() {return altTemplateURL;}

    /**
     * Get the preflight mode.
     * @return true if the messages should be previewed before being
     *         sent; false otherwise
     */
    public boolean getPreflight() {
	if (preflight == null) {
	    String pre = dbProperties.getProperty("preflight", "false");
	    if (pre.trim().equalsIgnoreCase("false")) {
		preflight = false;
	    } else if (pre.trim().equalsIgnoreCase("true")) {
		preflight = true;
	    } else {
		System.err.println("unrecognized preflight: " + pre);
		return false;
	    }
	}
	return preflight;
    }

    /**
     * Set the preflight mode.
     * @param value  true if the messages should be previewed before being
     *        sent; false otherwise
     */
    public void setPreflight(boolean value) {
	preflight = value;
    }

    /**
     * Send a calendar appointment or message via email, either to
     * an email address. For MMS, the recipient address is that for a
     * gateway.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param vector a vector of UserCalendar objects, each
     *        containing calendars and data needed to identify
     *        recipients and format messages
     * @param suppressCalendars true if calendars should not be attached
     *        to a message; false otherwise
     * @param frame a JFrame on which any dialog boxes should be centered;
     *        null if there are none
     * @param preflight true if the user should be shown a representation of
     *        the messages in a web browser (e.g., to verify that the
     *        messages are formated or created as desired); false otherwise
     */
    public static boolean sendViaEmail(ECDB ecdb, Connection conn,
				       Vector<UserCalendars> vector,
				       boolean suppressCalendars,
				       JFrame frame, boolean preflight)
	throws Exception, SQLException
    {
	String subject = ecdb.getSubject();
	String mediaType1 = ecdb.getMediaType();
	URL templateURL1 = ecdb.getTemplateURL();
	String altMediaType1 = ecdb.getAltMediaType();
	URL altTemplateURL1 = ecdb.getAltTemplateURL();
	
	Properties emailProperties = ecdb.getEmailProperties();
	String provider = emailProperties.getProperty("provider");
	SMTPAgent agent = SMTPAgent.newInstance(preflight? "dryrun": provider);
	if (agent != null) {
	    for (UserCalendars ucals: vector) {
		String mediaType = null;
		URL templateURL = null;
		String altMediaType = null;
		URL altTemplateURL = null;
		if (ucals.forEmail) {
		    if (templateURL1 == null &&  altTemplateURL1 == null) {
			mediaType = "text/html; charset=UTF-8";
			templateURL = ECDB.class.getResource("text.tpl");
			altMediaType = "text/plain; charset=UTF-8";
			altTemplateURL = ECDB.class.getResource("alttext.tpl");
		    } else {
			mediaType = mediaType1;
			templateURL = templateURL1;
			altMediaType = altMediaType1;
			altTemplateURL = altTemplateURL1;
		    }
		} else {
		    if (mediaType1 != null && templateURL1 != null &&
			mediaType1.toLowerCase().startsWith("text.plain")) {
			mediaType = mediaType1;
			templateURL = templateURL1;
		    } else if (altMediaType1 != null && altTemplateURL1 != null
			       && altMediaType1.toLowerCase()
			       .startsWith("text.plain")) {
			mediaType = altMediaType1;
			templateURL = altTemplateURL1;
		    }
		}
		emailProperties = ecdb.getEmailProperties();
		if (subject != null) {
		    emailProperties.put("subject", subject);
		}
		if (mediaType != null) {
		    emailProperties.put("textMediaType", mediaType);
		}
		if (altMediaType != null) {
		    emailProperties.put("altTextMediaType", altMediaType);
		}
		if (mediaType != null && templateURL != null) {
		    StringBuilder sb = new StringBuilder();
		    AppendableWriter w = new AppendableWriter(sb);
		    TemplateProcessor tp = new TemplateProcessor(ucals.kmap);
		    tp.processURL(templateURL, "UTF-8", w);
		    String txt = sb.toString().replaceAll("\r\n", "\n")
			.replaceAll("\n", "\r\n");
		    emailProperties.put("text", txt);
		}
		if (altMediaType != null && altTemplateURL != null) {
		    StringBuilder sb = new StringBuilder();
		    AppendableWriter w = new AppendableWriter(sb);
		    TemplateProcessor tp = new TemplateProcessor(ucals.kmap);
		    tp.processURL(altTemplateURL, "UTF-8", w);
		    String txt = sb.toString().replaceAll("\r\n", "\n")
			.replaceAll("\n", "\r\n");
		    emailProperties.put("altText", txt);
		}
		String to = ecdb.getFullEmailAddress(conn, ucals.userID,
						     ucals.forEmail);
		if (to != null && ucals.calendars.size() > 0) {
		    agent.send(emailProperties, to,
			       (suppressCalendars? null: ucals.calendars));
		}
	    }
	    return agent.complete(frame, !preflight);
	} else {
	    System.err.println("no SMTP agent");
	    return false;
	}
    }

    /**
     * Send a calendar appointment or message via email, either to
     * an email address. For MMS, the recipient address is that for a
     * gateway.
     * @param ecdb the instance of ECDB to use.
     * @param conn a database connection obtained from ecdb
     * @param out the print stream on which the message will be
     *        displayed
     * @param vector a vector of UserCalendar objects, each
     *        containing calendars and data needed to identify
     *        recipients and format messages
     * @param forEmail true if the message should be formatted as an
     *        email messages; false if it should be formatted as an MMS
     *        message
     * @param suppressCalendars true if calendars should not be attached
     *        to a message; false otherwise
     */
    public static void dryrunForSend(ECDB ecdb, Connection conn,
				     PrintWriter out,
				     Vector<UserCalendars> vector,
				     boolean forEmail,
				     boolean suppressCalendars)
	throws IOException, SQLException
    {
	String subject = ecdb.getSubject();
	String mediaType = ecdb.getMediaType();
	URL templateURL = ecdb.getTemplateURL();
	String altMediaType = ecdb.getAltMediaType();
	URL altTemplateURL = ecdb.getAltTemplateURL();
	if (forEmail && mediaType == null && templateURL == null
	    && altMediaType == null && altTemplateURL == null) {
	    mediaType = "text/html; charset=UTF-8";
	    templateURL = ECDB.class.getResource("text.tpl");
	    altMediaType = "text/plain; charset=UTF-8";
	    altTemplateURL = ECDB.class.getResource("alttext.tpl");
	}


	boolean first = true;
	for (UserCalendars ucal: vector) {
	    if (first) {
		first = false;
	    } else {
		System.out.println();
	    }
	    System.out.format("*** For user %d (%s) ***\n",
			      ucal.userID,
			      ecdb.getFullEmailAddress(conn, ucal.userID,
						       forEmail));
	    if (subject != null) {
		System.out.println("*** subject: " + subject);
	    }
	    if (mediaType != null) {
		System.out.println("*** text ***");
		TemplateProcessor tp = new
		    TemplateProcessor(ucal.kmap);
		/*
		  FileReader r = new
		  FileReader(template, UTF8);
		*/
		tp.processURL(templateURL, "UTF-8", System.out);
	    }
	    if (altMediaType != null) {
		System.out.println("*** alt text ***");
		TemplateProcessor tp = new
		    TemplateProcessor(ucal.kmap);
		/*
		  FileReader r = new
		  FileReader(altTemplate, UTF8);
		*/
		tp.processURL(altTemplateURL, "UTF-8", System.out);
	    }
	    if (suppressCalendars == false) {
		if (mediaType != null || altMediaType != null) {
		    System.out.println("*** Calendars ***");
		}
		printICals(ucal.calendars);
	    }
	}
    }

    /**
     * Main program for ECDB when used as an application instead of purely
     * as part of a class library.
     */
    public static void main(String argv[]) {
	int ind = 0;

	boolean config = false;
	boolean createDB = false;
	// boolean allowRoles = false;
	boolean createTables = false;
	boolean noSetupOptions = true;
	boolean noCommands = true;
	boolean full = false;
	boolean id = false;

	boolean addPhoneDomains = false;
	LinkedHashMap<String,String> pdmap = new LinkedHashMap<>();
	boolean addCarrier = false;
	boolean deleteCarrier = false;
	boolean listCarriers = false;
	boolean setCarrier = false;
	boolean listCarrierMap = false;
	boolean setCarrierMapping = false;
	boolean addUser = false;
	boolean setUser = false;
	boolean listUsers = false;
	boolean getCellEmail = false;
	boolean deleteUser = false;
	boolean addOwner = false;
	boolean setOwner = false;
	boolean listOwners = false;
	boolean deleteOwner = false;
	boolean addLocation = false;
	boolean setLocation = false;
	boolean listLocations = false;
	boolean deleteLocation = false;
	boolean addFirstAlarm = false;
	boolean listFirstAlarms = false;
	boolean setFirstAlarm = false;
	boolean deleteFirstAlarm = false;
	boolean deleteFirstAlarms = false;
	boolean addSecondAlarm = false;
	boolean listSecondAlarms = false;
	boolean setSecondAlarm = false;
	boolean deleteSecondAlarm = false;
	boolean deleteSecondAlarms = false;
	boolean addPreEventDefault = false;
	boolean listPreEventDefaults = false;
	boolean setPreEventDefault = false;
	boolean deletePreEventDefault = false;
	boolean deletePreEventDefaults = false;
	boolean addEvent = false;
	boolean setEvent = false;
	boolean listEvents = false;
	boolean deleteEvent = false;
	boolean addInstance = false;
	boolean setInstance = false;
	boolean listInstances = false;
	boolean deleteInstance = false;
	boolean deleteCanceled = false;
	boolean addSeries = false;
	boolean setSeries = false;
	boolean listSeries = false;
	boolean deleteSeries = false;
	boolean addSeriesInst = false;
	boolean listSeriesInst = false;
	boolean deleteSeriesInst = false;
	boolean addAttendee = false;
	boolean listAttendees = false;
	boolean deleteAttendee = false;
	boolean setAttendee = false;
	boolean applySeries = false;
	boolean getCalendars = false;
	boolean noHeaders = false;
	boolean suppressCalendars = false;

	int headingIndex = -1;

	int carrierID = -1;
	String carrier = null;
	String countryPrefix = null;
	String idomain = null;
	int userID = -1;
	String upattern = null;
	String firstName = null;
	String lastName  = null;
	Boolean lastNameFirst = null;
	String title = null;
	String emailAddr = null;
	String cellNumber = null;
	String status = null;
	int ownerID = -1;
	String opattern = null;
	String label = null;
	String summary = null;
	boolean peDefault = false;
	int locationID = -1;
	String lpattern = null;
	String location = null;
	Time eventTime = null;
	Time alarmTime = null;
	Boolean weekday = null;
	Boolean forPhone = null;
	Boolean forEmail = null;
	boolean preflight = false;
        int offset = -1; // integer
	int eventID = -1;
	String epattern = null;
	String description = null;
	int seriesID = -1;
	String spattern = null;
	int instanceID = -1;
	String preEventType = null;
	int preEventOffset = -1;
	java.sql.Date startDate = null;
	Time startTime = null;
	java.sql.Date endDate = null;
	Time endTime = null;
	Boolean attendingPreEvent = null;
	String attendeeState = null;
	int[] cols = null;
	File template = null;
	File altTemplate = null;
	URL templateURL = null;
	URL altTemplateURL = null;
	String mediaType = null;
	String altMediaType = null;
	String subject = null;

	boolean copyToClipboard = false;
	boolean sendViaEmail = false;
	boolean saveToDir = false;
	File dir = null;

	File f = null;

	while (ind < argv.length) {
	    if (argv[ind].equals("--")) {
		ind++; break;
	    } else if (argv[ind].equals("--file") || argv[ind].equals("-f")) {
		ind++; hasArgTest(ind, argv);
		f = new File(argv[ind]);
	    } else if (argv[ind].equals("-v")||argv[ind].equals("--verbose")) {
		full = true;
	    } else if (argv[ind].equals("--id") || argv[ind].equals("-i")) {
		id = true;
	    } else if (argv[ind].equals("--carrierID")) {
		ind++; hasArgTest(ind, argv);
		carrierID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--carrier")) {
		ind++; hasArgTest(ind, argv);
		carrier = argv[ind];
	    } else if (argv[ind].equals("--countryPrefix")) {
		ind++; hasArgTest(ind, argv);
		countryPrefix = argv[ind];
	    } else if (argv[ind].equals("--idomain")) {
		ind++; hasArgTest(ind, argv);
		idomain = argv[ind];
	    } else if (argv[ind].equals("--userID")) {
		ind++; hasArgTest(ind, argv);
		userID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--upattern")) {
		ind++; hasArgTest(ind, argv);
		upattern = argv[ind];
	    } else if (argv[ind].equals("--firstName")) {
		ind++; hasArgTest(ind, argv);
		firstName = argv[ind];
	    } else if (argv[ind].equals("--lastName")) {
		ind++; hasArgTest(ind, argv);
		lastName = argv[ind];
	    } else if (argv[ind].equals("--lastNameFirst")) {
		ind++; hasArgTest(ind, argv);
		lastNameFirst = Boolean.valueOf(argv[ind]);
	    } else if (argv[ind].equals("--title")) {
		ind++; hasArgTest(ind, argv);
		title = argv[ind];
	    } else if (argv[ind].equals("--emailAddr")) {
		ind++; hasArgTest(ind, argv);
		emailAddr = argv[ind];
	    } else if (argv[ind].equals("--cellNumber")) {
		ind++; hasArgTest(ind, argv);
		cellNumber = argv[ind];
	    } else if (argv[ind].equals("--status")) {
		ind++; hasArgTest(ind, argv);
		status = argv[ind].trim().toUpperCase();
	    } else if (argv[ind].equals("--ownerID")) {
		ind++; hasArgTest(ind, argv);
		ownerID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--opattern")) {
		ind++; hasArgTest(ind, argv);
		opattern = argv[ind];
	    } else if (argv[ind].equals("--label")) {
		ind++; hasArgTest(ind, argv);
		label = argv[ind];
	    } else if (argv[ind].equals("--summary")) {
		ind++; hasArgTest(ind, argv);
		summary = argv[ind];
	    } else if (argv[ind].equals("--peDefault")) {
		ind++; hasArgTest(ind, argv);
		peDefault = argv[ind].equalsIgnoreCase("true");
	    } else if (argv[ind].equals("--locationID")) {
		ind++; hasArgTest(ind, argv);
		locationID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--lpattern")) {
		ind++; hasArgTest(ind, argv);
		lpattern = argv[ind];
	    } else if (argv[ind].equals("--location")) {
		ind++; hasArgTest(ind, argv);
		location = argv[ind];
	    } else if (argv[ind].equals("--eventTime")) {
		ind++; hasArgTest(ind, argv);
		eventTime = parseTime(argv[ind]);
	    } else if (argv[ind].equals("--weekday")) {
		ind++; hasArgTest(ind, argv);
		weekday = argv[ind].equalsIgnoreCase("true");
	    } else if (argv[ind].equals("--alarmTime")) {
		ind++; hasArgTest(ind, argv);
		alarmTime = parseTime(argv[ind]);
	    } else if (argv[ind].equals("--forEmail")) {
		ind++;
		if (ind == argv.length || argv[ind].startsWith("--")) {
		    forEmail = true;
		    ind--;
		} else {
		    forEmail = (argv[ind].equalsIgnoreCase("true"));
		}
	    } else if (argv[ind].equals("--forPhone")) {
		ind++;
		if (ind == argv.length || argv[ind].startsWith("--")) {
		    ind--;
		    forPhone = true;
		} else {
		    forPhone =  (argv[ind].equalsIgnoreCase("true"));
		}
	    } else if (argv[ind].equals("--preflight")) {
		preflight = true;
	    } else if (argv[ind].equals("--offset")) {
		ind++; hasArgTest(ind, argv);
		offset = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--eventID")) {
		ind++; hasArgTest(ind, argv);
		eventID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--epattern")) {
		ind++; hasArgTest(ind, argv);
		epattern = argv[ind];
	    } else if (argv[ind].equals("--description")) {
		ind++; hasArgTest(ind, argv);
		description = argv[ind];
	    } else if (argv[ind].equals("--instanceID")) {
		ind++; hasArgTest(ind, argv);
		instanceID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--preEventType")) {
		ind++; hasArgTest(ind, argv);
		preEventType = argv[ind];
	    } else if (argv[ind].equals("--preEventOffset")) {
		ind++; hasArgTest(ind, argv);
		preEventOffset = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--startDate")) {
		ind++; hasArgTest(ind, argv);
		startDate = java.sql.Date.valueOf(argv[ind]);
	    } else if (argv[ind].equals("--startTime")) {
		ind++; hasArgTest(ind, argv);
		startTime = parseTime(argv[ind]);
	    } else if (argv[ind].equals("--endDate")) {
		ind++; hasArgTest(ind, argv);
		endDate = java.sql.Date.valueOf(argv[ind]);
	    } else if (argv[ind].equals("--endTime")) {
		ind++; hasArgTest(ind, argv);
		endTime = parseTime(argv[ind]);
	    } else if (argv[ind].equals("--attendingPreEvent")) {
		ind++; hasArgTest(ind, argv);
		attendingPreEvent = argv[ind].equalsIgnoreCase("true");
	    } else if (argv[ind].equals("--seriesID")) {
		ind++; hasArgTest(ind, argv);
		seriesID = Integer.parseInt(argv[ind]);
	    } else if (argv[ind].equals("--spattern")) {
		ind++; hasArgTest(ind, argv);
		spattern = argv[ind];
	    } else if (argv[ind].equals("--cols")) {
		ind++; hasArgTest(ind, argv);
		String[] tmp = argv[ind].trim().split("\\s*,\\s*");
		cols = new int[tmp.length];
		full = true;
		for (int i = 0; i < cols.length; i++) {
		    try {
			cols[i] = Integer.parseUnsignedInt(tmp[i]);
		    } catch (NumberFormatException nfe) {
			System.err.println("ecdb: illegal --cols argument "
					   + "\"" + tmp[i] + "\"");
			System.exit(1);
		    }
		}
	    } else if (argv[ind].equals("--noCalendars")) {
		suppressCalendars = true;
	    } else if (argv[ind].equals("--noHeaders")) {
		noHeaders = true;
	    } else if (argv[ind].equals("--attendeeState")) {
		ind++; hasArgTest(ind, argv);
		attendeeState = argv[ind];
	    } else if (argv[ind].equals("--subject")) {
		ind++; hasArgTest(ind, argv);
		subject = argv[ind];
	    } else if (argv[ind].equals("--template")) {
		ind++; hasArgTest(ind, argv);
		template = new File(argv[ind]);
	    } else if (argv[ind].equals("--mediaType")) {
		ind++; hasArgTest(ind, argv);
		mediaType = argv[ind];
	    } else if (argv[ind].equals("--altTemplate")) {
		ind++; hasArgTest(ind, argv);
		altTemplate = new File(argv[ind]);
	    } else if (argv[ind].equals("--altMediaType")) {
		ind++; hasArgTest(ind, argv);
		altMediaType = argv[ind];
	    } else if (argv[ind].equals("--configure")) {
		config = true;
		noSetupOptions = false;
	    } else if (argv[ind].equals("--createDB")) {
		createDB = true;
		noSetupOptions = false;
	    } else if (argv[ind].equals("--createTables")) {
		createTables = true;
		noSetupOptions = false;
	    } else if (argv[ind].equals("--create")) {
		createDB = true;
		// allowRoles = true;
		createTables = true;
		noSetupOptions = false;
	    } else if (argv[ind].equals("--createWithGUI")) {
		// Not documented: used by ECDB.desktop
		Support.InitData initData = null;
		try {
		    initData = Support.getInitData();
		} catch (Exception e) {
		    System.err.println("ECDB: " + e.getMessage());
		    System.exit(1);
		}
		if (initData == null) {
		    System.out.println("ECDB: user canceled initialization");
		    System.exit(0);
		}
		f = initData.f;
		createDB = initData.createDB;
		createTables = initData.createTables;
		noSetupOptions = false;
	    } else if (argv[ind].equals("--addCarrier")) {
		addCarrier = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listCarriers")) {
		listCarriers = true;
		noCommands = false;
		headingIndex = 0;
	    } else if (argv[ind].equals("--deleteCarrier")) {
		deleteCarrier = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setCarrier")) {
		setCarrier = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listCarrierMap")) {
		listCarrierMap = true;
		noCommands = false;
		headingIndex = 1;
	    } else if (argv[ind].equals("--setCarrierMapping")) {
		setCarrierMapping = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addUser")) {
		addUser = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listUsers")) {
		listUsers = true;
		noCommands = false;
		headingIndex = 2;
	    } else if (argv[ind].equals("--getCellEmail")) {
		getCellEmail = true;
		noCommands = false;
	    } else if (argv[ind].equals("--deleteUser")) {
		deleteUser = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setUser")) {
		setUser = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addOwner")) {
		addOwner = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listOwners")) {
		listOwners = true;
		noCommands = false;
		headingIndex = 3;
	    } else if (argv[ind].equals("--deleteOwner")) {
		deleteOwner = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setOwner")) {
		setOwner = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addLocation")) {
		addLocation = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listLocations")) {
		listLocations = true;
		headingIndex =   5;
		noCommands = false;
	    } else if (argv[ind].equals("--deleteLocation")) {
		deleteLocation = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setLocation")) {
		setLocation = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addFirstAlarm")) {
		addFirstAlarm = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listFirstAlarms")) {
		listFirstAlarms = true;
		noCommands = false;
		headingIndex= 6;
	    } else if (argv[ind].equals("--deleteFirstAlarm")) {
		noCommands = false;
		deleteFirstAlarm = true;
	    } else if (argv[ind].equals("--deleteFirstAlarms")) {
		noCommands = false;
		deleteFirstAlarms = true;
	    } else if (argv[ind].equals("--setFirstAlarm")) {
		noCommands = false;
		setFirstAlarm = true;
	    } else if (argv[ind].equals("--addSecondAlarm")) {
		addSecondAlarm = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listSecondAlarms")) {
		listSecondAlarms = true;
		noCommands = false;
		headingIndex = 7;
	    } else if (argv[ind].equals("--deleteSecondAlarm")) {
		deleteSecondAlarm = true;
		noCommands = false;
	    } else if (argv[ind].equals("--deleteSecondAlarms")) {
		deleteSecondAlarms = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setSecondAlarm")) {
		setSecondAlarm = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addPreEventDefault")) {
		addPreEventDefault = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listPreEventDefaults")) {
		listPreEventDefaults = true;
		headingIndex = 4;
		noCommands = false;
	    } else if (argv[ind].equals("--deletePreEventDefault")) {
		deletePreEventDefault = true;
		noCommands = false;
	    } else if (argv[ind].equals("--deletePreEventDefaults")) {
		deletePreEventDefaults = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setPreEventDefault")) {
		setPreEventDefault = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addEvent")) {
		addEvent = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listEvents")) {
		listEvents = true;
		headingIndex = 8;
		noCommands = false;
	    } else if (argv[ind].equals("--deleteEvent")) {
		deleteEvent = true;
		noCommands = false;
	    } else if (argv[ind].equals("--setEvent")) {
		setEvent = true;
		noCommands = false;
	    } else if (argv[ind].equals("--addInstance")) {
		addInstance = true;
		noCommands = false;
	    } else if (argv[ind].equals("--listInstances")) {
		listInstances = true;
		noCommands = false;
		headingIndex = 9;
	    } else if (argv[ind].equals("--deleteInstance")) {
		noCommands = false;
		deleteInstance = true;
	    } else if (argv[ind].equals("--setInstance")) {
		noCommands = false;
		setInstance = true;
	    } else if (argv[ind].equals("--addSeries")) {
		noCommands = false;
		addSeries = true;
	    } else if (argv[ind].equals("--listSeries")) {
		noCommands = false;
		listSeries = true;
		headingIndex = 10;
	    } else if (argv[ind].equals("--deleteSeries")) {
		noCommands = false;
		deleteSeries = true;
	    } else if (argv[ind].equals("--setSeries")) {
		noCommands = false;
		setSeries = true;
	    } else if (argv[ind].equals("--addSeriesInst")) {
		noCommands = false;
		addSeriesInst = true;
	    } else if (argv[ind].equals("--listSeriesInst")) {
		noCommands = false;
		listSeriesInst = true;
		headingIndex = 11;
	    } else if (argv[ind].equals("--deleteSeriesInst")) {
		noCommands = false;
		deleteSeriesInst = true;
	    } else if (argv[ind].equals("--addAttendee")) {
		noCommands = false;
		addAttendee = true;
	    } else if (argv[ind].equals("--listAttendees")) {
		noCommands = false;
		listAttendees = true;
		headingIndex = 12;
	    } else if (argv[ind].equals("--deleteAttendee")) {
		noCommands = false;
		deleteAttendee = true;
	    } else if (argv[ind].equals("--setAttendee")) {
		noCommands = false;
		setAttendee = true;
	    } else if (argv[ind].equals("--applySeries")) {
		noCommands = false;
		applySeries = true;
	    } else if (argv[ind].equals("--getCalendars")) {
		noCommands = false;
		getCalendars = true;
	    } else if (argv[ind].equals("--copy")) {
		noCommands = false;
		copyToClipboard = true;
	    } else if (argv[ind].equals("--send")) {
		noCommands = false;
		sendViaEmail = true;
	    } else if (argv[ind].equals("--saveToDir")) {
		noCommands = false;
		ind++; hasArgTest(ind, argv);
		saveToDir = true;
		dir = new File(argv[ind]);
		if (dir.exists()) {
		    if (!dir.isDirectory()) {
			System.err.format("not a directory: " + argv[ind]);
			System.exit(1);
		    }
		} else {
		    if (!dir.mkdirs()) {
			System.err.format("could not create " + dir.toString());
			System.exit(1);
		    }
		}
	    } else if (argv[ind].startsWith("-")) {
		System.err.format("ecdb: unrecognized option \"%s\"\n",
				  argv[ind]);
		System.exit(1);
	    } else {
		break;
	    }
	    ind++;
	}

	if (noHeaders) headingIndex = -1;

	int iarray[] = null;
	if (id) {
	    iarray = new int[argv.length-ind];
	    for (int i = 0; i < iarray.length; i++) {
		try {
		    iarray[i] = Integer.parseInt(argv[ind+i]);
		} catch (NumberFormatException e) {
		    System.err.println("ecdb: non-option argument is not an "
				       + "integer - " + argv[ind+i]);
		}
	    }
	}
	boolean usePattern = false;
	boolean splitUser = false;
	if (upattern != null) {
	    if (upattern.contains("|")) {
		splitUser = true;
	    } else {
		usePattern = true;
	    }
	}
	if (opattern != null) usePattern = true;
	if (lpattern != null) usePattern = true;
	if (epattern != null) usePattern = true;

	String[] sarray = (usePattern)? new String[1]:
	    ((splitUser)? upattern.split("\\|"):
	     new String[argv.length-ind]);
	if (usePattern) {
	    if (upattern != null && listUsers) sarray[0] = upattern;
	    if (opattern != null && listOwners) sarray[0] = opattern;
	    if (lpattern != null && listLocations) sarray[0] = lpattern;
	    if (epattern != null && listEvents) sarray[0] = epattern;
	} else if (!splitUser) {
	    System.arraycopy(argv, ind, sarray, 0, sarray.length);
	}
	try {
	    if (config) {
		Support.editConfig(f);
	    }

	    if (createDB) {
		try (ECDB ecdb = new ECDB(f)) {
		    ecdb.createDB();
		}
	    }

	    if (createTables) {
		try (ECDB ecdb = new ECDB(f)) {
		    ecdb.allowRoles();
		}
		try (ECDB ecdb = new ECDB(f)) {
		    ecdb.createTables();
		}
	    }
	    if (addCarrier) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			ecdb.addCarrier(conn, carrier);
		    }
		}
	    }
	    if (deleteCarrier) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (carrierID > 0) {
			    ecdb.deleteCarrier(conn, carrierID);
			} else {
			    ecdb.deleteCarrier(conn, carrier);
			}
		    }
		}
	    }
	    if (listCarriers) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (id) {
			    vector = ecdb.listCarriers(conn, iarray, full);
			} else {
			    vector = ecdb.listCarriers(conn, sarray, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setCarrier) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			ecdb.setCarrier(conn, carrierID, carrier);
		    }
		}
	    }
	    if (setCarrierMapping) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			ecdb.setCarrierMapping(conn,
					       countryPrefix,
					       ((carrierID > 0)? carrierID:
						ecdb.findCarrier(conn,
								 carrier)),
					       idomain);
		    }
		}
	    }
	    if (listCarrierMap) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			String cprefix = countryPrefix;
			if (cprefix != null && carrier == null) carrier="%";
			if (carrier != null && cprefix == null) cprefix="%";
			vector = ecdb.listCarrierMap(conn, cprefix, carrier,
						     full);
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (addUser) {
		boolean lnf = (lastNameFirst == null)? Boolean.FALSE:
		    lastNameFirst.booleanValue();
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (carrierID == -1) {
			    if (carrier == null) carrier = "OTHER";
			    carrierID = ecdb.findCarrier(conn, carrier);
			}
			ecdb.addUserInfo(conn, firstName, lastName, lnf, title,
					 emailAddr, countryPrefix, cellNumber,
					 carrierID);
		    }
		}
	    }
	    if (listUsers) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (id) {
			    vector = ecdb.listUserInfo(conn, iarray, full);
			} else {
			    vector = ecdb.listUserInfo(conn, sarray, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (getCellEmail) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1) {
			    if (upattern != null) {
				userID = ecdb.findUserInfo(conn, upattern);
			    }
			}
			String addr =
			    ecdb.getUserCellphoneEmail(conn, userID, full);
			System.out.println(addr);
		    }
		}
	    }
	    if (setUser) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (carrierID == -1) {
			    if (carrier != null) {
				carrierID = ecdb.findCarrier(conn, carrier);
			    }
			}
			ecdb.setUserInfo(conn, userID,
					 firstName, lastName, lastNameFirst,
					 title, emailAddr,
					 countryPrefix, cellNumber, carrierID,
					 status);
		    }
		}
	    }
	    if (deleteUser) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID != -1) {
			    ecdb.deleteUserInfo(conn, userID);
			} else if (upattern != null) {
			    ecdb. deleteUserInfo(conn, upattern);
			} else if (iarray != null && iarray.length > 0) {
			    ecdb.deleteUserInfo(conn, iarray);
			}
		    }
		}
	    }
	    if (addOwner) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			ecdb.addOwner(conn, label, summary, idomain);
		    }
		}
	    }
	    if (listOwners) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (id) {
			    vector = ecdb.listOwners(conn, iarray, full);
			} else {
			    vector = ecdb.listOwners(conn, sarray, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setOwner) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.setOwner(conn, ownerID, label, summary, idomain);
		    }
		}
	    }
	    if (deleteOwner) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID != -1) {
			    ecdb.deleteUserInfo(conn, ownerID);
			} else if (opattern != null) {
			    ecdb.deleteOwner(conn, opattern);
			} else if (iarray != null && iarray.length > 0) {
			    ecdb.deleteOwner(conn, iarray);
			}
		    }
		}
	    }
	    if (addLocation) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			ecdb.addLocation(conn, label, location);
		    }
		}
	    }
	    if (listLocations) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (id) {
			    vector = ecdb.listLocations(conn, iarray, full);
			} else {
			    vector = ecdb.listLocations(conn, sarray, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setLocation) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.setLocation(conn, locationID, label, location);
		    }
		}
	    }
	    if (deleteLocation) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (locationID != -1) {
			    ecdb.deleteUserInfo(conn, locationID);
			} else if (lpattern != null) {
			    ecdb.deleteLocation(conn, lpattern);
			} else if (iarray != null && iarray.length > 0) {
			    ecdb.deleteLocation(conn, iarray);
			}
		    }
		}
	    }
	    if (addFirstAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (forEmail == null) forEmail = true;
			if (forPhone == null) forPhone = true;
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.addFirstAlarm(conn, userID, ownerID,
					   locationID, eventTime, weekday,
					   alarmTime, forEmail, forPhone);
		    }
		}
	    }
	    if (listFirstAlarms) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			vector = ecdb.listFirstAlarms(conn, userID, ownerID,
						      locationID, eventTime,
						      weekday, full);
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setFirstAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.setFirstAlarm(conn, userID, ownerID,
					   locationID, eventTime, weekday,
					   alarmTime, forEmail, forPhone);
		    }
		}
	    }
	    if (deleteFirstAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.deleteFirstAlarm(conn, userID, ownerID,
					      locationID, eventTime, weekday);
		    }
		}
	    }
	    if (deleteFirstAlarms) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.deleteFirstAlarms(conn, userID, ownerID,
					       locationID, eventTime, weekday,
					       false);
		    }
		}
	    }
	    if (addSecondAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (forEmail == null) forEmail = false;
			if (forPhone == null) forPhone = true;
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.addSecondAlarm(conn, userID, ownerID,
					    locationID, offset,
					    forEmail, forPhone);
		    }
		}
	    }
	    if (listSecondAlarms) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			vector = ecdb.listSecondAlarms(conn, userID, ownerID,
						       locationID, full);
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setSecondAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.setSecondAlarm(conn, userID, ownerID,
					    locationID, offset,
					    forEmail, forPhone);
		    }
		}
	    }
	    if (deleteSecondAlarm) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.deleteSecondAlarm(conn, userID, ownerID,
					       locationID);
		    }
		}
	    }
	    if (deleteSecondAlarms) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.deleteSecondAlarms(conn, userID, ownerID,
						locationID,
						false);
		    }
		}
	    }
	    if (addPreEventDefault) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			// if (forEmail == null) forEmail = false;
			// if (forPhone == null) forPhone = true;
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.addPreEventDefault(conn, userID, ownerID,
						peDefault);
		    }
		}
	    }
	    if (listPreEventDefaults) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			vector = ecdb.listPreEventDefaults(conn, userID,
							   ownerID, full);
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setPreEventDefault) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.setPreEventDefault(conn, userID, ownerID,
						peDefault);
		    }
		}
	    }
	    if (deletePreEventDefault) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.deletePreEventDefault(conn, userID, ownerID);
		    }
		}
	    }
	    if (deletePreEventDefaults) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.deletePreEventDefaults(conn, userID, ownerID,
						    false);
		    }
		}
	    }
	    if (addEvent) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.addEvent(conn, ownerID, label, description);
		    }
		}
	    }
	    if (listEvents) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (ownerID != -1) {
			    vector = ecdb.listEventsForOwner(conn, ownerID,
							     full);
			} else if (opattern != null) {
			    vector = new Vector<Vector<Object>>();
			} else if (id) {
			    vector = ecdb.listEvents(conn, iarray, full);
			} else {
			    vector = ecdb.listEvents(conn, sarray, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setEvent) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			ecdb.setEvent(conn, eventID, ownerID, label,
				      description);
		    }
		}
	    }
	    if (deleteEvent) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (eventID != -1) {
			    ecdb.deleteEvent(conn, eventID);
			} else if (epattern != null) {
			    ecdb.deleteEvent(conn, epattern);
			} else if (iarray != null && iarray.length > 0) {
			    ecdb.deleteEvent(conn, iarray);
			}
		    }
		}
	    }
	    if (addInstance) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			if (status == null) {
			    status = "CONFIRMED";
			}
			ecdb.addEventInstance(conn, eventID, locationID,
					      preEventType,
					      preEventOffset,
					      startDate, startTime,
					      endDate, endTime, status);
		    }
		}
	    }
	    if (listInstances) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (instanceID != -1) {
			    vector = ecdb.listEventInstance(conn, instanceID,
							    full);
			} else if (id) {
			    vector = ecdb.listEventInstances(conn, iarray,
							     full);
			} else {
			    if (ownerID == -1 && opattern != null) {
				ownerID = ecdb.findOwner(conn, opattern);
			    }
			    if (eventID == -1 && epattern != null) {
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1 && lpattern != null) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    if (startDate == null && startTime == null &&
				status == null) {
				vector = ecdb.listEventInstances(conn,
								 ownerID,
								 eventID,
								 locationID,
								 full);
			    } else {
				vector = ecdb.listEventInstances(conn,
								 ownerID,
								 locationID,
								 startDate,
								 startTime,
								 status,
								 full);
			    }
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setInstance) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			ecdb.setEventInstance(conn, instanceID,
					      eventID, locationID,
					      preEventType,
					      preEventOffset,
					      startDate, startTime,
					      endDate, endTime,
					      status);
		    }
		}
	    }
	    if (deleteInstance) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (instanceID != -1) {
			    ecdb.deleteEventInstance(conn, instanceID);
			} else if (id && iarray!= null && iarray.length > 0) {
			    ecdb.deleteEventInstances(conn, iarray);
			} else {
			    if (ownerID == -1 && opattern != null) {
				ownerID = ecdb.findOwner(conn, opattern);
			    }
			    if (eventID == -1 && epattern != null) {
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1 && lpattern != null) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    instanceID = ecdb.findEventInstance(conn,
								eventID,
								locationID,
								startDate,
								startTime);
			    ecdb.deleteEventInstance(conn, instanceID);
			}
		    }
		}
	    }
	    if (addSeries) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			ecdb.addSeries(conn, ownerID, label);
		    }
		}
	    }
	    if (listSeries) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (id) {
			    vector = ecdb.listSeries(conn, iarray, full);
			} else {
			    if (ownerID == -1 && opattern != null) {
				ownerID = ecdb.findOwner(conn, opattern);
			    }
			    vector = ecdb.listSeries(conn, ownerID,
						     spattern, full);
			}
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (setSeries) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (seriesID == -1 && ownerID != -1 &&
			    spattern != null) {
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			ecdb.setSeries(conn, seriesID, ownerID, label);
		    }
		}
	    }
	    if (deleteSeries) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (seriesID != -1) {
			    ecdb.deleteSeries(conn, seriesID);
			} else if (spattern != null) {
			    if (ownerID == -1 && opattern != null) {
				ownerID = ecdb.findOwner(conn, opattern);
				if (ownerID == -1) {
				    System.err.println("ecdb: "
						       + "no matching owner "
						       + "for " + spattern);
				    System.exit(1);
				}
			    }
			    ecdb.deleteSeries(conn, ownerID, spattern);
			} else if (iarray != null && iarray.length > 0) {
			    ecdb.deleteSeries(conn, iarray);
			}
		    }
		}
	    }
	    if (addSeriesInst) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (seriesID == -1 && spattern != null) {
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			if(eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			if (locationID == -1 && lpattern != null) {
			    locationID = ecdb.findLocation(conn, lpattern);
			}
			if (instanceID == -1) {
			    instanceID = ecdb.findEventInstance
				(conn, eventID, locationID,
				 startDate, startTime);
			}
			ecdb.addSeriesInstance(conn, seriesID, instanceID);
		    }
		}
	    }
	    if (listSeriesInst) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (seriesID == -1 && spattern != null) {
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			if(eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			if (instanceID == -1) {
			    instanceID = ecdb.findEventInstance
				(conn, eventID, locationID,
				 startDate, startTime);
			}
			vector = ecdb.listSeriesInstance(conn, seriesID,
							 instanceID,
							 full);
			if (cols == null) {
			    print(vector, headingIndex, full);
			} else {
			    print(vector, cols, headingIndex, full);
			}
		    }
		}
	    }
	    if (deleteSeriesInst) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			}
			if (seriesID == -1 && spattern != null) {
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			if(eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			}
			if (instanceID == -1) {
			    instanceID = ecdb.findEventInstance
				(conn, eventID, locationID,
				 startDate, startTime);
			}
			ecdb.deleteSeriesInstance(conn, seriesID, instanceID);
		    }
		}
	    }
	    if (addAttendee) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (instanceID == -1) {
			    if (eventID == -1 && epattern != null) {
				if (ownerID == -1 && opattern != null) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1 && lpattern != null) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    instanceID = ecdb.findEventInstance(conn,
								eventID,
								locationID,
								startDate,
								startTime);
			} else {
			    if (eventID == -1) {
				eventID = ecdb.getEventInstanceEventID
				    (conn, instanceID);
			    } else if (eventID != ecdb.getEventInstanceEventID
				       (conn, instanceID)) {
				throw new Exception("inconsistent eventIDs");
			    }
			    if (ownerID == -1) {
				ownerID = ecdb.getEventOwnerID(conn, eventID);
			    } else if (ownerID != ecdb.getEventOwnerID
				       (conn, eventID)) {
				throw new Exception("inconsistent ownerIDs");
			    }
			}
			if (attendingPreEvent == null) {
			    attendingPreEvent = ecdb.getPreEventDefault
				(conn, userID, ownerID);
			}
			if (attendingPreEvent == null) {
			    attendingPreEvent = Boolean.FALSE;
			}
			ecdb.addAttendee(conn, userID, instanceID,
					 (boolean)attendingPreEvent, seriesID);
		    }
		}
	    }
	    if (listAttendees) {
		Vector<Vector<Object>> vector = null;
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (instanceID == -1) {
			    if (eventID == -1 && epattern != null) {
				if (ownerID == -1 && opattern != null) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1 && lpattern != null) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    instanceID = ecdb.findEventInstance(conn,
								eventID,
								locationID,
								startDate,
								startTime);
			}
			if (seriesID == -1 && spattern != null) {
			    if (ownerID == -1 && opattern != null) {
				ownerID = ecdb.findOwner(conn, opattern);
			    }
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			vector = ecdb.listAttendees(conn, userID, instanceID,
						    seriesID, attendeeState,
						    full);
		    }
		}
		if (cols == null) {
		    print(vector, headingIndex, full);
		} else {
		    print(vector, cols, headingIndex, full);
		}
	    }
	    if (deleteAttendee) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (instanceID == -1) {
			    if (eventID == -1 && epattern != null) {
				if (ownerID == -1 && opattern != null) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1 && lpattern != null) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    instanceID = ecdb.findEventInstance(conn,
								eventID,
								locationID,
								startDate,
								startTime);
			}
			if (seriesID == -1 && spattern != null) {
			    if (instanceID != -1) {
				if (eventID == -1) {
				    eventID = ecdb.getEventInstanceEventID
					(conn, instanceID);
				} else if (eventID !=
					   ecdb.getEventInstanceEventID
					   (conn, instanceID)) {
				    throw new Exception("eventID inconsistent");
				}
				if (ownerID == -1) {
				    ownerID = ecdb.getEventOwnerID(conn,
								   eventID);
				} else if (ownerID !=
					   ecdb.getEventOwnerID(conn,eventID)) {
				    throw new Exception("ownerID inconsistent");
				}
				if (ownerID == -1 && opattern != null) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				seriesID = ecdb.findSeries(conn, ownerID,
							   spattern);
			    }
			    ecdb.deleteAttendee(conn, userID, instanceID,
						attendeeState, seriesID,
						false);
			}
		    }
		}
	    }
	    if (setAttendee) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (instanceID == -1) {
			    if (eventID == -1) {
				if (ownerID == -1) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				eventID = ecdb.findEvent(conn, ownerID,
							 epattern);
			    }
			    if (locationID == -1) {
				locationID = ecdb.findLocation(conn, lpattern);
			    }
			    instanceID = ecdb.findEventInstance(conn,
								eventID,
								locationID,
								startDate,
								startTime);
			}
			if (seriesID == -1) {
			    if (instanceID != -1) {
				if (eventID == -1) {
				    eventID = ecdb.getEventInstanceEventID
					(conn, instanceID);
				} else if (eventID !=
					   ecdb.getEventInstanceEventID
					   (conn, instanceID)) {
				    throw new Exception("eventID inconsistent");
				}
				if (ownerID == -1) {
				    ownerID = ecdb.getEventOwnerID(conn,
								   eventID);
				} else if (ownerID !=
					   ecdb.getEventOwnerID(conn,eventID)) {
				    throw new Exception("ownerID inconsistent");
				}
				if (ownerID == -1) {
				    ownerID = ecdb.findOwner(conn, opattern);
				}
				seriesID = ecdb.findSeries(conn, ownerID,
							   spattern);
			    }
			    ecdb.setAttendee(conn, userID, instanceID,
					     attendeeState, seriesID);
			}
		    }
		}
	    }
	    if (applySeries) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			}
			if (seriesID == -1) {
			    if (ownerID == -1) {
				ownerID = ecdb.findOwner(conn, opattern);
			    }
			    seriesID = ecdb.findSeries(conn, ownerID, spattern);
			}
			ecdb.applySeries(conn, userID, seriesID);
		    }
		}
	    }
	    if (getCalendars) {
		try (ECDB ecdb = new ECDB(f)) {
		    try (Connection conn = ecdb.getConnection()) {
			if (userID == -1 && upattern != null) {
			    userID = ecdb.findUserInfo(conn, upattern);
			    if (userID == -1) {
				System.err.println("ecdb: user \"" + upattern
						   + "\" does not exist");
				System.exit(1);
			    }
			}
			if (ownerID == -1 && opattern != null) {
			    ownerID = ecdb.findOwner(conn, opattern);
			    if (ownerID == -1) {
				System.err.println("ecdb: owner \"" + opattern
						   + "\" does not exist");
				System.exit(1);
			    }
			}
			if (eventID == -1 && epattern != null) {
			    eventID = ecdb.findEvent(conn, ownerID, epattern);
			    if (eventID == -1) {
				System.err.println("ecdb: event \"" + epattern
						   + "\" does not exist");
				System.exit(1);
			    }
			}
			boolean flag = false;
			if ((forEmail == null && forPhone == null)
			    || (forEmail == null && forPhone == false)
			    || (forPhone == null && forEmail == false)
			    || (forPhone == forEmail)) {
			    throw new IllegalStateException
				("Must set --forEmail or --forPhone to true "
				 + "but not both");
			}
			if (forPhone == null && forEmail) flag = true;
			else if (forEmail == null && forPhone) flag = false;
			if (forEmail) {
			    if (template != null) {
				templateURL = template.toURI().toURL();
			    }
			    if (altTemplate != null) {
				altTemplateURL = altTemplate.toURI().toURL();
			    }
			}
			Vector<UserCalendars> vector =
			    ecdb.getCalendars(conn, userID, ownerID,
					      eventID, flag);
			int vlen = vector.size();
			if (vlen == 0) {
			    //nothing to do!
			} else if (copyToClipboard && vlen > 1) {
			    System.err.println("ecdb: multiple users when only"
					       + " one is allowed");
			} else if (copyToClipboard) {
			    Vector<byte[]> calendars =
				vector.get(0).calendars;
			    copyToClipboard(calendars, true);
			} else if (saveToDir && vlen > 1) {
			    System.err.println("ecdb: multiple users when only"
					       + " one is allowed");
			} else if (saveToDir) {
			    if (dir != null && vlen == 1) {
				Vector<byte[]> calendars = vector.get(0)
				    .calendars;
				saveToDirectory(dir, calendars);
			    }
			} else if (sendViaEmail) {
			    ecdb.setSubject(subject);
			    ecdb.setMediaType(mediaType);
			    ecdb.setTemplateURL(templateURL);
			    ecdb.setAltMediaType(altMediaType);
			    ecdb.setAltTemplateURL(altTemplateURL);
			    if (preflight) {
				if (sendViaEmail(ecdb, conn, vector,
						 suppressCalendars,
						 null, true) == false) {
				    return;
				}
			    }
			    sendViaEmail(ecdb, conn, vector,
					 suppressCalendars, null, false);
			} else {
			    ecdb.setSubject(subject);
			    ecdb.setMediaType(mediaType);
			    ecdb.setTemplateURL(templateURL);
			    ecdb.setAltMediaType(altMediaType);
			    ecdb.setAltTemplateURL(altTemplateURL);
			    PrintWriter out = new PrintWriter(System.out);
			    dryrunForSend(ecdb, conn, out, /*subject,*/ vector,
					  // mediaType, templateURL,
					  // altMediaType, altTemplateURL,
					  flag, suppressCalendars);
			}
		    }
		}
	    }
	    if (noSetupOptions && noCommands) {
		try (ECDB ecdb = new ECDB(f)) {
		    Support.createGUI(ecdb);
		}
	    }
	} catch (Exception e) {
	    e.printStackTrace();
	    System.exit(1);
	}
    }
}
