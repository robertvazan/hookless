// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import com.machinezoo.noexception.*;

/*
 * This is usually output of some cryptographic hash like SHA-256.
 * Word 0 contains lowest bits. Word 3 contains highest bits.
 * When serialized, the whole 256-bit hash is saved in big-endian byte order.
 */
public record ReactiveVersionHash(long word3, long word2, long word1, long word0) implements ReactiveVersion {
	/*
	 * May be used in some edge cases like hashing nulls. Nobody has ever found data that would be hashed to zero by SHA-256.
	 */
	public static final ReactiveVersionHash ZERO = new ReactiveVersionHash(0, 0, 0, 0);
	public long word(int offset) {
		return switch (offset) {
			case 0 -> word0;
			case 1 -> word1;
			case 2 -> word2;
			case 3 -> word3;
			default -> throw new IllegalArgumentException();
		};
	}
	public static ReactiveVersionHash fromWords(long[] words) {
		return new ReactiveVersionHash(words[0], words[1], words[2], words[3]);
	}
	public long[] toWords() {
		return new long[] { word3, word2, word1, word0 };
	}
	public static ReactiveVersionHash fromBytes(byte[] bytes) {
		if (bytes.length != 32)
			throw new IllegalArgumentException();
		var buffer = ByteBuffer.wrap(bytes);
		return new ReactiveVersionHash(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
	}
	public byte[] toBytes() {
		var buffer = ByteBuffer.allocate(32);
		buffer.putLong(word3);
		buffer.putLong(word2);
		buffer.putLong(word1);
		buffer.putLong(word0);
		return buffer.array();
	}
	public String toBase64() {
		/*
		 * In BASE64, 32 bytes will be padded to 33 and the bits will be then spread over 44 characters.
		 * The last character will not encode anything, so it will be just '='.
		 */
		return Base64.getUrlEncoder().encodeToString(toBytes()).substring(0, 43);
	}
	private static final SecureRandom random = new SecureRandom();
	public static ReactiveVersionHash random() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return ReactiveVersionHash.fromBytes(bytes);
	}
	public static ReactiveVersionHash hash(byte[] data) {
		if (data == null)
			return ZERO;
		return fromBytes(Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(data));
	}
	public static ReactiveVersionHash hash(String text) {
		return hash(text != null ? text.getBytes(StandardCharsets.UTF_8) : null);
	}
	/*
	 * Computes hash from object's type and its toString() output.
	 * Assumes that two objects of the same type have the same toString() output iff the two objects are equal.
	 * 
	 * This places a number of restrictions on the object and all objects it contains:
	 * - Implementation of toString() must be provided unless it is generated automatically like in enums and records.
	 * - If arrays are included in records, these records must override toString().
	 * - Types of contained objects must be sufficiently constrained to avoid ambiguous toString() output.
	 * 
	 * Generally, this works best for simple specialized records.
	 */
	public static ReactiveVersionHash hash(Object object) {
		if (object == null)
			return ZERO;
		return hash(object.getClass().getName() + ": " + object.toString());
	}
	public ReactiveVersionHash combine(ReactiveVersionHash other) {
		Objects.requireNonNull(other);
		var hasher = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256"));
		hasher.update(toBytes());
		hasher.update(other.toBytes());
		return fromBytes(hasher.digest());
	}
	public ReactiveVersionHash combine(byte[] data) {
		var hasher = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256"));
		hasher.update(toBytes());
		hasher.update(new byte[] { data != null ? (byte)1 : (byte)0 });
		if (data != null)
			hasher.update(data);
		return fromBytes(hasher.digest());
	}
	public ReactiveVersionHash combine(String text) {
		return combine(text != null ? text.getBytes(StandardCharsets.UTF_8) : null);
	}
	public ReactiveVersionHash combine(Object object) {
		return combine(hash(object));
	}
	@Override
	public ReactiveVersionHash toHash() {
		return this;
	}
	@Override
	public boolean equals(Object obj) {
		return obj instanceof ReactiveVersionHash other
			&& word0 == other.word0
			&& word1 == other.word1
			&& word2 == other.word2
			&& word3 == other.word3;
	}
	@Override
	public int hashCode() {
		/*
		 * Here we optimistically assume the hash bits appear to be random.
		 * Explicitly constructed hashes with trivial content would need better hashCode().
		 */
		var sum = word0 ^ word1 ^ word2 ^ word3;
		return (int)(sum | (sum >>> 32));
	}
	@Override
	public String toString() {
		return toBase64();
	}
}
