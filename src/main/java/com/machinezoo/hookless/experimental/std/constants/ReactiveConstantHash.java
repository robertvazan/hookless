// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.io.*;

/*
 * The constant object is only required to be serializable.
 */
public interface ReactiveConstantHash extends ReactiveConstant {
	Serializable compute();
}
