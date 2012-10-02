package com.comphenix.protocol.injector;

/**
 * Invoked when attempting to use a player that has already logged out.
 * 
 * @author Kristian
 */
public class PlayerLoggedOutException extends RuntimeException {

	public PlayerLoggedOutException() {
		// Default error message
		super("Cannot inject a player that has already logged out.");
	}
	
	public PlayerLoggedOutException(String message, Throwable cause) {
		super(message, cause);
	}

	public PlayerLoggedOutException(String message) {
		super(message);
	}

	public PlayerLoggedOutException(Throwable cause) {
		super(cause);
	}
	
	/**
	 * Construct an exception from a formatted message.
	 * @param message - the message to format.
	 * @param params - parameters.
	 * @return The formated exception
	 */
	public static PlayerLoggedOutException fromFormat(String message, Object... params) {
		return new PlayerLoggedOutException(String.format(message, params));
	}
}
