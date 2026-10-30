import { test, expect } from "@playwright/test";
import {
  addOfferItems,
  canPromotionApply,
  findOfferAdditions,
} from "../src/promotion-eligibility";
import type { Cart, Evaluation, ItemView, Promotion } from "../src/types";

const promotion = (patch: Partial<Promotion> = {}): Promotion => ({
  id: "offer",
  version: 1,
  name: "Offer",
  scope: "items",
  strategy: "flat",
  exclusivity: "stackable",
  details: { amount: 100 },
  itemIds: [],
  categories: [],
  enabled: true,
  schemaVersion: 1,
  ...patch,
});
const cart = (prices: number[] = []): Cart => ({
  id: "cart",
  version: 1,
  lines: prices.map((price, index) => ({
    id: `line${index}`,
    itemId: `item${index}`,
    name: `Item ${index}`,
    price,
    quantity: 1,
    category: `Category ${index}`,
  })),
  shipping: 1000,
  selections: [],
  completed: false,
});
const evaluation = { steps: [] } as unknown as Evaluation;
const catalog: ItemView[] = [1000, 200, 300].map((price, index) => ({
  version: 1,
  item: {
    id: `item${index}`,
    name: `Item ${index}`,
    price,
    category: `Category ${index}`,
  },
}));

test("only intersecting category/item targets count and unavailable offers are gray", () => {
  const p = promotion({ itemIds: ["item0"], categories: ["Category 1"] });
  expect(canPromotionApply(p, cart([1000, 200]), evaluation, [p])).toBe(false);
  expect(
    findOfferAdditions(p, cart(), evaluation, [p], catalog),
  ).toBeUndefined();
  expect(
    canPromotionApply(
      promotion({ enabled: false }),
      cart([1000]),
      evaluation,
      [],
    ),
  ).toBe(false);
  expect(
    canPromotionApply(
      promotion({ expiresAt: "2020-01-01T00:00:00Z" }),
      cart([1000]),
      evaluation,
      [],
    ),
  ).toBe(false);
});

test("BOGO adds enough matching units and triple adds both cheap add-ons", () => {
  const bogo = promotion({
    strategy: "bogo",
    scope: "item",
    itemIds: ["item0"],
  });
  expect(canPromotionApply(bogo, cart(), evaluation, [bogo])).toBe(false);
  const pair = findOfferAdditions(bogo, cart(), evaluation, [bogo], catalog)!;
  expect(pair.map((addition) => addition.quantity)).toEqual([2]);
  expect(
    canPromotionApply(bogo, addOfferItems(cart(), pair), evaluation, [bogo]),
  ).toBe(true);
  const triple = promotion({ strategy: "bogotriple" });
  const base = cart([1000]);
  const additions = findOfferAdditions(
    triple,
    base,
    evaluation,
    [triple],
    catalog,
  )!;
  expect(additions).toHaveLength(1);
  expect(additions[0].item.price).toBe(200);
  expect(additions[0].quantity).toBe(2);
  expect(
    canPromotionApply(triple, addOfferItems(base, additions), evaluation, [
      triple,
    ]),
  ).toBe(true);
});

test("triple requires a strict combined remaining balance and positive savings", () => {
  const p = promotion({ strategy: "bogotriple" });
  expect(canPromotionApply(p, cart([500, 200, 300]), evaluation, [p])).toBe(
    false,
  );
  expect(canPromotionApply(p, cart([501, 200, 300]), evaluation, [p])).toBe(
    true,
  );
  expect(canPromotionApply(p, cart([1000, 0, 0]), evaluation, [p])).toBe(false);
  expect(canPromotionApply(p, cart([1000, 0, 200]), evaluation, [p])).toBe(
    true,
  );
  const earlier = promotion({
    id: "early",
    scope: "oneitem",
    strategy: "flat",
  });
  const discounted = {
    steps: [
      {
        promotionId: "early",
        allocations: [{ unitId: "line0:0000", lineId: "line0", saving: 600 }],
      },
    ],
  } as unknown as Evaluation;
  expect(
    canPromotionApply(p, cart([1000, 200, 300]), discounted, [earlier, p]),
  ).toBe(false);
});

test("threshold additions are minimal and rounded percentage offers need positive savings", () => {
  const goal = promotion({
    strategy: "flatgoal",
    details: { amount: 100, threshold: 5000 },
    itemIds: ["item0"],
  });
  const additions = findOfferAdditions(
    goal,
    cart(),
    evaluation,
    [goal],
    catalog,
  )!;
  expect(additions[0].quantity).toBe(6);
  const fixed = promotion({
    strategy: "fixedprice",
    scope: "oneitem",
    details: { amount: 5000 },
    itemIds: ["item0"],
  });
  expect(
    findOfferAdditions(fixed, cart(), evaluation, [fixed], catalog)?.[0]
      .quantity,
  ).toBe(6);
  expect(
    canPromotionApply(
      promotion({ strategy: "percent", details: { basisPoints: 1 } }),
      cart([100]),
      evaluation,
      [],
    ),
  ).toBe(false);
});

test("rainbow additions include the required distinct eligible categories", () => {
  const p = promotion({
    strategy: "rainbowpercent",
    details: { categories: 3, basisPoints: 1000 },
  });
  const additions = findOfferAdditions(
    p,
    cart([1000]),
    evaluation,
    [p],
    catalog,
  )!;
  expect(additions).toHaveLength(2);
  expect(
    canPromotionApply(p, addOfferItems(cart([1000]), additions), evaluation, [
      p,
    ]),
  ).toBe(true);
  expect(
    canPromotionApply(
      { ...p, scope: "item" },
      cart([1000, 200, 300]),
      evaluation,
      [p],
    ),
  ).toBe(false);
});

test("catalog additions preserve edited snapshots and respect cart limits", () => {
  const base = cart([999]);
  const updated = addOfferItems(base, [{ item: catalog[0].item, quantity: 2 }]);
  expect(updated.lines).toHaveLength(2);
  expect(updated.lines[0].price).toBe(999);
  expect(updated.lines[1].price).toBe(1000);
  const full = cart([1000]);
  full.lines[0].quantity = 1000;
  expect(
    findOfferAdditions(promotion(), full, evaluation, [], catalog),
  ).toBeUndefined();
});

test("triple catalog plans can combine a free item with a positively priced add-on", () => {
  const p = promotion({ strategy: "bogotriple" });
  const available = [0, 600, 1000].map((price, index) => ({
    ...catalog[index],
    item: { ...catalog[index].item, price },
  }));
  const additions = findOfferAdditions(p, cart(), evaluation, [p], available)!;
  expect(additions.reduce((sum, addition) => sum + addition.quantity, 0)).toBe(
    3,
  );
  expect(
    canPromotionApply(p, addOfferItems(cart(), additions), evaluation, [p]),
  ).toBe(true);
});

test("rainbow catalog plans provide enough value as well as missing categories", () => {
  const p = promotion({
    strategy: "rainbowpercent",
    details: { categories: 3, basisPoints: 100 },
  });
  const available = [0, 0, 1].map((price, index) => ({
    ...catalog[index],
    item: { ...catalog[index].item, price },
  }));
  const additions = findOfferAdditions(p, cart(), evaluation, [p], available)!;
  expect(additions).toHaveLength(3);
  expect(
    additions.find((addition) => addition.item.price === 1)?.quantity,
  ).toBe(50);
  expect(
    canPromotionApply(p, addOfferItems(cart(), additions), evaluation, [p]),
  ).toBe(true);
});
