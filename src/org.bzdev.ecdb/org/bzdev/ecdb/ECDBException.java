package org.bzdev.ecdb;

/**
 * ECDB exception.
 * This exception indicates an error specific to the ECDB class.
 */
class ECDBException extends Exception {

    /**
     * Constructor.
     */
    public ECDBException() {
	super();
    }

    /**
     * Constructor providing a message.
     * @param message the message
     */
    public ECDBException(String message) {
	super(message);
    }

    /**
     * Constructor providing a message and cause.
     * @param message the message
     * @param cause the cause
     */
    public ECDBException(String message, Throwable cause) {
	super(message, cause);
    }

    /**
     * Constructor providing a message and cause.
     * @param message the message
     * @param cause the cause
     * @param enableSuppression whether or not suppression is enabled
     *        or disabled
     * @param writeableStackTrace whether or not the stack trace
     *        should be writable
     */
    protected ECDBException(String message, Throwable cause,
			    boolean enableSuppression,
			    boolean writeableStackTrace)
    {
	super(message, cause);
    }

    /**
     * Constructor providing a cause.
     * @param cause the cause
     */
    public ECDBException(Throwable cause) {
	super(cause);
    }

}
