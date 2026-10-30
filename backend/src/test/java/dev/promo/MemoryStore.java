package dev.promo;

import dev.promo.store.Conflict;
import dev.promo.store.Store;
import dev.promo.store.Store.Check;
import dev.promo.store.Store.Document;
import dev.promo.store.Store.Write;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Models conditional document transactions for component tests. */
public class MemoryStore implements Store {
	private final Map<String, Document> documents = new TreeMap<>();
	private int call;
	private int failAt = -1;

	public synchronized void resetDocuments() {
		documents.clear();
		call = 0;
		failAt = -1;
	}

	public synchronized void failOnAccess(int index) {
		call = 0;
		failAt = index;
	}

	public synchronized int getAccessCount() {
		return call;
	}

	private void recordAccess() {
		if (++call == failAt) {
			throw software.amazon.awssdk.core.exception.SdkClientException.create(
					"Injected storage failure");
		}
	}

	private String key(String partition, String id) {
		return partition + "|" + id;
	}

	@Override
	public synchronized Optional<Document> findDocument(String partition, String id) {
		recordAccess();
		return Optional.ofNullable(documents.get(key(partition, id)));
	}

	@Override
	public synchronized List<Document> listDocuments(String partition) {
		recordAccess();
		return documents.entrySet().stream()
				.filter(e -> e.getKey().startsWith(partition + "|"))
				.map(Map.Entry::getValue)
				.toList();
	}

	@Override
	public synchronized void executeTransaction(List<Write> writes, List<Check> checks) {
		recordAccess();
		for (var write : writes) {
			validateVersion(write.partition(), write.id(), write.expectedVersion());
		}
		for (var check : checks) {
			validateVersion(check.partition(), check.id(), check.expectedVersion());
		}
		for (var write : writes) {
			if (write.json() == null) {
				documents.remove(key(write.partition(), write.id()));
			} else {
				documents.put(
						key(write.partition(), write.id()),
						new Document(write.id(), write.expectedVersion() + 1, write.json()));
			}
		}
	}

	private void validateVersion(String partition, String id, long expected) {
		long actual =
				Optional.ofNullable(documents.get(key(partition, id))).map(Document::version).orElse(0L);
		if (actual != expected) {
			throw new Conflict("Version changed");
		}
	}
}
