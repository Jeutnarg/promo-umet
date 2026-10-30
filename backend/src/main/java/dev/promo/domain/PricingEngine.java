package dev.promo.domain;

import static dev.promo.domain.Model.Allocation;
import static dev.promo.domain.Model.Cart;
import static dev.promo.domain.Model.Evaluation;
import static dev.promo.domain.Model.Exclusivity;
import static dev.promo.domain.Model.Line;
import static dev.promo.domain.Model.Promotion;
import static dev.promo.domain.Model.Rejection;
import static dev.promo.domain.Model.Scope;
import static dev.promo.domain.Model.Selection;
import static dev.promo.domain.Model.Step;
import static dev.promo.domain.Model.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Calculates sequential promotion savings and reproducible unit allocations. */
public final class PricingEngine {
	private static final class Unit {
		final String id;
		final String line;
		final long original;
		long remaining;

		Unit(String id, String line, long value) {
			this.id = id;
			this.line = line;
			original = value;
			remaining = value;
		}
	}

	private static final class EvaluationState {
		final Map<String, Unit> units;
		final Map<String, Selection> selections = new HashMap<>();
		final List<Rejection> rejected = new ArrayList<>();
		final List<Step> steps = new ArrayList<>();
		final Set<String> reserved = new HashSet<>();
		final Set<String> touched = new HashSet<>();
		final long originalMerchandise;
		boolean orderExclusive;
		Long goalBasis;

		EvaluationState(Map<String, Unit> units, long originalMerchandise, List<Selection> selections) {
			this.units = units;
			this.originalMerchandise = originalMerchandise;
			selections.forEach(selection -> this.selections.put(selection.promotionId(), selection));
		}
	}

	private record ProposedDiscount(Map<String, Long> deltas, Set<String> affected) {
		long calculateSavings() {
			return deltas.values().stream().mapToLong(Long::longValue).sum();
		}
	}

	public Evaluation evaluateCartPrice(Cart cart, List<Promotion> promotions, Instant now) {
		validateEvaluationInputs(cart, promotions);
		var units = createUnitBalances(cart);
		var state = new EvaluationState(units, calculateMerchandiseSubtotal(units), cart.selections());
		rejectMissingPromotions(state, promotions);
		for (var promotion : sortPromotions(cart, promotions)) {
			var selection = state.selections.get(promotion.id());
			if (selection != null) {
				applySelectedPromotion(cart, promotion, selection, now, state);
			}
		}
		return buildEvaluation(cart, promotions, now, state);
	}

	private void validateEvaluationInputs(Cart cart, List<Promotion> promotions) {
		Rules.validateCart(cart);
		promotions.forEach(Rules::validatePromotion);
		Rules.validateCondition(
				promotions.stream().map(Promotion::id).distinct().count() == promotions.size(),
				"Duplicate promotion ID");
	}

	private Map<String, Unit> createUnitBalances(Cart cart) {
		var units = new LinkedHashMap<String, Unit>();
		for (var line : cart.lines().stream().sorted(Comparator.comparing(Line::id)).toList()) {
			for (int index = 0; index < line.quantity(); index++) {
				String id = line.id() + ":" + String.format(Locale.ROOT, "%04d", index);
				units.put(id, new Unit(id, line.id(), line.price()));
			}
		}
		units.put("shipping", new Unit("shipping", "shipping", cart.shipping()));
		return units;
	}

	private void rejectMissingPromotions(EvaluationState state, List<Promotion> promotions) {
		var known = new HashSet<String>();
		promotions.forEach(promotion -> known.add(promotion.id()));
		state.selections.keySet().stream()
				.filter(id -> !known.contains(id))
				.sorted()
				.forEach(id -> state.rejected.add(new Rejection(id, "Promotion no longer exists")));
	}

	private void applySelectedPromotion(
			Cart cart, Promotion promotion, Selection selection, Instant now, EvaluationState state) {
		String reason = findTimeOrThresholdRejection(promotion, now, state);
		var lines = findEligibleLines(cart, promotion, selection.lineId());
		if (reason == null) {
			reason = findTargetRejection(promotion, lines);
		}
		if (reason != null) {
			state.rejected.add(new Rejection(promotion.id(), reason));
			return;
		}
		ProposedDiscount discount;
		try {
			discount =
					calculateProposedDiscount(
							promotion, selection, findTargetUnits(promotion, lines, state.units), state.units);
		} catch (IllegalArgumentException exception) {
			state.rejected.add(new Rejection(promotion.id(), exception.getMessage()));
			return;
		}
		if (hasExclusivityConflict(promotion, discount, state)) {
			state.rejected.add(new Rejection(promotion.id(), "Conflicts with an exclusive promotion"));
			return;
		}
		if (discount.calculateSavings() == 0) {
			state.rejected.add(new Rejection(promotion.id(), "No positive savings"));
			return;
		}
		commitDiscount(promotion, discount, state);
	}

	private String findTimeOrThresholdRejection(
			Promotion promotion, Instant now, EvaluationState state) {
		if (!promotion.enabled()) {
			return "Promotion disabled";
		}
		if (promotion.expiresAt() != null && !now.isBefore(promotion.expiresAt())) {
			return "Promotion expired";
		}
		if (Rules.isGoalPromotion(promotion)) {
			if (state.goalBasis == null) {
				state.goalBasis = calculateMerchandiseSubtotal(state.units);
			}
			if (state.goalBasis <= promotion.details().threshold()) {
				return "Merchandise subtotal does not exceed threshold";
			}
		}
		return null;
	}

	private String findTargetRejection(Promotion promotion, List<Line> lines) {
		if (lines.isEmpty()) {
			return "No eligible target line";
		}
		if (promotion.strategy() == Strategy.RAINBOW_PERCENT
				&& lines.stream().map(Line::category).distinct().count()
						< promotion.details().categories()) {
			return "Insufficient eligible categories";
		}
		return null;
	}

	private List<Unit> findTargetUnits(
			Promotion promotion, List<Line> lines, Map<String, Unit> units) {
		if (promotion.strategy() == Strategy.SHIPPING_GOAL) {
			return List.of(units.get("shipping"));
		}
		var lineIds = new HashSet<String>();
		lines.forEach(line -> lineIds.add(line.id()));
		var targets =
				new ArrayList<>(
						units.values().stream().filter(unit -> lineIds.contains(unit.line)).toList());
		if (promotion.scope() == Scope.ORDER && promotion.strategy() != Strategy.BOGO) {
			targets.add(units.get("shipping"));
		}
		return targets;
	}

	private ProposedDiscount calculateProposedDiscount(
			Promotion promotion, Selection selection, List<Unit> targets, Map<String, Unit> units) {
		var deltas = new LinkedHashMap<String, Long>();
		var affected = new HashSet<String>();
		if (promotion.strategy() == Strategy.BOGO) {
			for (var pair : resolveBogoPairs(targets, selection.pairs())) {
				var first = units.get(pair.get(0));
				var second = units.get(pair.get(1));
				var free =
						first.remaining < second.remaining
										|| (first.remaining == second.remaining && first.id.compareTo(second.id) < 0)
								? first
								: second;
				deltas.put(free.id, free.remaining);
				affected.add(first.id);
				affected.add(second.id);
			}
		} else {
			targets.forEach(unit -> affected.add(unit.id));
			if (promotion.scope() == Scope.ITEM) {
				for (var target : targets) {
					allocateDiscount(List.of(target), calculateDiscount(promotion, target.remaining), deltas);
				}
			} else {
				allocateDiscount(
						targets,
						calculateDiscount(promotion, targets.stream().mapToLong(unit -> unit.remaining).sum()),
						deltas);
			}
		}
		return new ProposedDiscount(deltas, affected);
	}

	private boolean hasExclusivityConflict(
			Promotion promotion, ProposedDiscount discount, EvaluationState state) {
		return state.orderExclusive
				|| (promotion.exclusivity() == Exclusivity.ORDER && !state.steps.isEmpty())
				|| !Collections.disjoint(discount.affected(), state.reserved)
				|| (promotion.exclusivity() == Exclusivity.UNITS
						&& !Collections.disjoint(discount.affected(), state.touched));
	}

	private void commitDiscount(
			Promotion promotion, ProposedDiscount discount, EvaluationState state) {
		var allocations = new ArrayList<Allocation>();
		discount
				.deltas()
				.forEach(
						(id, amount) -> {
							if (amount > 0) {
								state.units.get(id).remaining -= amount;
								allocations.add(new Allocation(id, state.units.get(id).line, amount));
							}
						});
		state.steps.add(
				new Step(
						promotion.id(),
						promotion.name(),
						discount.calculateSavings(),
						List.copyOf(allocations)));
		state.touched.addAll(discount.affected());
		if (promotion.exclusivity() == Exclusivity.UNITS) {
			state.reserved.addAll(discount.affected());
		}
		state.orderExclusive = promotion.exclusivity() == Exclusivity.ORDER;
	}

	private Evaluation buildEvaluation(
			Cart cart, List<Promotion> promotions, Instant now, EvaluationState state) {
		long merchandise = calculateMerchandiseSubtotal(state.units);
		long shipping = state.units.get("shipping").remaining;
		var covered =
				state.units.values().stream()
						.filter(unit -> !unit.id.equals("shipping") && unit.remaining < unit.original)
						.map(unit -> unit.id)
						.toList();
		var uncovered =
				state.units.values().stream()
						.filter(unit -> !unit.id.equals("shipping") && unit.remaining == unit.original)
						.map(unit -> unit.id)
						.toList();
		Instant next =
				promotions.stream()
						.filter(Promotion::enabled)
						.map(Promotion::expiresAt)
						.filter(Objects::nonNull)
						.filter(now::isBefore)
						.min(Comparator.naturalOrder())
						.orElse(null);
		return new Evaluation(
				cart.version(),
				calculatePromotionRevision(promotions),
				state.originalMerchandise,
				cart.shipping(),
				merchandise,
				shipping,
				state.originalMerchandise + cart.shipping() - merchandise - shipping,
				merchandise + shipping,
				List.copyOf(state.steps),
				List.copyOf(state.rejected),
				covered,
				uncovered,
				next);
	}

	public List<Promotion> sortPromotions(Cart cart, List<Promotion> promotions) {
		return promotions.stream()
				.sorted(
						Comparator.comparing(Rules::isGoalPromotion)
								.thenComparing(Promotion::scope)
								.thenComparingLong(
										p ->
												cart.lines().stream()
														.filter(line -> Rules.isEligibleLine(p, line))
														.map(Line::itemId)
														.distinct()
														.count())
								.thenComparingInt(
										p ->
												switch (p.strategy()) {
													case PERCENT, RAINBOW_PERCENT -> 1;
													case FIXED_PRICE -> 2;
													default -> 0;
												})
								.thenComparing(Promotion::id))
				.toList();
	}

	public List<Line> findEligibleLines(Cart cart, Promotion promotion, String lineId) {
		var eligible =
				cart.lines().stream()
						.filter(line -> Rules.isEligibleLine(promotion, line))
						.sorted(Comparator.comparing(Line::id))
						.toList();
		if (promotion.scope() == Scope.ONE_ITEM || promotion.scope() == Scope.ITEM) {
			if (lineId == null) {
				return eligible.stream().limit(1).toList();
			}
			return eligible.stream().filter(line -> line.id().equals(lineId)).toList();
		}
		return eligible;
	}

	public String calculatePromotionRevision(List<Promotion> promotions) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			promotions.stream()
					.sorted(Comparator.comparing(Promotion::id))
					.forEach(p -> digest.update(p.toString().getBytes(StandardCharsets.UTF_8)));
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private long calculateMerchandiseSubtotal(Map<String, Unit> units) {
		return units.values().stream()
				.filter(unit -> !unit.id.equals("shipping"))
				.mapToLong(unit -> unit.remaining)
				.sum();
	}

	private long calculateDiscount(Promotion promotion, long balance) {
		return Math.min(
				balance,
				switch (promotion.strategy()) {
					case FLAT, FLAT_GOAL -> promotion.details().amount();
					case FIXED_PRICE -> Math.max(0, balance - promotion.details().amount());
					case SHIPPING_GOAL -> balance;
					case PERCENT, RAINBOW_PERCENT ->
							BigDecimal.valueOf(balance)
									.multiply(BigDecimal.valueOf(promotion.details().basisPoints()))
									.divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
									.longValueExact();
					default -> throw new IllegalArgumentException("Unsupported strategy");
				});
	}

	private void allocateDiscount(List<Unit> targets, long saving, Map<String, Long> deltas) {
		long total = targets.stream().mapToLong(unit -> unit.remaining).sum();
		if (saving == 0 || total == 0) {
			return;
		}
		var remainders = new HashMap<String, BigDecimal>();
		long assigned = 0;
		for (var unit : targets) {
			var numerator = BigDecimal.valueOf(saving).multiply(BigDecimal.valueOf(unit.remaining));
			var parts = numerator.divideAndRemainder(BigDecimal.valueOf(total));
			long amount = parts[0].longValueExact();
			deltas.put(unit.id, amount);
			assigned += amount;
			remainders.put(unit.id, parts[1]);
		}
		var order =
				targets.stream()
						.sorted(
								Comparator.<Unit, BigDecimal>comparing(unit -> remainders.get(unit.id))
										.reversed()
										.thenComparing(unit -> unit.id))
						.toList();
		for (int i = 0; i < saving - assigned; i++) {
			deltas.merge(order.get(i).id, 1L, Long::sum);
		}
	}

	private List<List<String>> resolveBogoPairs(List<Unit> units, List<List<String>> requested) {
		if (requested == null || requested.isEmpty()) {
			var sorted =
					units.stream()
							.sorted(
									Comparator.<Unit>comparingLong(unit -> unit.remaining)
											.reversed()
											.thenComparing(unit -> unit.id))
							.toList();
			var result = new ArrayList<List<String>>();
			for (int i = 0; i + 1 < sorted.size(); i += 2) {
				result.add(List.of(sorted.get(i).id, sorted.get(i + 1).id));
			}
			return result;
		}
		var allowed = new HashSet<String>();
		units.forEach(unit -> allowed.add(unit.id));
		var used = new HashSet<String>();
		Rules.validateCondition(
				requested.size() == units.size() / 2, "BOGO requires the maximum number of pairs");
		for (var pair : requested) {
			Rules.validateCondition(
					pair != null && pair.size() == 2, "Each BOGO pair requires two units");
			for (var id : pair) {
				Rules.validateCondition(
						allowed.contains(id) && used.add(id), "BOGO contains an invalid or repeated unit");
			}
		}
		return requested;
	}
}
