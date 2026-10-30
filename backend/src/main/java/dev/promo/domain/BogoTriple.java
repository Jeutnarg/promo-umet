package dev.promo.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Builds and validates disjoint buy-one/get-two groups using current merchandise balances. */
public final class BogoTriple {
	private BogoTriple() {}

	public record Balance(String id, long remaining) {}

	private static final Comparator<Balance> BALANCE_ORDER =
			Comparator.comparingLong(Balance::remaining).thenComparing(Balance::id);

	public static boolean canFormGroup(List<Balance> balances) {
		var sorted = balances.stream().sorted(BALANCE_ORDER).toList();
		return sorted.size() >= 3
				&& sorted.get(0).remaining() + sorted.get(1).remaining() < sorted.getLast().remaining();
	}

	public static boolean qualifies(Balance first, Balance second, Balance third) {
		long largest = Math.max(first.remaining(), Math.max(second.remaining(), third.remaining()));
		return first.remaining() + second.remaining() + third.remaining() - largest < largest;
	}

	public static List<List<String>> resolveGroups(
			List<Balance> balances, List<List<String>> requested) {
		if (requested == null || requested.isEmpty()) {
			return createDefaultGroups(balances);
		}
		var available = new HashMap<String, Balance>();
		balances.forEach(balance -> available.put(balance.id(), balance));
		var used = new HashSet<String>();
		for (var group : requested) {
			Rules.validateCondition(
					group != null && group.size() == 3, "Each BOGO triple requires three units");
			for (var id : group) {
				Rules.validateCondition(
						available.containsKey(id) && used.add(id),
						"BOGO triple contains an invalid or repeated unit");
			}
			Rules.validateCondition(
					qualifies(
							available.get(group.get(0)),
							available.get(group.get(1)),
							available.get(group.get(2))),
					"The two add-ons must cost strictly less than the qualifying item after earlier"
							+ " discounts");
		}
		Rules.validateCondition(
				!canFormGroup(balances.stream().filter(balance -> !used.contains(balance.id())).toList()),
				"BOGO triple must include another qualifying group of unused units");
		return requested;
	}

	/**
	 * Takes the largest sponsor and the highest-value qualifying pair, then repeats to exhaustion.
	 */
	private static List<List<String>> createDefaultGroups(List<Balance> balances) {
		var remaining = new ArrayList<>(balances.stream().sorted(BALANCE_ORDER).toList());
		var groups = new ArrayList<List<String>>();
		while (remaining.size() >= 3) {
			var sponsor = remaining.getLast();
			int left = 0;
			int right = remaining.size() - 2;
			int bestLeft = -1;
			int bestRight = -1;
			long bestSaving = -1;
			while (left < right) {
				long saving = remaining.get(left).remaining() + remaining.get(right).remaining();
				if (saving < sponsor.remaining()) {
					if (saving > bestSaving) {
						bestSaving = saving;
						bestLeft = left;
						bestRight = right;
					}
					left++;
				} else {
					right--;
				}
			}
			if (bestLeft < 0) {
				break;
			}
			groups.add(
					List.of(sponsor.id(), remaining.get(bestLeft).id(), remaining.get(bestRight).id()));
			remaining.removeLast();
			remaining.remove(bestRight);
			remaining.remove(bestLeft);
		}
		return List.copyOf(groups);
	}
}
