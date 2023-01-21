// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.io.*;
import com.machinezoo.noexception.*;

/*
 * Keys must be immutable, equatable, hashable, stringifiable, serializable, and transparent.
 * Most keys should be records that embed enough information to uniquely identify something in the application.
 * Keys might contain parent key. Parent keys form a chain that identifies whole context of the key.
 * 
 * Some key types might be reusable, relying on opaque parameters for identifying information.
 * It is however preferable to define a lot of specialized keys to maximize diagnostic information.
 * 
 * Java records have poorly implemented hashCode(), which does not take into account class name.
 * Records with the same parameters, especially empty records, consequently share the same hash code.
 * If the key does not have any unique parameters, it is recommended to override hashCode().
 * 
 * Built-in Java serialization support is enforced, but keys should preferably support custom serialization.
 * Records will seamlessly support most serialization libraries.
 */
public interface ReactiveKey extends Serializable {
	/*
	 * Utility methods implementing default Java serialization.
	 * Arbitrary Serializable is allowed here, because serialization can be used with other key-like objects.
	 */
	static byte[] serialize(Serializable key) {
		var buffer = new ByteArrayOutputStream();
		Exceptions.wrap().run(() -> {
			try (var out = new ObjectOutputStream(buffer)) {
				out.writeObject(key);
			}
		});
		return buffer.toByteArray();
	}
	static <T extends Serializable> T deserialize(byte[] serialized, Class<T> clazz) {
		return Exceptions.wrap().get(() -> {
			var buffer = new ByteArrayInputStream(serialized);
			var in = new ObjectInputStream(buffer);
			return clazz.cast(in.readObject());
		});
	}
}
