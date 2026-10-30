import { useEffect, useRef, useState } from "react";
import {
  sendApiRequest,
  client,
  ApiError,
  parseDollarAmount,
  formatMoney,
  createUniqueId,
} from "./api";
import { generatePromotionDescription } from "./promotion-description";
import { createRandomCart } from "./random-cart";
import { Breakdown, Trace } from "./components";
import type {
  Cart,
  CartView,
  CartSummary,
  ItemView,
  Line,
  OptimizedCart,
  Order,
  Promotion,
  Quote,
  Selection,
} from "./types";

const createEmptyCart = (): Cart => ({
  id: createUniqueId(),
  version: 0,
  lines: [],
  shipping: 1000,
  selections: [],
  completed: false,
});
export function Customer() {
  const [view, setView] = useState<CartView>();
  const [items, setItems] = useState<ItemView[]>([]);
  const [promotions, setPromotions] = useState<Promotion[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [quote, setQuote] = useState<Quote>();
  const [order, setOrder] = useState<Order>();
  const [history, setHistory] = useState<CartSummary[]>([]);
  const [itemName, setItemName] = useState("");
  const [itemPrice, setItemPrice] = useState("10.00");
  const [category, setCategory] = useState("Everyday");
  const [editing, setEditing] = useState<ItemView>();
  const [targets, setTargets] = useState<Record<string, string>>({});
  const current = useRef<CartView | undefined>(undefined);
  const locked = useRef(false);
  const checkoutKey = useRef(createUniqueId());
  const cleanedVersion = useRef("");

  function applyCartView(next: CartView) {
    current.current = next;
    setView(next);
    setHistory((previous) => [
      ...previous.filter((cart) => cart.id !== next.cart.id),
      {
        id: next.cart.id,
        total: next.evaluation.total,
        totalQuantity: next.cart.lines.reduce(
          (n, line) => n + line.quantity,
          0,
        ),
        savedAt: next.cart.savedAt,
        completed: next.cart.completed,
      },
    ]);
  }
  async function runWorkspaceTask(action: () => Promise<void>) {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (e instanceof ApiError && e.status === 409 && current.current) {
        const fresh = await sendApiRequest<CartView>(
          `/carts/${current.current.cart.id}`,
        ).catch(() => undefined);
        if (fresh) applyCartView(fresh);
      }
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  async function loadWorkspaceData() {
    const [catalog, available, carts, summaries] = await Promise.all([
      client.listItems(),
      client.listPromotions(),
      client.listCarts(),
      client.listCartSummaries(),
    ]);
    setItems(catalog);
    setPromotions(available);
    setHistory(summaries);
    const latest = carts
      .filter((cart) => !cart.completed)
      .sort(
        (a, b) =>
          (Date.parse(b.savedAt || "") || 0) -
          (Date.parse(a.savedAt || "") || 0),
      )[0];
    applyCartView(
      latest
        ? await sendApiRequest<CartView>(`/carts/${latest.id}`)
        : await client.createCart(createEmptyCart()),
    );
  }
  useEffect(() => {
    void runWorkspaceTask(loadWorkspaceData);
  }, []);
  useEffect(() => {
    if (busy || !view || order || view.cart.completed) return;
    const key = `${view.cart.id}:${view.cart.version}`;
    const unavailable = view.evaluation.rejected.some((r) =>
      /expired|disabled|no longer exists/i.test(r.reason),
    );
    if (unavailable && cleanedVersion.current !== key) {
      cleanedVersion.current = key;
      void refreshCart();
    }
  }, [view, busy, order]);
  async function refreshCart() {
    if (!current.current || order) return;
    await runWorkspaceTask(async () => {
      const available = await client.listPromotions();
      setPromotions(available);
      let fresh = await sendApiRequest<CartView>(
        `/carts/${current.current!.cart.id}`,
      );
      if (
        quote &&
        (Date.now() >= Date.parse(quote.expiresAt) ||
          fresh.evaluation.promotionRevision !==
            quote.evaluation.promotionRevision ||
          fresh.evaluation.total !== quote.evaluation.total)
      ) {
        setQuote(undefined);
        setMessage(
          "Your confirmation changed or expired. Review the refreshed cart before checking out.",
        );
      }
      const expired = new Set(
        fresh.evaluation.rejected
          .filter((r) => /expired|disabled|no longer exists/i.test(r.reason))
          .map((r) => r.promotionId),
      );
      if (expired.size && !fresh.cart.completed) {
        fresh = await sendApiRequest<CartView>(
          `/carts/${fresh.cart.id}`,
          "PUT",
          {
            ...fresh.cart,
            selections: fresh.cart.selections.filter(
              (s) => !expired.has(s.promotionId),
            ),
          },
        );
        setMessage(
          "An unavailable promotion was removed. Your total has been updated.",
        );
      }
      setHistory(await client.listCartSummaries());
      applyCartView(fresh);
    });
  }
  useEffect(() => {
    const focus = () => void refreshCart();
    window.addEventListener("focus", focus);
    const expiry = [view?.evaluation.nextExpiration, quote?.expiresAt]
      .filter(Boolean)
      .sort()[0];
    const timer = expiry
      ? setTimeout(
          focus,
          Math.min(
            2147483647,
            Math.max(100, new Date(expiry).getTime() - Date.now() + 100),
          ),
        )
      : undefined;
    return () => {
      window.removeEventListener("focus", focus);
      clearTimeout(timer);
    };
  }, [view?.evaluation.nextExpiration, view?.cart.version, quote, order]);
  async function persistCart(cart: Cart) {
    setMessage("");
    applyCartView(await client.updateCart({ id: cart.id }, cart));
  }
  function updateCartLine(id: string, patch: Partial<Line>) {
    void runWorkspaceTask(async () => {
      const cart = current.current!.cart;
      await persistCart({
        ...cart,
        lines: cart.lines.map((line) =>
          line.id === id ? { ...line, ...patch } : line,
        ),
      });
    });
  }
  function togglePromotion(promotion: Promotion) {
    void runWorkspaceTask(async () => {
      const cart = current.current!.cart;
      const selected = cart.selections.some(
        (s) => s.promotionId === promotion.id,
      );
      const line =
        targets[promotion.id] ||
        cart.lines.find(
          (l) =>
            (!promotion.itemIds.length ||
              promotion.itemIds.includes(l.itemId)) &&
            (!promotion.categories.length ||
              promotion.categories.includes(l.category)),
        )?.id;
      const selection = {
        promotionId: promotion.id,
        lineId: line,
        pairs: [],
      } as Selection;
      await persistCart({
        ...cart,
        selections: selected
          ? cart.selections.filter((s) => s.promotionId !== promotion.id)
          : [...cart.selections, selection],
      });
    });
  }
  async function requestOptimization(
    mode: string,
    retry = true,
  ): Promise<OptimizedCart> {
    const cart = current.current!;
    try {
      return await sendApiRequest<OptimizedCart>(
        `/carts/${cart.cart.id}/optimize`,
        "POST",
        {
          version: cart.cart.version,
          promotionRevision: cart.evaluation.promotionRevision,
          mode,
        },
      );
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 409 || !retry)
        throw error;
      applyCartView(await sendApiRequest<CartView>(`/carts/${cart.cart.id}`));
      return requestOptimization(mode, false);
    }
  }
  function applyOptimizedPromotions(mode: string) {
    setMessage("");
    void runWorkspaceTask(async () => {
      const result = await requestOptimization(mode);
      applyCartView({
        cart: result.cart,
        evaluation: result.result.evaluation,
      });
      const count = result.result.evaluation.coveredUnits.length;
      setMessage(
        mode === "swindle"
          ? `Apparently optimal promotions applied! What an awful deal! ${count} units discounted`
          : result.result.optimalityProven
            ? `Optimal promotions found! What an artful deal! ${count} units discounted.`
            : `Promotions found within the search time limit. ${count} units discounted.`,
      );
    });
  }
  async function createFreshCart(clone: boolean) {
    const next = clone
      ? await sendApiRequest<CartView>(`/carts/${order!.cart.id}/clone`, "POST")
      : await client.createCart(createEmptyCart());
    applyCartView(next);
    setQuote(undefined);
    setOrder(undefined);
    setMessage("");
    checkoutKey.current = createUniqueId();
    setHistory(await client.listCartSummaries());
  }
  if (!view)
    return (
      <main>
        <h1>Your workspace</h1>
        {error ? (
          <>
            <p role="alert">{error}</p>
            <button onClick={() => void runWorkspaceTask(loadWorkspaceData)}>
              Retry connection
            </button>
          </>
        ) : (
          <p>Loading your cart…</p>
        )}
      </main>
    );
  return (
    <main>
      <div className="page-heading">
        <div>
          <span className="eyebrow">THE PROMOTION PLAYGROUND</span>
          <h1>
            {order
              ? "All set. Nicely saved."
              : quote
                ? "One last look."
                : "Make your cart work harder."}
          </h1>
          <p className="muted">
            Every promotion, every penny. See exactly how your total takes
            shape.
          </p>
        </div>
        <div className="cart-badge">
          <span className="pill">
            {order ? "ORDER COMPLETE" : quote ? "CONFIRMATION" : "YOUR CART"}
          </span>
          {!order && !quote && (
            <button
              className="quiet remove"
              aria-label="Delete current cart"
              disabled={busy}
              onClick={() => {
                if (
                  window.confirm(
                    "Delete this cart and start with an empty cart?",
                  )
                )
                  void runWorkspaceTask(async () => {
                    applyCartView(
                      await sendApiRequest<CartView>(
                        `/carts/${view.cart.id}`,
                        "DELETE",
                        {
                          version: view.cart.version,
                        },
                      ),
                    );
                    setHistory(await client.listCartSummaries());
                    setMessage("Cart deleted. A fresh empty cart is ready.");
                  });
              }}
            >
              ×
            </button>
          )}
        </div>{" "}
      </div>
      {message && (
        <div className="banner" role="status">
          {message}
          <button
            className="quiet"
            aria-label="Dismiss notice"
            onClick={() => setMessage("")}
          >
            ×
          </button>
        </div>
      )}
      {error && (
        <div className="banner error" role="alert">
          {error}
        </div>
      )}
      <fieldset
        disabled={busy}
        className="workspace-fieldset"
        onChange={() => setMessage("")}
      >
        {order ? (
          <section className="panel confirmation">
            <span className="eyebrow">SIMULATED ORDER RECORDED</span>
            <h2>Thanks for experimenting.</h2>
            <Breakdown cart={order.cart} evaluation={order.evaluation} />
            <Trace evaluation={order.evaluation} cart={order.cart} />
            <div className="actions">
              <button
                className="primary"
                onClick={() =>
                  void runWorkspaceTask(() => createFreshCart(false))
                }
              >
                Start a fresh cart
              </button>
              <button
                onClick={() =>
                  void runWorkspaceTask(() => createFreshCart(true))
                }
              >
                Clone this cart
              </button>
            </div>
            <p className="muted">
              Order recorded {new Date(order.createdAt).toLocaleString()}. No
              payment was collected.
            </p>
          </section>
        ) : quote ? (
          <section className="panel confirmation">
            <h2>Review your order</h2>
            <Breakdown cart={quote.cart} evaluation={quote.evaluation} />
            <Trace evaluation={quote.evaluation} cart={quote.cart} />
            <p className="muted">
              Your price is rechecked when you submit. Confirmation expires
              after five minutes.
            </p>
            <div className="actions">
              <button
                className="primary"
                onClick={() =>
                  void runWorkspaceTask(async () =>
                    setOrder(
                      await sendApiRequest<Order>(
                        "/orders",
                        "POST",
                        { quoteId: quote.id },
                        { "Idempotency-Key": checkoutKey.current },
                      ),
                    ),
                  )
                }
              >
                Buy Buy Buy
              </button>
              <button onClick={() => setQuote(undefined)}>Let me think</button>
            </div>
          </section>
        ) : (
          <div className="cart-layout">
            <aside className="panel items-panel">
              <div className="section-heading">
                <h2>Your items</h2>
                <span className="count">{items.length}</span>
              </div>
              <p className="muted small">Build your own little catalog.</p>
              <div className="catalog-list">
                {items.map(({ item, version }) => (
                  <article className="catalog-item" key={item.id}>
                    <div className="item-icon">
                      {item.name.charAt(0).toUpperCase()}
                    </div>
                    <div>
                      <b>{item.name}</b>
                      <span className="muted small">{item.category}</span>
                      <span>{formatMoney(item.price)}</span>
                    </div>
                    <div className="catalog-actions">
                      <button
                        aria-label={`Add ${item.name}`}
                        onClick={() =>
                          void runWorkspaceTask(async () => {
                            const cart = current.current!.cart;
                            const existing = cart.lines.find(
                              (l) => l.itemId === item.id,
                            );
                            await persistCart({
                              ...cart,
                              lines: existing
                                ? cart.lines.map((l) =>
                                    l.id === existing.id
                                      ? { ...l, quantity: l.quantity + 1 }
                                      : l,
                                  )
                                : [
                                    ...cart.lines,
                                    {
                                      id: createUniqueId(),
                                      itemId: item.id,
                                      name: item.name,
                                      price: item.price,
                                      category: item.category,
                                      quantity: 1,
                                    },
                                  ],
                            });
                          })
                        }
                      >
                        +
                      </button>
                      <button
                        className="quiet small"
                        onClick={() => {
                          setEditing({ item, version });
                          setItemName(item.name);
                          setItemPrice((item.price / 100).toFixed(2));
                          setCategory(item.category);
                        }}
                      >
                        Edit
                      </button>
                    </div>
                  </article>
                ))}
              </div>
              <form
                className="item-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void runWorkspaceTask(async () => {
                    const id = editing?.item.id || createUniqueId();
                    await sendApiRequest(`/items/${id}`, "PUT", {
                      item: {
                        id,
                        name: itemName,
                        price: parseDollarAmount(itemPrice),
                        category,
                      },
                      version: editing?.version || 0,
                    });
                    setItems(await client.listItems());
                    setItemName("");
                    setEditing(undefined);
                  });
                }}
              >
                <h3>{editing ? "Edit item" : "Create an item"}</h3>
                <label>
                  Name
                  <input
                    required
                    value={itemName}
                    onChange={(e) => setItemName(e.target.value)}
                    placeholder="Something good"
                  />
                </label>
                <label>
                  Unit price ($)
                  <input
                    required
                    type="number"
                    min="0"
                    step="0.01"
                    value={itemPrice}
                    onChange={(e) => setItemPrice(e.target.value)}
                  />
                </label>
                <label>
                  Category
                  <input
                    required
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                  />
                </label>
                <button className="full">
                  {editing ? "Save item" : "Add to catalog"}
                </button>
                {editing && (
                  <button
                    type="button"
                    onClick={() => {
                      setEditing(undefined);
                      setItemName("");
                    }}
                  >
                    Cancel edit
                  </button>
                )}
              </form>
            </aside>
            <section className="cart-center">
              <div className="panel">
                <div className="section-heading">
                  <h2>
                    In your cart (
                    {view.cart.lines.reduce((n, line) => n + line.quantity, 0)})
                  </h2>
                </div>
                {!view.cart.lines.length && (
                  <div className="empty">
                    <span>＋</span>
                    <h3>Won't your empty box be oh so pretty!</h3>
                    <p>Add an item from your catalog to get started.</p>
                  </div>
                )}
                {view.cart.lines.map((line) => {
                  const discounts = view.evaluation.steps
                    .map((step) => ({
                      ...step,
                      saving: step.allocations
                        .filter((a) => a.lineId === line.id)
                        .reduce((n, a) => n + a.saving, 0),
                    }))
                    .filter((s) => s.saving > 0)
                    .sort(
                      (a, b) =>
                        b.saving - a.saving ||
                        a.promotionId.localeCompare(b.promotionId),
                    );
                  return (
                    <article className="cart-line" key={line.id}>
                      <div className="line-header">
                        <div>
                          <h3>{line.name}</h3>
                          <span className="muted small">{line.category}</span>
                        </div>
                        <strong>
                          {formatMoney(line.quantity * line.price)}
                        </strong>
                      </div>
                      <div className="line-controls">
                        <label>
                          Qty
                          <input
                            aria-label={`${line.name} quantity`}
                            key={`q-${line.quantity}`}
                            type="number"
                            min="1"
                            max="1000"
                            defaultValue={line.quantity}
                            onBlur={(e) => {
                              if (Number(e.target.value) !== line.quantity)
                                updateCartLine(line.id, {
                                  quantity: Number(e.target.value),
                                });
                            }}
                          />
                        </label>
                        <label>
                          Price ($)
                          <input
                            aria-label={`${line.name} price`}
                            key={`p-${line.price}`}
                            type="number"
                            min="0"
                            step="0.01"
                            defaultValue={(line.price / 100).toFixed(2)}
                            onBlur={(e) => {
                              if (
                                parseDollarAmount(e.target.value) !== line.price
                              )
                                updateCartLine(line.id, {
                                  price: parseDollarAmount(e.target.value),
                                });
                            }}
                          />
                        </label>
                        <button
                          className="quiet remove"
                          onClick={() =>
                            void runWorkspaceTask(async () => {
                              const cart = current.current!.cart;
                              await persistCart({
                                ...cart,
                                lines: cart.lines.filter(
                                  (l) => l.id !== line.id,
                                ),
                              });
                            })
                          }
                        >
                          Remove
                        </button>
                      </div>
                      <div className="line-savings">
                        {discounts.map((d) => (
                          <span key={d.promotionId}>
                            {d.name} <b>−{formatMoney(d.saving)}</b>
                          </span>
                        ))}
                      </div>
                    </article>
                  );
                })}
              </div>
              <div className="panel promotions-panel">
                <div className="section-heading">
                  <div>
                    <h2>Available promotions</h2>
                    <p className="muted small">
                      Choose your savings. Or let us do the math.
                    </p>
                  </div>
                </div>
                <div className="strategy-actions">
                  <button
                    className="primary"
                    onClick={() => applyOptimizedPromotions("suggest")}
                  >
                    ✦ Suggest best
                  </button>
                  <button onClick={() => applyOptimizedPromotions("swindle")}>
                    Swindle
                  </button>
                </div>
                <p className="muted small">
                  "Suggest best" finds you a great deal and tries to find the
                  absolute best!
                </p>
                <p className="muted small">
                  Swindle covers as many units as possible, then saves as little
                  as possible.
                </p>
                {!promotions.some((p) => p.enabled) && (
                  <p className="empty">
                    No promotions yet. An administrator can create some.
                  </p>
                )}
                {promotions
                  .filter(
                    (p) =>
                      p.enabled &&
                      (!p.expiresAt ||
                        new Date(p.expiresAt).getTime() > Date.now()),
                  )
                  .map((p) => (
                    <div className="promotion" key={p.id}>
                      <label className="check-label">
                        <input
                          type="checkbox"
                          checked={view.cart.selections.some(
                            (s) => s.promotionId === p.id,
                          )}
                          onChange={() => togglePromotion(p)}
                        />
                        <span>
                          <b>{p.name}</b>
                          <small>
                            {p.description || generatePromotionDescription(p)}
                          </small>
                        </span>
                      </label>
                      {(p.scope === "oneitem" || p.scope === "item") && (
                        <select
                          aria-label={`${p.name} target`}
                          value={
                            targets[p.id] ||
                            view.cart.selections.find(
                              (s) => s.promotionId === p.id,
                            )?.lineId ||
                            ""
                          }
                          onChange={(e) => {
                            setTargets({ ...targets, [p.id]: e.target.value });
                            if (
                              view.cart.selections.some(
                                (s) => s.promotionId === p.id,
                              )
                            )
                              void runWorkspaceTask(async () => {
                                const cart = current.current!.cart;
                                await persistCart({
                                  ...cart,
                                  selections: cart.selections.map((s) =>
                                    s.promotionId === p.id
                                      ? {
                                          ...s,
                                          lineId: e.target.value,
                                          pairs: [],
                                        }
                                      : s,
                                  ),
                                });
                              });
                          }}
                        >
                          <option value="">
                            Automatically choose an eligible item
                          </option>
                          {view.cart.lines
                            .filter(
                              (l) =>
                                (!p.itemIds.length ||
                                  p.itemIds.includes(l.itemId)) &&
                                (!p.categories.length ||
                                  p.categories.includes(l.category)),
                            )
                            .map((l) => (
                              <option key={l.id} value={l.id}>
                                {l.name}
                              </option>
                            ))}
                        </select>
                      )}
                    </div>
                  ))}
              </div>
            </section>
            <aside>
              <div className="panel summary-panel">
                <span className="eyebrow">THE BIG PICTURE</span>
                <h2>Order summary</h2>
                <Breakdown evaluation={view.evaluation} />
                <label>
                  Shipping ($)
                  <input
                    aria-label="Shipping"
                    type="number"
                    min="0"
                    step="0.01"
                    key={view.cart.shipping}
                    defaultValue={(view.cart.shipping / 100).toFixed(2)}
                    onBlur={(e) => {
                      if (
                        parseDollarAmount(e.target.value) !== view.cart.shipping
                      )
                        void runWorkspaceTask(() =>
                          persistCart({
                            ...current.current!.cart,
                            shipping: parseDollarAmount(e.target.value),
                          }),
                        );
                    }}
                  />
                </label>
                <button
                  disabled={!view.cart.lines.length}
                  className="primary full checkout"
                  onClick={() =>
                    void runWorkspaceTask(async () => {
                      checkoutKey.current = createUniqueId();
                      setQuote(
                        await sendApiRequest<Quote>(
                          `/carts/${view.cart.id}/quotes`,
                          "POST",
                          { version: view.cart.version },
                        ),
                      );
                    })
                  }
                >
                  Checkout →
                </button>
                <p className="muted small">
                  Just a sandbox. No payment details needed.
                </p>
                <Trace evaluation={view.evaluation} cart={view.cart} />
              </div>
              <div className="panel saved-carts">
                <h3>Saved carts</h3>
                <select
                  aria-label="Saved carts"
                  title={
                    view.cart.savedAt
                      ? new Date(view.cart.savedAt).toLocaleString()
                      : "---"
                  }
                  value={view.cart.id}
                  onChange={(e) =>
                    void runWorkspaceTask(async () =>
                      applyCartView(
                        await sendApiRequest<CartView>(
                          `/carts/${e.target.value}`,
                        ),
                      ),
                    )
                  }
                >
                  {history
                    .filter((cart) => !cart.completed)
                    .sort(
                      (a, b) =>
                        (Date.parse(b.savedAt || "") || 0) -
                          (Date.parse(a.savedAt || "") || 0) ||
                        a.id.localeCompare(b.id),
                    )
                    .map((cart) => (
                      <option
                        key={cart.id}
                        value={cart.id}
                        title={
                          cart.savedAt
                            ? new Date(cart.savedAt).toLocaleString()
                            : "---"
                        }
                      >
                        {formatMoney(cart.total)} · {cart.totalQuantity} items
                      </option>
                    ))}
                </select>
                <button
                  className="quiet"
                  onClick={() =>
                    void runWorkspaceTask(() => createFreshCart(false))
                  }
                >
                  + New cart
                </button>
                <button
                  className="quiet surprise-cart"
                  disabled={!items.length}
                  onClick={() =>
                    void runWorkspaceTask(async () => {
                      await persistCart({
                        ...current.current!.cart,
                        lines: createRandomCart(items),
                        selections: [],
                      });
                    })
                  }
                >
                  Surprise me!
                </button>
              </div>
            </aside>
          </div>
        )}
      </fieldset>
      {busy && (
        <div className="working" role="status">
          Updating your workspace…
        </div>
      )}
    </main>
  );
}
