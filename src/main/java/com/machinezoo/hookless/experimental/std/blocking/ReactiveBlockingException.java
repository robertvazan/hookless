// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.blocking;

public class ReactiveBlockingException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	public ReactiveBlockingException(String message, Throwable cause) {
		super(message, cause);
	}
	public ReactiveBlockingException(String message) {
		this(message, null);
	}
	public ReactiveBlockingException(Throwable cause) {
		super(cause != null ? cause.toString() : null, cause);
	}
	public ReactiveBlockingException() {
		this(null, null);
	}
	public static ReactiveBlockingException block(String message, Throwable cause) {
		ReactiveBlocking.block();
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
