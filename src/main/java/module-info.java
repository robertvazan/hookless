// Part of Hookless: https://hookless.machinezoo.com
/**
 * Hookless is a reactive programming library for Java based on invalidate-then-refresh pattern and implemented via thread-local context object.
 * See the <a href="https://hookless.machinezoo.com/">website</a> for more information.
 * <p>
 * The main package {@link com.machinezoo.hookless} contains reactive primitives and other core reactive classes.
 * Other packages provide reactive wrappers and reactive alternatives to various JRE classes.
 * 
 * @see <a href="https://hookless.machinezoo.com/">Hookless website</a>
 */
module com.machinezoo.hookless {
	exports com.machinezoo.hookless;
	exports com.machinezoo.hookless.util;
	exports com.machinezoo.hookless.time;
	exports com.machinezoo.hookless.noexception;
	exports com.machinezoo.hookless.prefs;
	requires transitive java.prefs;
	requires com.machinezoo.stagean;
	requires transitive com.machinezoo.noexception;
	requires com.google.common;
	requires io.opentracing.api;
	requires io.opentracing.util;
	requires it.unimi.dsi.fastutil;
	requires micrometer.core;
}
