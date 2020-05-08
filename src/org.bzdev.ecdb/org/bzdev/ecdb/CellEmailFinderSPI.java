package org.bzdev.ecdb;

public interface CellEmailFinderSPI {

    boolean isSupported(String prefix, String cellNumber);

    CellEmailFinder getInstance(String prefix, String cellNumber);

}
