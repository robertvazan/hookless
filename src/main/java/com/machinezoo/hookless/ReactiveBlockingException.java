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
 * This is because a lot of code creates this exception as a fallback before there is any reason to block.
 */
public class ReactiveBlockingException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	public ReactiveBlockingException(String message, Throwable cause) {
		super(message, cause);
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
	/*
	 * Blocking of current computation can be explicitly requested by calling one of the methods below.
	 * These methods throw instead of returning the exception to make sure people don't forget to throw it.
	 * They nevertheless declare exception return, so that callers can add throw clause to avoid issues with unreachable code.
	 */
	public static ReactiveBlockingException block(String message, Throwable cause) {
		CurrentReactiveScope.block();
		throw new ReactiveBlockingException(message, cause);
	}
	public static ReactiveBlockingException block(String message) {
		throw block(message, null);
	}
	public static ReactiveBlockingException block(Throwable cause) {
		throw block(null, cause);
	}
	public static ReactiveBlockingException block() {
		throw block(null, null);
	}
}
