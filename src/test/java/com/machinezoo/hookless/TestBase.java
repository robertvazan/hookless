// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import org.awaitility.pollinterval.*;
import org.junit.jupiter.api.*;
import com.machinezoo.noexception.*;

public abstract class TestBase {
	@BeforeAll
	public static void awaitility() {
		setDefaultPollInterval(new FibonacciPollInterval());
	}
	public static void sleep(int millis) {
		Exceptions.sneak().run(() -> Thread.sleep(millis));
	}
	public static void settle() {
		sleep(100);
	}
}
