import type { Promotion, ItemView, Line } from "./types";
import { formatMoney } from "./api";
export function generatePromotionDescription(p: Promotion): string {
  const d = p.details;
  const style = {
    bogo: "Buy one and get another of equal or lesser value free",
    bogotriple:
      "Buy one and get two add-ons free when their combined price is strictly less than the qualifying item's price after earlier discounts",
    flat: `Save ${formatMoney(d.amount || 0)}`,
    percent: `Save ${(d.basisPoints || 0) / 100}%`,
    fixedprice: `Reduce the price to ${formatMoney(d.amount || 0)}`,
    flatgoal: `Save ${formatMoney(d.amount || 0)} when discounted merchandise exceeds ${formatMoney(d.threshold || 0)}`,
    shippinggoal: `Get free shipping when discounted merchandise exceeds ${formatMoney(d.threshold || 0)}`,
    rainbowpercent: `Save ${(d.basisPoints || 0) / 100}% with at least ${d.categories || 1} eligible categories`,
  }[p.strategy];
  const scope = {
    oneitem: "on the total for one selected item",
    item: "on each unit of one selected item",
    items: "across eligible merchandise",
    order: "across your order, including shipping",
  }[p.scope];
  const exclusive = {
    stackable: "Can be combined with other compatible offers.",
    units: "Cannot share affected units with another offer.",
    order: "Must be used alone on this order.",
  }[p.exclusivity];
  const merchandiseOnly = p.strategy === "bogo" || p.strategy === "bogotriple";
  return `${style} ${merchandiseOnly && p.scope === "order" ? "across eligible merchandise (shipping excluded)" : scope}. ${exclusive}`;
}

/** Always show current targeting independently of the administrator's editable description. */
export function describePromotionTargets(
  p: Promotion,
  catalog: ItemView[],
  lines: Line[],
): string {
  const names = new Map(catalog.map(({ item }) => [item.id, item.name]));
  lines.forEach((line) => {
    if (!names.has(line.itemId)) names.set(line.itemId, line.name);
  });
  const categories = p.categories.length
    ? `Eligible categories: ${p.categories.join(", ")}.`
    : "Eligible categories: all categories.";
  const matchingNames = [
    ...new Set(
      catalog
        .filter(
          ({ item }) =>
            !p.categories.length || p.categories.includes(item.category),
        )
        .map(({ item }) => item.name),
    ),
  ];
  const items = p.itemIds.length
    ? `Eligible items: ${p.itemIds.map((id) => names.get(id) || "Item unavailable in your catalog").join(", ")}.`
    : `Eligible items: ${p.categories.length ? "any item in the eligible categories" : "all items"}.${matchingNames.length ? ` In your catalog: ${matchingNames.join(", ")}.` : " No matching items in your catalog."}`;
  return `${categories} ${items}${p.categories.length && p.itemIds.length ? " Items must match both lists." : ""}`;
}
