package org.bzdev.ecdb;

/**
 * Service-provider interface for a CellEmailFinder;
 */
public interface CellEmailFinderSPI {

    /**
     * Determine if a provide supports a particular prefix and
     * cell phone number.
     * @param prefix the prefix (e.g, "1" for the U.S.)
     * @param cellNumber  the cell-phone number
     */
    boolean isSupported(String prefix, String cellNumber);

    /**
     * Get an instance of CellEmailFinder.
     * @param prefix the prefix (e.g, "1" for the U.S.)
     * @param cellNumber  the cell-phone number
     */
    CellEmailFinder getInstance(String prefix, String cellNumber);

}
