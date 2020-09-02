// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.noexception;

import com.machinezoo.hookless.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/**
 * Wrappers for {@link ExceptionHandler} and {@link ExceptionFilter}.
 */
@StubDocs
@NoTests
@DraftApi("if this is used often, we might consider neater API, e.g. .silence().blocking().run(...)")
public class ReactiveExceptions {
	/*
	 * We aren't using the more concise anonymous inner classes, because we want nice class names in stack traces.
	 */
	private static class BlockingExceptionHandler extends ExceptionHandler {
		private final ExceptionHandler inner;
		BlockingExceptionHandler(ExceptionHandler inner) {
			this.inner = inner;
		}
		@Override
		public boolean handle(Throwable exception) {
			if (CurrentReactiveScope.blocked())
				return inner.handle(exception);
			return false;
		}
	}
	public static ExceptionHandler blocking(ExceptionHandler handler) {
		return new BlockingExceptionHandler(handler);
	}
	private static class NonBlockingExceptionHandler extends ExceptionHandler {
		private final ExceptionHandler inner;
		NonBlockingExceptionHandler(ExceptionHandler inner) {
			this.inner = inner;
		}
		@Override
		public boolean handle(Throwable exception) {
			if (!CurrentReactiveScope.blocked())
				return inner.handle(exception);
			return false;
		}
	}
	public static ExceptionHandler nonblocking(ExceptionHandler handler) {
		return new NonBlockingExceptionHandler(handler);
	}
	private static class BlockingExceptionFilter extends ExceptionFilter {
		private final ExceptionFilter inner;
		BlockingExceptionFilter(ExceptionFilter inner) {
			this.inner = inner;
		}
		@Override
		public void handle(Throwable exception) {
			if (CurrentReactiveScope.blocked())
				inner.handle(exception);
		}
	}
	public static ExceptionFilter blocking(ExceptionFilter handler) {
		return new BlockingExceptionFilter(handler);
	}
	private static class NonBlockingExceptionFilter extends ExceptionFilter {
		private final ExceptionFilter inner;
		NonBlockingExceptionFilter(ExceptionFilter inner) {
			this.inner = inner;
		}
		@Override
		public void handle(Throwable exception) {
			if (!CurrentReactiveScope.blocked())
				inner.handle(exception);
		}
	}
	public static ExceptionFilter nonblocking(ExceptionFilter handler) {
		return new NonBlockingExceptionFilter(handler);
	}
}
