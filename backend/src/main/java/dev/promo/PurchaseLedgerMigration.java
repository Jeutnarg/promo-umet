package dev.promo;

import dev.promo.api.SandboxService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Indexes existing purchase snapshots once before normal application initialization. */
@Component
@Profile("!test")
@Order(0)
public class PurchaseLedgerMigration implements ApplicationRunner {
	private final SandboxService service;

	public PurchaseLedgerMigration(SandboxService service) {
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		service.migrateLegacyPurchases();
	}
}
