// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

/**
 * Default exception to throw when reactive code needs to <a href="https://hookless.machinezoo.com/blocking">reactively block</a>.
 * <p>
 * Merely creating or throwing this exception is not sufficient to indicate blocking.
 * Current reactive computation must be explicitly marked as blocked before throwing
 * by calling {@link CurrentReactiveScope#block()}.
 * This class defines convenience {@link #block()} method and its overloads that
 * call {@link CurrentReactiveScope#block()} before throwing {@code ReactiveBlockingException}.
 * <p>
 * Reactive code should preferably return fallback in case it signals blocking,
 * because that allows the rest of the code to continue and to trigger further blocking operations.
 * This way all blocking operations (e.g. database queries) can execute in parallel.
 * <p>
 * It is however sometimes impossible to provide a reasonable fallback result.
 * In those cases, throwing an exception is acceptable.
 * <p>
 * This exception type is provided as the most descriptive for cases of reactive blocking.
 * Its use is not mandatory though. Code that reactively blocks may throw any exception.
 * 
 * @see ReactiveScope#block()
 * @see CurrentReactiveScope#block()
 * @see <a href="https://hookless.machinezoo.com/blocking">Reactive blocking</a>
 */
public class ReactiveBlockingException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	/*
	 * Constructors do not cause blocking on their own,
	 * so that the exception can be created as a fallback before there is any reason to block.
	 */
	/**
	 * Constructs new {@code ReactiveBlockingException} with the specified message and cause.
	 * <p>
	 * Merely calling this constructor does not <a href="https://hookless.machinezoo.com/blocking">block</a>
	 * current {@link ReactiveScope}. Use {@link #block(String, Throwable)} for that.
	 * 
	 * @param message
	 *            informative message (possibly {@code null}) that can be later retrieved via {@link Throwable#getMessage()}
	 * @param cause
	 *            cause of this exception (possibly {@code null}) that can be later retrieved via {@link Throwable#getCause()}
	 * 
	 * @see #block(String, Throwable)
	 */
	public ReactiveBlockingException(String message, Throwable cause) {
		super(message, cause);
	}
	/**
	 * Constructs new {@code ReactiveBlockingException} with the specified message.
	 * <p>
	 * Merely calling this constructor does not <a href="https://hookless.machinezoo.com/blocking">block</a>
	 * current {@link ReactiveScope}. Use {@link #block(String)} for that.
	 * 
	 * @param message
	 *            informative message (possibly {@code null}) that can be later retrieved via {@link Throwable#getMessage()}
	 * 
	 * @see #block(String)
	 */
	public ReactiveBlockingException(String message) {
		this(message, null);
	}
	/**
	 * Constructs new {@code ReactiveBlockingException} with the specified cause.
	 * If {@code cause} is not {@code null}, message string of this exception will be set to {@code cause.toString()}.
	 * <p>
	 * Merely calling this constructor does not <a href="https://hookless.machinezoo.com/blocking">block</a>
	 * current {@link ReactiveScope}. Use {@link #block(Throwable)} for that.
	 * 
	 * @param cause
	 *            cause of this exception (possibly {@code null}) that can be later retrieved via {@link Throwable#getCause()}
	 * 
	 * @see #block(Throwable)
	 */
	public ReactiveBlockingException(Throwable cause) {
		super(cause != null ? cause.toString() : null, cause);
	}
	/**
	 * Constructs new {@code ReactiveBlockingException}.
	 * The exception will have no message, i.e. it will return {@code null} from {@link Throwable#getMessage()}.
	 * <p>
	 * Merely calling this constructor does not <a href="https://hookless.machinezoo.com/blocking">block</a>
	 * current {@link ReactiveScope}. Use {@link #block()} for that.
	 * 
	 * @see #block()
	 */
	public ReactiveBlockingException() {
		this(null, null);
	}
	/*
	 * Blocking of current computation can be explicitly requested by calling one of the methods below.
	 * These methods throw instead of returning the exception to make sure people don't forget to throw it.
	 * They nevertheless declare exception return, so that callers can add throw clause to avoid issues with unreachable code.
	 */
	/**
	 * Marks the current {@link ReactiveScope} as <a href="https://hookless.machinezoo.com/blocking">blocking</a>
	 * and then throws {@code ReactiveBlockingException} with the specified message and cause.
	 * Current reactive scope is blocked by calling {@link CurrentReactiveScope#block()}.
	 * <p>
	 * This method always throws and thus never returns. Declared return type is just a convenience
	 * that lets callers avoid unreachable code errors by placing the call in a {@code throw} statement,
	 * e.g. {@code throw ReactiveBlockingException.block(...)}.
	 * 
	 * @param message
	 *            message (possibly {@code null}) passed to {@link #ReactiveBlockingException(String, Throwable)}
	 * @param cause
	 *            cause (possibly {@code null}) passed to {@link #ReactiveBlockingException(String, Throwable)}
	 * @return never returns
	 * 
	 * @see #ReactiveBlockingException(String, Throwable)
	 */
	public static ReactiveBlockingException block(String message, Throwable cause) {
		CurrentReactiveScope.block();
		throw new ReactiveBlockingException(message, cause);
	}
	/**
	 * Marks the current {@link ReactiveScope} as <a href="https://hookless.machinezoo.com/blocking">blocking</a>
	 * and then throws {@code ReactiveBlockingException} with the specified message.
	 * Current reactive scope is blocked by calling {@link CurrentReactiveScope#block()}.
	 * <p>
	 * This method always throws and thus never returns. Declared return type is just a convenience
	 * that lets callers avoid unreachable code errors by placing the call in a {@code throw} statement,
	 * e.g. {@code throw ReactiveBlockingException.block(...)}.
	 * 
	 * @param message
	 *            message (possibly {@code null}) passed to {@link #ReactiveBlockingException(String)}
	 * @return never returns
	 * 
	 * @see #ReactiveBlockingException(String)
	 */
	public static ReactiveBlockingException block(String message) {
		throw block(message, null);
	}
	/**
	 * Marks the current {@link ReactiveScope} as <a href="https://hookless.machinezoo.com/blocking">blocking</a>
	 * and then throws {@code ReactiveBlockingException} with the specified cause.
	 * Current reactive scope is blocked by calling {@link CurrentReactiveScope#block()}.
	 * <p>
	 * This method always throws and thus never returns. Declared return type is just a convenience
	 * that lets callers avoid unreachable code errors by placing the call in a {@code throw} statement,
	 * e.g. {@code throw ReactiveBlockingException.block(...)}.
	 * 
	 * @param cause
	 *            cause (possibly {@code null}) passed to {@link #ReactiveBlockingException(Throwable)}
	 * @return never returns
	 * 
	 * @see #ReactiveBlockingException(Throwable)
	 */
	public static ReactiveBlockingException block(Throwable cause) {
		throw block(null, cause);
	}
	/**
	 * Marks the current {@link ReactiveScope} as <a href="https://hookless.machinezoo.com/blocking">blocking</a>
	 * and then throws {@code ReactiveBlockingException}.
	 * Current reactive scope is blocked by calling {@link CurrentReactiveScope#block()}.
	 * <p>
	 * This method always throws and thus never returns. Declared return type is just a convenience
	 * that lets callers avoid unreachable code errors by placing the call in a {@code throw} statement,
	 * e.g. {@code throw ReactiveBlockingException.block(...)}.
	 * 
	 * @return never returns
	 * 
	 * @see #ReactiveBlockingException()
	 */
	public static ReactiveBlockingException block() {
		throw block(null, null);
	}
}
