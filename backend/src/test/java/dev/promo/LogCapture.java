package dev.promo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures immutable logging context while request-local state still exists. */
final class LogCapture implements AutoCloseable {
	private final List<Logger> loggers = new ArrayList<>();
	private final ListAppender<ILoggingEvent> appender =
			new ListAppender<>() {
				@Override
				protected void append(ILoggingEvent event) {
					event.prepareForDeferredProcessing();
					super.append(event);
				}
			};

	LogCapture(Class<?>... types) {
		appender.start();
		for (var type : types) {
			var logger = (Logger) LoggerFactory.getLogger(type);
			logger.addAppender(appender);
			loggers.add(logger);
		}
	}

	List<ILoggingEvent> findEvents(String message) {
		return appender.list.stream()
				.filter(event -> event.getFormattedMessage().equals(message))
				.toList();
	}

	List<ILoggingEvent> getEvents() {
		return List.copyOf(appender.list);
	}

	static Object getField(ILoggingEvent event, String name) {
		return event.getKeyValuePairs().stream()
				.filter(field -> field.key.equals(name))
				.findFirst()
				.orElseThrow()
				.value;
	}

	String renderCapturedData() {
		return appender.list.stream()
				.map(
						event ->
								event.getFormattedMessage()
										+ event.getKeyValuePairs()
										+ event.getMDCPropertyMap()
										+ (event.getThrowableProxy() == null
												? ""
												: ThrowableProxyUtil.asString(event.getThrowableProxy())))
				.reduce("", String::concat);
	}

	@Override
	public void close() {
		loggers.forEach(logger -> logger.detachAppender(appender));
		appender.stop();
	}
}
