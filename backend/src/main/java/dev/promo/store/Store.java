package dev.promo.store;

import java.util.List;
import java.util.Optional;

/** Provides strongly consistent document reads and atomic conditional writes. */
public interface Store {
	record Document(String id, long version, String json) {}

	record Write(String partition, String id, long expectedVersion, String json) {}

	record Check(String partition, String id, long expectedVersion) {}

	Optional<Document> findDocument(String partition, String id);

	List<Document> listDocuments(String partition);

	void executeTransaction(List<Write> writes, List<Check> checks);
}
