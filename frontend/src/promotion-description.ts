import type { Promotion } from "./types";
import { formatMoney } from "./api";
export function generatePromotionDescription(p: Promotion): string {
  const d = p.details;
  const style = {
    bogo: "Buy one and get another of equal or lesser value free",
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
  return `${style} ${scope}. ${exclusive}`;
}
