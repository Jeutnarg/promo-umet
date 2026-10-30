package dev.promo.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Defines immutable pricing inputs, calculation traces, and persisted snapshots. */
public final class Model {
	private Model() {}

	/** Defines the merchandise or order balance affected by a promotion. */
	public enum Scope {
		@JsonProperty("oneitem")
		ONE_ITEM,
		@JsonProperty("item")
		ITEM,
		@JsonProperty("items")
		ITEMS,
		@JsonProperty("order")
		ORDER
	}

	/** Identifies a supported typed discount calculation. */
	public enum Strategy {
		@JsonProperty("bogo")
		BOGO,
		@JsonProperty("flat")
		FLAT,
		@JsonProperty("percent")
		PERCENT,
		@JsonProperty("fixedprice")
		FIXED_PRICE,
		@JsonProperty("flatgoal")
		FLAT_GOAL,
		@JsonProperty("shippinggoal")
		SHIPPING_GOAL,
		@JsonProperty("rainbowpercent")
		RAINBOW_PERCENT
	}

	/** Defines which other promotion applications may coexist. */
	public enum Exclusivity {
		@JsonProperty("stackable")
		STACKABLE,
		@JsonProperty("units")
		UNITS,
		@JsonProperty("order")
		ORDER
	}

	/** Defines a reusable catalog item with a unit price in cents. */
	public record Item(String id, String name, long price, String category) {}

	/** Snapshots a catalog item and its quantity in a cart. */
	public record Line(
			String id, String itemId, String name, int quantity, long price, String category) {}

	/** Selects a promotion, optional target line, and reproducible BOGO pairs. */
	public record Selection(String promotionId, String lineId, List<List<String>> pairs) {
		public Selection(String id) {
			this(id, null, List.of());
		}
	}

	/** Snapshots cart contents, selected promotions, and the last save instant. */
	public record Cart(
			String id,
			long version,
			List<Line> lines,
			long shipping,
			List<Selection> selections,
			boolean completed,
			Instant savedAt) {
		public Cart(
				String id,
				long version,
				List<Line> lines,
				long shipping,
				List<Selection> selections,
				boolean completed) {
			this(id, version, lines, shipping, selections, completed, Instant.now());
		}
	}

	/** Carries strategy-specific amounts in cents and percentages in basis points. */
	public record Details(Long amount, Integer basisPoints, Long threshold, Integer categories) {}

	/** Defines targeting, stacking restrictions, strategy parameters, and expiration. */
	public record Promotion(
			String id,
			long version,
			String name,
			Scope scope,
			Exclusivity exclusivity,
			Strategy strategy,
			Details details,
			List<String> itemIds,
			List<String> categories,
			Instant expiresAt,
			boolean enabled,
			int schemaVersion,
			String description) {
		public Promotion(
				String id,
				long version,
				String name,
				Scope scope,
				Exclusivity exclusivity,
				Strategy strategy,
				Details details,
				List<String> itemIds,
				List<String> categories,
				Instant expiresAt,
				boolean enabled,
				int schemaVersion) {
			this(
					id,
					version,
					name,
					scope,
					exclusivity,
					strategy,
					details,
					itemIds,
					categories,
					expiresAt,
					enabled,
					schemaVersion,
					null);
		}
	}

	/** Assigns a positive saving in cents to a merchandise unit or shipping. */
	public record Allocation(String unitId, String lineId, long saving) {}

	/** Records one applied promotion and its allocated savings. */
	public record Step(String promotionId, String name, long saving, List<Allocation> allocations) {}

	/** Explains why a selected promotion did not apply. */
	public record Rejection(String promotionId, String reason) {}

	/** Reports versioned pricing, application steps, and positive-saving coverage. */
	public record Evaluation(
			long cartVersion,
			String promotionRevision,
			long originalMerchandise,
			long originalShipping,
			long merchandise,
			long shipping,
			long savings,
			long total,
			List<Step> steps,
			List<Rejection> rejected,
			List<String> coveredUnits,
			List<String> uncoveredUnits,
			Instant nextExpiration) {}

	/** Returns selected promotions with search completion and coverage guarantees. */
	public record OptimizationResult(
			List<Selection> selections,
			Evaluation evaluation,
			boolean optimalityProven,
			boolean coverageProven,
			long candidates,
			String mode) {}

	/** Preserves a time-limited confirmation snapshot for order revalidation. */
	public record Quote(
			String id, Cart cart, List<Promotion> promotions, Evaluation evaluation, Instant expiresAt) {}

	/** Preserves an immutable submitted pricing snapshot. */
	public record Order(
			String id,
			String quoteId,
			Cart cart,
			List<Promotion> promotions,
			Evaluation evaluation,
			Instant createdAt) {}

	/** Defines a deterministic pricing scenario and its expected results. */
	public record TestCase(
			String name,
			Cart cart,
			List<Promotion> promotions,
			String mode,
			Instant now,
			long expectedTotal,
			Integer expectedCoveredUnits) {}

	/** Groups versioned administrator pricing scenarios. */
	public record TestSet(
			String id, long version, int schemaVersion, String name, List<TestCase> cases) {}

	/** Reports one scenario result and any evaluation failure. */
	public record TestResult(
			String name, boolean passed, Evaluation evaluation, String error, Boolean optimalityProven) {}

	/** Records an administrator action and its actor. */
	public record Audit(String actor, String action, String entityId, Instant at) {}
}
