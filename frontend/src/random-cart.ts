import type { ItemView, Line } from "./types";

export function createRandomCart(items: ItemView[]): Line[] {
  if (!items.length) return [];
  const random = (low: number, high: number) =>
    low + Math.floor(Math.random() * (high - low + 1));
  const shuffled = [...items];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = random(0, i);
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  const unique = random(Math.ceil(items.length / 2), items.length);
  const total =
    items.length >= 8
      ? random(2 * items.length, 3 * items.length)
      : random(6, 20);
  const lines = shuffled.slice(0, unique).map(({ item }) => ({
    id: crypto.randomUUID(),
    itemId: item.id,
    name: item.name,
    price: item.price,
    category: item.category,
    quantity: 1,
  }));
  for (let i = unique; i < total; i++)
    lines[random(0, lines.length - 1)].quantity++;
  return lines;
}
