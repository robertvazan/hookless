// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

/*
 * Reactive code should preferably return fallback in case it signals blocking,
 * because that allows the rest of the code to continue and to trigger further blocking operations.
 * This way all blocking operations (like database queries) run all in parallel.
 * 
 * Sometimes however it is impossible to provide a reasonable fallback result.
 * In those cases, throwing an exception is acceptable.
 * Exceptions might be also thrown if fallback value from lower level code cannot be processed by higher level code.
 * 
 * This exception class is provided as the most descriptive for cases of reactive blocking.
 * Its use is however not mandatory. Reactively blocking code may throw any exception.
 * 
 * Merely throwing this exception is not sufficient to indicate blocking.
 * Current reactive computation must be explicitly marked as blocked before throwing.
 */
public class ReactiveBlockingException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	public ReactiveBlockingException(String message, Throwable cause) {
		super(message != null ? message : "Reactive computation is blocked.", cause);
	}
	public ReactiveBlockingException(String message) {
		this(message, null);
	}
	public ReactiveBlockingException(Throwable cause) {
		super(null, cause);
	}
	public ReactiveBlockingException() {
		this(null, null);
	}
}
