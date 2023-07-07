package org.bzdev.ecdb;

/**
 * Service-provider interface for a CellEmailFinder.
 * An instance of {@link CellEmailFinder} will typically use
 * some Internet service to do the actual lookup. That service
 * may work for only some prefixes and in some cases, for a
 * subset of the cell-phone numbers associated with that prefix.
 * The prefix for the U.S. ("1") covers the U.S., Canada, and
 * some other countries or regions, typically small ones, and
 * these can be distinguished by area codes.
 */
public interface CellEmailFinderSPI {

    /**
     * Determine if a provide supports a particular prefix and
     * cell phone number.
     * @param prefix the prefix (e.g, "1" for the U.S.)
     * @param cellNumber the cell-phone number
     * @return true if the provider supports this combination; false otherwise
     */
    boolean isSupported(String prefix, String cellNumber);

    /**
     * Get an instance of CellEmailFinder.
     * @param prefix the prefix (e.g, "1" for the U.S.)
     * @param cellNumber  the cell-phone number
     * @return an instance of this interface for the given arguments
     */
    CellEmailFinder getInstance(String prefix, String cellNumber);

}
