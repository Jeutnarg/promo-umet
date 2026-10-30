package dev.promo.store;

/** Signals an optimistic version conflict or a stale confirmation. */
public class Conflict extends RuntimeException {
	public Conflict(String message) {
		super(message);
	}
}
