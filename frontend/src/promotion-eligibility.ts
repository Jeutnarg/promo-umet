import type { Cart, Evaluation, ItemView, Line, Promotion } from "./types";

export type Addition = { item: ItemView["item"]; quantity: number };
export function isEligibleItem(
  p: Promotion,
  item: { id: string; category: string },
): boolean {
  return (
    (!p.itemIds.length || p.itemIds.includes(item.id)) &&
    (!p.categories.length || p.categories.includes(item.category))
  );
}
const isGoal = (p: Promotion) =>
  p.strategy === "flatgoal" || p.strategy === "shippinggoal";

function comparePromotionOrder(a: Promotion, b: Promotion, cart: Cart): number {
  const scopes = ["oneitem", "item", "items", "order"];
  const count = (p: Promotion) =>
    new Set(
      cart.lines
        .filter((line) =>
          isEligibleItem(p, { id: line.itemId, category: line.category }),
        )
        .map((line) => line.itemId),
    ).size;
  const style = (p: Promotion) =>
    ["percent", "rainbowpercent"].includes(p.strategy)
      ? 1
      : p.strategy === "fixedprice"
        ? 2
        : 0;
  return (
    Number(isGoal(a)) - Number(isGoal(b)) ||
    scopes.indexOf(a.scope) - scopes.indexOf(b.scope) ||
    count(a) - count(b) ||
    style(a) - style(b) ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
  );
}

/** Advisory UI eligibility; the server remains authoritative for conflicts, prices and checkout. */
export function canPromotionApply(
  p: Promotion,
  cart: Cart,
  evaluation: Evaluation,
  promotions: Promotion[],
): boolean {
  if (!p.enabled || (p.expiresAt && Date.parse(p.expiresAt) <= Date.now()))
    return false;
  const known = new Map(
    promotions.map((promotion) => [promotion.id, promotion]),
  );
  const savings = new Map<string, number>();
  let goalBasis = cart.lines.reduce(
    (sum, line) => sum + line.price * line.quantity,
    0,
  );
  let shipping = cart.shipping;
  for (const step of evaluation.steps) {
    const earlier = known.get(step.promotionId);
    if (!earlier || comparePromotionOrder(earlier, p, cart) >= 0) continue;
    for (const allocation of step.allocations) {
      savings.set(
        allocation.unitId,
        (savings.get(allocation.unitId) || 0) + allocation.saving,
      );
      if (allocation.unitId === "shipping") shipping -= allocation.saving;
      else if (!isGoal(earlier)) goalBasis -= allocation.saving;
    }
  }
  if (isGoal(p) && goalBasis <= (p.details.threshold || 0)) return false;
  const lines = cart.lines.filter((line) =>
    isEligibleItem(p, { id: line.itemId, category: line.category }),
  );
  if (!lines.length) return false;
  if (
    p.strategy === "rainbowpercent" &&
    (p.scope === "item" || p.scope === "oneitem") &&
    (p.details.categories || 1) > 1
  )
    return false;
  if (
    p.strategy === "rainbowpercent" &&
    new Set(lines.map((line) => line.category)).size <
      (p.details.categories || 1)
  )
    return false;
  if (p.strategy === "shippinggoal") return shipping > 0;
  const balances = (line: Line) =>
    Array.from({ length: line.quantity }, (_, unit) =>
      Math.max(
        0,
        line.price -
          (savings.get(`${line.id}:${String(unit).padStart(4, "0")}`) || 0),
      ),
    );
  const groups =
    p.scope === "item" || p.scope === "oneitem"
      ? lines.map((line) => balances(line))
      : [lines.flatMap(balances)];
  return groups.some((group) => {
    if (p.strategy === "bogo")
      return group.filter((value) => value > 0).length >= 2;
    if (p.strategy === "bogotriple") {
      const sorted = [...group].sort((a, b) => a - b);
      // Two zero-priced add-ons cannot produce a saving. Find the cheapest positive pair.
      const positive = sorted.findIndex((value) => value > 0);
      const pair = sorted[0] + sorted[Math.max(1, positive)];
      return (
        sorted.length >= 3 &&
        positive >= 0 &&
        pair > 0 &&
        pair < sorted[sorted.length - 1]
      );
    }
    const total =
      group.reduce((sum, value) => sum + value, 0) +
      (p.scope === "order" ? shipping : 0);
    if (p.strategy === "fixedprice")
      return p.scope === "item"
        ? group.some((value) => value > (p.details.amount || 0))
        : total > (p.details.amount || 0);
    if (["flat", "flatgoal"].includes(p.strategy))
      return total > 0 && (p.details.amount || 0) > 0;
    // Avoid enabling percentage offers that necessarily round to zero cents.
    const percentage = p.details.basisPoints || 0;
    return p.scope === "item"
      ? group.some((value) => Math.round((value * percentage) / 10000) > 0)
      : Math.round((total * percentage) / 10000) > 0;
  });
}

export function addOfferItems(cart: Cart, additions: Addition[]): Cart {
  const lines = cart.lines.map((line) => ({ ...line }));
  for (const { item, quantity } of additions) {
    // Keep edited cart snapshots intact; catalog additions get their catalog price/category.
    const existing = lines.find(
      (line) =>
        line.itemId === item.id &&
        line.price === item.price &&
        line.category === item.category,
    );
    if (existing) existing.quantity += quantity;
    else {
      let sequence = lines.length;
      while (lines.some((line) => line.id === `offer-${sequence}`)) sequence++;
      lines.push({
        id: `offer-${sequence}`,
        itemId: item.id,
        name: item.name,
        price: item.price,
        category: item.category,
        quantity,
      });
    }
  }
  return {
    ...cart,
    lines,
    selections: cart.selections.map((selection) => ({
      ...selection,
      pairs: [],
    })),
  };
}

export function findOfferAdditions(
  p: Promotion,
  cart: Cart,
  evaluation: Evaluation,
  promotions: Promotion[],
  catalog: ItemView[],
): Addition[] | undefined {
  const room = 1000 - cart.lines.reduce((sum, line) => sum + line.quantity, 0);
  if (
    room <= 0 ||
    !p.enabled ||
    (p.expiresAt && Date.parse(p.expiresAt) <= Date.now())
  )
    return undefined;
  const eligible = catalog
    .map((entry) => entry.item)
    .filter((item) => isEligibleItem(p, item))
    .sort((a, b) => a.price - b.price || (a.id < b.id ? -1 : 1));
  const fits = (additions: Addition[]) => {
    const candidate = addOfferItems(cart, additions);
    return (
      candidate.lines.length <= 100 &&
      candidate.lines.every((line) => line.quantity <= 1000) &&
      candidate.lines.reduce((sum, line) => sum + line.quantity, 0) <= 1000 &&
      canPromotionApply(p, candidate, evaluation, promotions)
    );
  };
  const findAdditionalUnits = (prefix: Addition[]): Addition[] | undefined => {
    const prepared = addOfferItems(cart, prefix);
    const remainingRoom =
      room - prefix.reduce((sum, addition) => sum + addition.quantity, 0);
    for (const item of eligible) {
      const existing = prepared.lines.find(
        (line) =>
          line.itemId === item.id &&
          line.price === item.price &&
          line.category === item.category,
      );
      const maximum = Math.min(remainingRoom, 1000 - (existing?.quantity || 0));
      const candidate = (quantity: number) =>
        prefix.some((addition) => addition.item.id === item.id)
          ? prefix.map((addition) =>
              addition.item.id === item.id
                ? { item, quantity: addition.quantity + quantity }
                : addition,
            )
          : [...prefix, { item, quantity }];
      for (const quantity of [1, 2, 3]) {
        if (quantity <= maximum && fits(candidate(quantity)))
          return candidate(quantity);
      }
      let low = 4;
      let high = maximum;
      if (high >= low && fits(candidate(high))) {
        while (low < high) {
          const middle = Math.floor((low + high) / 2);
          if (fits(candidate(middle))) high = middle;
          else low = middle + 1;
        }
        return candidate(low);
      }
    }
    return undefined;
  };
  if (p.strategy === "rainbowpercent") {
    const categories = new Set(
      cart.lines
        .filter((line) =>
          isEligibleItem(p, { id: line.itemId, category: line.category }),
        )
        .map((line) => line.category),
    );
    const additions: Addition[] = [];
    for (const item of eligible) {
      if (!categories.has(item.category)) {
        categories.add(item.category);
        additions.push({ item, quantity: 1 });
      }
      if (
        additions.length &&
        categories.size >= (p.details.categories || 1) &&
        fits(additions)
      )
        return additions;
    }
    if (categories.size >= (p.details.categories || 1) && additions.length) {
      const withPositiveSaving = findAdditionalUnits(additions);
      if (withPositiveSaving) return withPositiveSaving;
    }
  }
  const singleItem = findAdditionalUnits([]);
  if (singleItem) return singleItem;
  // Triple offers can need both a sponsor and add-ons from different catalog items.
  if (p.strategy === "bogotriple" && eligible.length >= 2) {
    const positive = eligible.find((item) => item.price > 0);
    const addOns =
      eligible[0].price === 0
        ? [
            { item: eligible[0], quantity: 1 },
            { item: positive || eligible[0], quantity: 1 },
          ]
        : [{ item: eligible[0], quantity: 2 }];
    const additions = [
      ...addOns,
      { item: eligible[eligible.length - 1], quantity: 1 },
    ];
    if (fits(additions)) return additions;
  }
  return undefined;
}
