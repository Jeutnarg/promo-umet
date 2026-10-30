package dev.promo.domain;

import static dev.promo.domain.Model.Cart;
import static dev.promo.domain.Model.Item;
import static dev.promo.domain.Model.Line;
import static dev.promo.domain.Model.Promotion;
import static dev.promo.domain.Model.Scope;
import static dev.promo.domain.Model.Strategy;
import static dev.promo.domain.Model.TestSet;

import java.util.HashSet;
import java.util.List;

/** Validates sandbox inputs and promotion eligibility predicates. */
public final class Rules {
	private Rules() {}

	public static void validateCondition(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}

	public static void validateText(String value, String label) {
		validateCondition(
				value != null && !value.isBlank() && value.length() <= 200,
				label + " must contain 1–200 characters");
	}

	public static void validateMoney(long amount) {
		validateCondition(
				amount >= 0 && amount <= 100_000_000, "Amount must be between 0 and 100000000 cents");
	}

	public static void validateItem(Item item) {
		validateCondition(item != null, "Item is required");
		validateText(item.id(), "Item ID");
		validateText(item.name(), "Name");
		validateText(item.category(), "Category");
		validateMoney(item.price());
	}

	public static void validateCart(Cart cart) {
		validateCondition(
				cart != null && cart.lines() != null && cart.selections() != null,
				"Cart lines and selections are required");
		validateCondition(
				cart.lines().size() <= 100 && cart.selections().size() <= 50,
				"Maximum 100 lines and 50 selections");
		validateMoney(cart.shipping());
		var ids = new HashSet<String>();
		int units = 0;
		for (var line : cart.lines()) {
			validateCondition(line != null, "Null cart line");
			validateText(line.id(), "Line ID");
			validateText(line.itemId(), "Item ID");
			validateText(line.name(), "Name");
			validateText(line.category(), "Category");
			validateCondition(
					!line.id().equals("shipping") && !line.id().contains(":"), "Reserved line ID");
			validateCondition(ids.add(line.id()), "Duplicate line ID");
			validateMoney(line.price());
			validateCondition(line.quantity() > 0 && line.quantity() <= 1000, "Quantity must be 1–1000");
			units += line.quantity();
		}
		validateCondition(units <= 1000, "Maximum 1000 units per cart");
		ids.clear();
		for (var selection : cart.selections()) {
			validateCondition(selection != null, "Null selection");
			validateText(selection.promotionId(), "Promotion ID");
			validateCondition(ids.add(selection.promotionId()), "Duplicate promotion selection");
			validateCondition(
					selection.pairs() == null || selection.pairs().size() <= 500, "Too many BOGO pairs");
		}
	}

	public static void validatePromotion(Promotion promotion) {
		validateCondition(promotion != null, "Promotion is required");
		validateText(promotion.id(), "Promotion ID");
		validateText(promotion.name(), "Name");
		validateCondition(
				promotion.description() == null || promotion.description().length() <= 2000,
				"Description must be at most 2000 characters");
		validateCondition(promotion.schemaVersion() == 1, "Unsupported promotion schema");
		validateCondition(
				promotion.scope() != null
						&& promotion.strategy() != null
						&& promotion.exclusivity() != null,
				"Scope, strategy and exclusivity are required");
		validateCondition(
				promotion.itemIds() != null && promotion.categories() != null, "Target lists are required");
		validateCondition(
				promotion.itemIds().size() <= 100 && promotion.categories().size() <= 100,
				"Too many targets");
		promotion.itemIds().forEach(id -> validateText(id, "Target item"));
		promotion.categories().forEach(id -> validateText(id, "Target category"));
		var details = promotion.details();
		validateCondition(details != null, "Details are required");
		switch (promotion.strategy()) {
			case FLAT, FIXED_PRICE, FLAT_GOAL -> {
				validateCondition(details.amount() != null, "Amount is required");
				validateMoney(details.amount());
			}
			case PERCENT, RAINBOW_PERCENT ->
					validateCondition(
							details.basisPoints() != null
									&& details.basisPoints() > 0
									&& details.basisPoints() <= 10000,
							"Percentage must be > 0 and <= 100%");
			default -> {}
		}
		if (isGoalPromotion(promotion)) {
			validateCondition(details.threshold() != null, "Threshold required");
			validateMoney(details.threshold());
		}
		if (promotion.strategy() == Strategy.RAINBOW_PERCENT) {
			validateCondition(
					details.categories() != null && details.categories() > 0,
					"Category count must be positive");
		}
		if (promotion.strategy() == Strategy.SHIPPING_GOAL) {
			validateCondition(promotion.scope() == Scope.ORDER, "Shipping goal requires order scope");
		}
	}

	public static boolean isGoalPromotion(Promotion promotion) {
		return promotion.strategy() == Strategy.FLAT_GOAL
				|| promotion.strategy() == Strategy.SHIPPING_GOAL;
	}

	public static boolean isEligibleLine(Promotion promotion, Line line) {
		return (promotion.itemIds().isEmpty() || promotion.itemIds().contains(line.itemId()))
				&& (promotion.categories().isEmpty() || promotion.categories().contains(line.category()));
	}

	public static void validateTestSet(TestSet set) {
		validateCondition(set != null, "Test set is required");
		validateText(set.name(), "Test set name");
		validateCondition(
				set.schemaVersion() == 1 && set.cases() != null && set.cases().size() <= 100,
				"Test set schema must be 1 with at most 100 cases");
		for (var test : set.cases()) {
			validateCondition(test != null, "Null test case");
			validateText(test.name(), "Case name");
			validateCart(test.cart());
			validateCondition(
					test.promotions() != null && test.promotions().size() <= 50,
					"At most 50 fixture promotions");
			test.promotions().forEach(Rules::validatePromotion);
			validateCondition(test.now() != null, "Fixed evaluation time required");
			validateCondition(
					List.of("manual", "suggest", "swindle").contains(test.mode()), "Unknown evaluation mode");
		}
	}
}
