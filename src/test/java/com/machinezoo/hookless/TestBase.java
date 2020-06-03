// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import org.awaitility.pollinterval.*;
import org.junit.jupiter.api.*;

public abstract class TestBase {
	@BeforeAll public static void awaitility() {
		setDefaultPollInterval(new FibonacciPollInterval());
	}
}
