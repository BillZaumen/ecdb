package org.bzdev.ecdb;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ServiceLoader;

/**
 * Base class for finding the email address to use for an MMS gateway
 * for a cell phone number. Classes implementing this interface
 * are obtained via a service-provider interface.
 * @see CellEmailFinderSPI
 */
public abstract class CellEmailFinder {

    static ServiceLoader<CellEmailFinderSPI> providers =
	ServiceLoader.load(CellEmailFinderSPI.class);

    /**
     * Look up the email address for a cell phone number's email address
     * for the number's MMS gateway,
     * using an instance of {@link CellEmailFinder} obtained from its
     * service-provider interface.
     * @param prefix the country prefix (e.g., 1 for the U.S.).
     * @param cellNumber the cell phone number, not including the
     *        prefix.
     * @return the email address
     */
    public abstract String lookup(String prefix, String cellNumber);

    /**
     * Look up the cell phone number's email address for the number's
     * MMS gateway.
     * The ECDB database will be used to find a cached value and if
     * that is not available or has aged out, the method
     * {@link CellEmailFinder#lookup(String, String)} will be used to
     * look up the number using an instance of {@link CellEmailFinder}
     * obtained from its service-provider interface.
     * @param prefix the country prefix (e.g., 1 for the U.S.).
     * @param cellNumber the cell phone number, not including the
     *        prefix.
     * @return the email address
     */
    public static String lookup(ECDB ecdb, Connection conn,
				String prefix, String cellNumber,
				int carrierID)
    {
	String emailAddr = null;
	String q = ecdb.getSQLProperty("getCellPhoneEmail");
	String cachedAddr = null;
	Boolean cachedSBC = null;
	boolean invalidCache = false;
	try (PreparedStatement ps = conn.prepareStatement(q)) {
	    ps.setString(1, prefix);
	    ps.setString(2, cellNumber);
	    try (ResultSet rs = ps.executeQuery()) {
		if (rs.next()) {
		    java.sql.Timestamp cachedModtime = rs.getTimestamp(3);
		    long ival = cachedModtime.toInstant()
			.until(Instant.now(), ChronoUnit.DAYS);
		    long max = ecdb.getCellEmailAddrTimeout();
		    cachedAddr = rs.getString(1);
		    cachedSBC = rs.getBoolean(2);
		    if (ival > max && cachedSBC == false) {
			invalidCache = true;
		    }
		}
	    }
	} catch (SQLException e) {
	    e.printStackTrace(System.err);
	}
	
	if (carrierID > 1) {
	    try {
		String domain = ecdb.getCarrierDomain(conn, prefix, carrierID);
		if (domain != null) {
		    emailAddr = cellNumber + "@" + domain;
		    if (cachedAddr != null) {
			invalidCache = true;
		    }
		} else if (cachedAddr != null) {
		    emailAddr = cachedAddr;
		}
	    } catch (SQLException e) {
	        e.printStackTrace(System.err);
	    }
	} else if (invalidCache == false) {
	    emailAddr = cachedAddr;
	}
	if (emailAddr == null) {
	    for (CellEmailFinderSPI p: providers) {
		if (p.isSupported(prefix, cellNumber)) {
		    CellEmailFinder finder =
			p.getInstance(prefix, cellNumber);
		    emailAddr = finder.lookup(prefix, cellNumber);
		    if (emailAddr != null) {
			if (cachedAddr == null) {
			    q = ecdb.getSQLProperty("addCellPhoneEmail");
			    try (PreparedStatement ps
				 = conn.prepareStatement(q)) {
				ps.setString(1, prefix);
				ps.setString(2, cellNumber);
				ps.setString(3, emailAddr);
				ps.setBoolean(4, false);
				ps.executeUpdate();
			    } catch (SQLException e) {
				e.printStackTrace(System.err);
			    }
			}
			break;
		    }
		}
	    }
	}
	if (invalidCache) {
	    if (emailAddr != null) {
		// cache in invalid so there is a cached email address
		// and we found a new value.
		q = ecdb.getSQLProperty("updateCellPhoneEmail");
		try (PreparedStatement ps = conn.prepareStatement(q)) {
		    ps.setString(1, emailAddr);
		    ps.setBoolean(2, false);
		    ps.setString(3, prefix);
		    ps.setString(4, cellNumber);
		    ps.executeUpdate();
		} catch (SQLException e) {
		    e.printStackTrace(System.err);
		}
	    } else {
		q = ecdb.getSQLProperty("deleteCellPhoneEmail");
		try (PreparedStatement ps = conn.prepareStatement(q)) {
		    ps.setString(1, prefix);
		    ps.setString(2, cellNumber);
		    ps.executeUpdate();
		} catch (SQLException e) {
		    e.printStackTrace(System.err);
		}

	    }
	}
	return emailAddr;
    }
}
