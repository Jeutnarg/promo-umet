package dev.promo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Allows test registries to release their resources with try-with-resources. */
final class TestMeterRegistry extends SimpleMeterRegistry implements AutoCloseable {}
