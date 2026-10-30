package dev.promo.domain;

import dev.promo.domain.Model.Allocation;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.PromotionDashboard;
import dev.promo.domain.Model.PromotionUsage;
import dev.promo.domain.Model.Purchase;
import dev.promo.domain.Model.Step;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Aggregates promotion costs from immutable completed purchase snapshots. */
public final class PromotionAnalytics {
	private static final class UsageAccumulator {
		String name;
		long purchaseCount;
		long discountedUnits;
		long cost;
		long shippingCost;
		Instant lastUsedAt;

		UsageAccumulator(String name) {
			this.name = name;
		}

		void includeApplication(Step step, Instant purchasedAt) {
			purchaseCount++;
			cost += step.saving();
			discountedUnits +=
					step.allocations().stream()
							.filter(allocation -> !allocation.unitId().equals("shipping"))
							.map(Allocation::unitId)
							.distinct()
							.count();
			shippingCost +=
					step.allocations().stream()
							.filter(allocation -> allocation.unitId().equals("shipping"))
							.mapToLong(Allocation::saving)
							.sum();
			if (lastUsedAt == null || lastUsedAt.isBefore(purchasedAt)) {
				lastUsedAt = purchasedAt;
			}
		}
	}

	public PromotionDashboard createDashboard(
			List<Purchase> purchases, List<Promotion> promotions, Instant now) {
		Map<String, UsageAccumulator> usage = new HashMap<>();
		promotions.forEach(
				promotion -> usage.put(promotion.id(), new UsageAccumulator(promotion.name())));
		long totalDiscount = 0;
		long totalOrderValue = 0;
		for (var purchase : purchases) {
			var order = purchase.order();
			totalDiscount += order.evaluation().savings();
			totalOrderValue += order.evaluation().total();
			var counted = new HashSet<String>();
			for (var step : order.evaluation().steps()) {
				Rules.validateCondition(
						counted.add(step.promotionId()), "Duplicate promotion in purchase snapshot");
				usage
						.computeIfAbsent(step.promotionId(), id -> new UsageAccumulator(step.name()))
						.includeApplication(step, order.createdAt());
			}
		}
		var rows =
				usage.entrySet().stream()
						.map(entry -> createUsageRow(entry.getKey(), entry.getValue()))
						.sorted(
								Comparator.comparingLong(PromotionUsage::cost)
										.reversed()
										.thenComparing(PromotionUsage::name)
										.thenComparing(PromotionUsage::promotionId))
						.toList();
		return new PromotionDashboard(purchases.size(), totalDiscount, totalOrderValue, rows, now);
	}

	private PromotionUsage createUsageRow(String id, UsageAccumulator usage) {
		return new PromotionUsage(
				id,
				usage.name,
				usage.purchaseCount,
				usage.discountedUnits,
				usage.cost,
				usage.shippingCost,
				usage.lastUsedAt);
	}
}
