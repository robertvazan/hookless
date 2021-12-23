// Part of Hookless: https://hookless.machinezoo.com
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
	requires org.slf4j;
}
