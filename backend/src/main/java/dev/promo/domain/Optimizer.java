package dev.promo.domain;

import static dev.promo.domain.Model.Cart;
import static dev.promo.domain.Model.Evaluation;
import static dev.promo.domain.Model.Line;
import static dev.promo.domain.Model.OptimizationResult;
import static dev.promo.domain.Model.Promotion;
import static dev.promo.domain.Model.Scope;
import static dev.promo.domain.Model.Selection;
import static dev.promo.domain.Model.Strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searches promotion selections and pairings within a bounded time budget. */
public final class Optimizer {
	private final PricingEngine engine;
	private final PricingObserver observer;

	public Optimizer(PricingEngine engine) {
		this(engine, PricingObserver.NONE);
	}

	public Optimizer(PricingEngine engine, PricingObserver observer) {
		this.engine = engine;
		this.observer = observer;
	}

	public OptimizationResult findOptimalPromotions(
			Cart cart, List<Promotion> promotions, Instant now, String mode, Duration budget) {
		long started = System.nanoTime();
		try {
			var result = searchOptimalPromotions(cart, promotions, now, mode, budget);
			observer.optimizationCompleted(
					cart, promotions.size(), result, budget, System.nanoTime() - started);
			return result;
		} catch (RuntimeException failure) {
			observer.calculationFailed("optimization", failure, System.nanoTime() - started);
			throw failure;
		}
	}

	private OptimizationResult searchOptimalPromotions(
			Cart cart, List<Promotion> promotions, Instant now, String mode, Duration budget) {
		Rules.validateCondition(
				List.of("suggest", "swindle").contains(mode), "Mode must be suggest or swindle");
		Rules.validateCondition(
				promotions.size() <= 50, "At most 50 active promotions may be optimized");
		var search = new Search(cart, engine.sortPromotions(cart, promotions), now, mode, budget);
		search.searchPromotionSelections(0, new ArrayList<>());
		return new OptimizationResult(
				search.bestSelections,
				search.best,
				!search.timedOut,
				!search.timedOut || search.best.uncoveredUnits().isEmpty(),
				search.candidates,
				mode);
	}

	private final class Search {
		final Cart cart;
		final List<Promotion> promotions;
		final Instant now;
		final String mode;
		final long deadline;
		Evaluation best;
		List<Selection> bestSelections = List.of();
		long candidates;
		boolean timedOut;

		Search(Cart cart, List<Promotion> promotions, Instant now, String mode, Duration budget) {
			this.cart = cart;
			this.promotions = promotions;
			this.now = now;
			this.mode = mode;
			deadline = System.nanoTime() + budget.toNanos();
			best = evaluateSelections(List.of());
		}

		boolean hasSearchExpired() {
			if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
				timedOut = true;
			}
			return timedOut;
		}

		Evaluation evaluateSelections(List<Selection> selections) {
			candidates++;
			return engine.evaluateCandidatePrice(
					new Cart(
							cart.id(),
							cart.version(),
							cart.lines(),
							cart.shipping(),
							List.copyOf(selections),
							cart.completed()),
					promotions,
					now);
		}

		void searchPromotionSelections(int index, ArrayList<Selection> selections) {
			if (hasSearchExpired()) {
				return;
			}
			var result = evaluateSelections(selections);
			if (!result.rejected().isEmpty()) {
				return;
			}
			if (isBetterCandidate(result, selections)) {
				best = result;
				bestSelections = List.copyOf(selections);
			}
			if (index == promotions.size()) {
				return;
			}
			var promotion = promotions.get(index);
			if (promotion.enabled()
					&& (promotion.expiresAt() == null || now.isBefore(promotion.expiresAt()))) {
				var targets =
						promotion.scope() == Scope.ITEM || promotion.scope() == Scope.ONE_ITEM
								? engine
										.findEligibleLines(
												new Cart(
														cart.id(),
														cart.version(),
														cart.lines(),
														cart.shipping(),
														List.of(),
														false),
												new Promotion(
														promotion.id(),
														promotion.version(),
														promotion.name(),
														Scope.ITEMS,
														promotion.exclusivity(),
														promotion.strategy(),
														promotion.details(),
														promotion.itemIds(),
														promotion.categories(),
														promotion.expiresAt(),
														promotion.enabled(),
														promotion.schemaVersion()),
												null)
										.stream()
										.map(Line::id)
										.toList()
								: Collections.<String>singletonList(null);
				for (var target : targets) {
					if (hasSearchExpired()) {
						return;
					}
					Consumer<List<List<String>>> apply =
							pairs -> {
								selections.add(new Selection(promotion.id(), target, pairs));
								searchPromotionSelections(index + 1, selections);
								selections.removeLast();
							};
					if (promotion.strategy() == Strategy.BOGO) {
						var ids = new ArrayList<String>();
						for (var line : engine.findEligibleLines(cart, promotion, target)) {
							for (int i = 0; i < line.quantity(); i++) {
								ids.add(line.id() + ":" + String.format(Locale.ROOT, "%04d", i));
							}
						}
						enumerateBogoPairings(ids, new ArrayList<>(), ids.size() % 2 == 1, apply);
					} else {
						apply.accept(List.of());
					}
				}
			}
			searchPromotionSelections(index + 1, selections);
		}

		void enumerateBogoPairings(
				List<String> remaining,
				ArrayList<List<String>> pairs,
				boolean maySkip,
				Consumer<List<List<String>>> consume) {
			if (hasSearchExpired()) {
				return;
			}
			if (remaining.size() < 2) {
				consume.accept(List.copyOf(pairs));
				return;
			}
			var first = remaining.getFirst();
			for (int i = 1; i < remaining.size(); i++) {
				if (hasSearchExpired()) {
					return;
				}
				var rest = new ArrayList<>(remaining);
				String second = rest.remove(i);
				rest.removeFirst();
				pairs.add(List.of(first, second));
				enumerateBogoPairings(rest, pairs, maySkip, consume);
				pairs.removeLast();
			}
			if (maySkip) {
				enumerateBogoPairings(
						new ArrayList<>(remaining.subList(1, remaining.size())), pairs, false, consume);
			}
		}

		boolean isBetterCandidate(Evaluation result, List<Selection> selections) {
			if (mode.equals("swindle")) {
				int coverage = Integer.compare(result.coveredUnits().size(), best.coveredUnits().size());
				if (coverage != 0) {
					return coverage > 0;
				}
				if (result.savings() != best.savings()) {
					return result.savings() < best.savings();
				}
			} else if (result.total() != best.total()) {
				return result.total() < best.total();
			}
			return selections.toString().compareTo(bestSelections.toString()) < 0;
		}
	}
}
