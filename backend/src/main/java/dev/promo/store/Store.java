package dev.promo.store;

import java.util.List;
import java.util.Optional;

/** Provides strongly consistent document reads and atomic conditional writes. */
public interface Store {
	record Document(String id, long version, String json) {}

	record Write(String partition, String id, long expectedVersion, String json) {}

	record Check(String partition, String id, long expectedVersion) {}

	record LegacyOrderDocument(String owner, Document document) {}

	Optional<Document> findDocument(String partition, String id);

	List<Document> listDocuments(String partition);

	/** Reads legacy owner-scoped orders only for the one-time purchase-ledger migration. */
	List<LegacyOrderDocument> scanLegacyOrders();

	void executeTransaction(List<Write> writes, List<Check> checks);
}
