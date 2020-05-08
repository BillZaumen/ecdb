package org.bzdev.ecdb;

class ECDBException extends Exception {

    public ECDBException() {
	super();
    }

    public ECDBException(String message) {
	super(message);
    }

    public ECDBException(String message, Throwable cause) {
	super(message, cause);
    }

    protected ECDBException(String message, Throwable cause,
			    boolean enableSuppression,
			    boolean writeableStackTrace)
    {
	super(message, cause);
    }

    public ECDBException(Throwable cause) {
	super(cause);
    }

}
