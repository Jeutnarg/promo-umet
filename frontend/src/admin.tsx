import { useEffect, useState } from "react";
import {
  sendApiRequest,
  client,
  parseDollarAmount,
  createUniqueId,
  formatMoney,
} from "./api";
import { generatePromotionDescription } from "./promotion-description";
import { Trace } from "./components";
import { PromotionDashboard } from "./promotion-dashboard";
import type { Promotion, TestCase, TestResult, TestSet } from "./types";

function createPromotionDraft(): Promotion {
  const expiration = new Date();
  const day = expiration.getDate();
  expiration.setDate(1);
  expiration.setMonth(expiration.getMonth() + 1);
  expiration.setDate(
    Math.min(
      day,
      new Date(
        expiration.getFullYear(),
        expiration.getMonth() + 1,
        0,
      ).getDate(),
    ),
  );
  return {
    expiresAt: expiration.toISOString(),
    id: createUniqueId(),
    version: 0,
    name: "",
    scope: "items",
    exclusivity: "stackable",
    strategy: "flat",
    details: { amount: 500, basisPoints: 2000, threshold: 5000, categories: 2 },
    itemIds: [],
    categories: [],
    enabled: true,
    schemaVersion: 1,
  } as Promotion;
}
const createSampleTestCase = (): TestCase => ({
  name: "Two units, no discounts",
  cart: {
    id: "fixture",
    version: 1,
    lines: [
      {
        id: "line1",
        itemId: "item1",
        name: "Sample item",
        quantity: 2,
        price: 1000,
        category: "Everyday",
      },
    ],
    shipping: 1000,
    selections: [],
    completed: false,
  },
  promotions: [],
  mode: "manual",
  now: "2026-01-01T00:00:00Z",
  expectedTotal: 3000,
  expectedCoveredUnits: 0,
});
const createEmptyTestSet = (): TestSet => ({
  id: createUniqueId(),
  version: 0,
  schemaVersion: 1,
  name: "New test set",
  cases: [],
});
export function Admin() {
  const [tab, setTab] = useState("promotions");
  const [promotions, setPromotions] = useState<Promotion[]>([]);
  const [draft, setDraft] = useState(createPromotionDraft);
  const [sets, setSets] = useState<TestSet[]>([]);
  const [set, setSet] = useState(createEmptyTestSet);
  const [json, setJson] = useState("");
  const [fixture, setFixture] = useState(
    JSON.stringify(createSampleTestCase(), null, 2),
  );
  const [results, setResults] = useState<TestResult[]>([]);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("all");
  const [noCases, setNoCases] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [manualName, setManualName] = useState("A new scenario");
  const [manualQuantity, setManualQuantity] = useState(2);
  const [manualPrice, setManualPrice] = useState("10.00");
  const [manualShipping, setManualShipping] = useState("10.00");
  const [manualExpected, setManualExpected] = useState("30.00");
  const [manualMode, setManualMode] = useState("manual");
  const [manualPromos, setManualPromos] = useState<string[]>([]);
  const [manualTime, setManualTime] = useState("2026-01-01T00:00");
  async function runWorkspaceTask(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }
  async function loadWorkspaceData() {
    const [p, s] = await Promise.all([
      client.listPromotions(),
      client.listTestSets(),
    ]);
    setPromotions(p);
    setSets(s);
  }
  useEffect(() => {
    void runWorkspaceTask(loadWorkspaceData);
  }, []);
  function updatePromotionDetail(key: string, value: number) {
    setDraft({ ...draft, details: { ...draft.details, [key]: value } });
  }
  function importTestSetJson(text: string, append: boolean) {
    try {
      if (new TextEncoder().encode(text).length > 262144)
        throw Error("Maximum import size is 256 KiB");
      const data = JSON.parse(text);
      if (data.schemaVersion !== 1 || !Array.isArray(data.cases))
        throw Error("Expected a schemaVersion 1 test set with a cases array");
      if (
        typeof data.name !== "string" ||
        !data.name.trim() ||
        data.cases.some(
          (test: unknown) =>
            !test ||
            typeof test !== "object" ||
            typeof (test as TestCase).name !== "string" ||
            !Number.isSafeInteger((test as TestCase).expectedTotal),
        )
      )
        throw Error(
          "A test set and each case need a name; each case needs an expectedTotal in cents",
        );
      if (append) setSet({ ...set, cases: [...set.cases, ...data.cases] });
      else setSet({ ...data, id: createUniqueId(), version: 0 });
      setMessage(
        append
          ? "Cases appended. Save to persist them."
          : "Imported as a new set. Save to persist it.",
      );
      setError("");
    } catch (e) {
      setError(String(e));
    }
  }
  function downloadTestSet() {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(set, null, 2)], { type: "application/json" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = `${set.name.replace(/[^a-z0-9-]/gi, "-")}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }
  const expired = Boolean(
    draft.expiresAt && Date.parse(draft.expiresAt) <= Date.now(),
  );
  return (
    <main>
      <div className="page-heading">
        <div>
          <span className="eyebrow">BEHIND THE SAVINGS</span>
          <h1>A little rule. A big difference.</h1>
          <p className="muted">
            Design promotions, test the edge cases, and follow every cent.
          </p>
        </div>
        <span className="pill">ADMINISTRATOR</span>
      </div>
      <div className="tabs">
        <button
          className={tab === "dashboard" ? "selected" : ""}
          onClick={() => setTab("dashboard")}
        >
          Promotion dashboard
        </button>
        <button
          className={tab === "promotions" ? "selected" : ""}
          onClick={() => setTab("promotions")}
        >
          Promotion control
        </button>
        <button
          className={tab === "tests" ? "selected" : ""}
          onClick={() => setTab("tests")}
        >
          Test workspace
        </button>
      </div>
      {error && (
        <div className="banner error" role="alert">
          {error}
        </div>
      )}
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
      <fieldset disabled={busy} className="workspace-fieldset">
        {tab === "dashboard" ? (
          <PromotionDashboard />
        ) : tab === "promotions" ? (
          <div className="admin-layout">
            <section className="panel">
              <div className="section-heading">
                <h2>Promotions</h2>
                <button onClick={() => setDraft(createPromotionDraft())}>
                  + New
                </button>
              </div>
              <fieldset className="promotion-filters">
                <legend>Show promotions</legend>
                {[
                  ["all", "All"],
                  ["never", "Without expiration"],
                  ["expired", "Expired"],
                  ["disabled", "Only disabled"],
                  ["enabled", "Exclude disabled"],
                ].map(([value, label]) => (
                  <label className="check-label" key={value}>
                    <input
                      type="radio"
                      name="promotion-filter"
                      value={value}
                      checked={filter === value}
                      onChange={() => setFilter(value)}
                    />
                    {label}
                  </label>
                ))}
              </fieldset>
              {promotions.length === 0 && (
                <p className="muted">
                  Create your first promotion to start experimenting.
                </p>
              )}
              {promotions
                .filter(
                  (p) =>
                    filter === "all" ||
                    (filter === "never" && !p.expiresAt) ||
                    (filter === "expired" &&
                      !!p.expiresAt &&
                      Date.parse(p.expiresAt) <= Date.now()) ||
                    (filter === "disabled" && !p.enabled) ||
                    (filter === "enabled" && p.enabled),
                )
                .map((p) => (
                  <button
                    className={`promotion-card ${draft.id === p.id ? "chosen" : ""}`}
                    key={p.id}
                    onClick={() => setDraft(p)}
                  >
                    <b>{p.name}</b>
                    <span>
                      {p.strategy} / {p.scope}
                    </span>
                    <small>
                      {!p.enabled
                        ? "Disabled"
                        : p.expiresAt && Date.parse(p.expiresAt) < Date.now()
                          ? "Expired"
                          : "Active"}{" "}
                      · v{p.version}
                    </small>
                  </button>
                ))}
            </section>
            <form
              className="panel editor"
              onSubmit={(e) => {
                e.preventDefault();
                void runWorkspaceTask(async () => {
                  const saved = await sendApiRequest<Promotion>(
                    `/admin/promotions/${draft.id}`,
                    "PUT",
                    {
                      ...draft,
                      description:
                        draft.description ??
                        generatePromotionDescription(draft),
                    },
                  );
                  setDraft(saved);
                  await loadWorkspaceData();
                  setMessage(
                    "Promotion saved. New cart calculations use this version.",
                  );
                });
              }}
            >
              <span className="eyebrow">PROMOTION DEFINITION</span>
              <h2>
                {draft.version ? "Edit promotion" : "Create a promotion"}
                {expired ? " (Expired)" : ""}
              </h2>
              <label>
                Expiration (your local time; optional)
                <input
                  type="datetime-local"
                  value={
                    draft.expiresAt
                      ? new Date(
                          Date.parse(draft.expiresAt) -
                            new Date(draft.expiresAt).getTimezoneOffset() *
                              60000,
                        )
                          .toISOString()
                          .slice(0, 16)
                      : ""
                  }
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      expiresAt: e.target.value
                        ? new Date(e.target.value).toISOString()
                        : undefined,
                    } as Promotion)
                  }
                />
              </label>
              <fieldset className="workspace-fieldset" disabled={expired}>
                <label>
                  Customer description
                  <textarea
                    maxLength={2000}
                    value={
                      draft.description ?? generatePromotionDescription(draft)
                    }
                    onChange={(e) =>
                      setDraft({ ...draft, description: e.target.value })
                    }
                  />
                </label>
                <button
                  type="button"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      description: generatePromotionDescription(draft),
                    })
                  }
                >
                  Use suggested description
                </button>
                <label>
                  Name
                  <input
                    required
                    value={draft.name}
                    onChange={(e) =>
                      setDraft({ ...draft, name: e.target.value })
                    }
                  />
                </label>
                <div className="form-grid">
                  <label>
                    Scope
                    <select
                      value={draft.scope}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          scope: e.target.value as Promotion["scope"],
                        })
                      }
                    >
                      {["oneitem", "item", "items", "order"].map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Strategy
                    <select
                      value={draft.strategy}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          strategy: e.target.value as Promotion["strategy"],
                          scope:
                            e.target.value === "shippinggoal"
                              ? "order"
                              : draft.scope,
                        })
                      }
                    >
                      {[
                        "bogo",
                        "flat",
                        "percent",
                        "fixedprice",
                        "flatgoal",
                        "shippinggoal",
                        "rainbowpercent",
                      ].map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </select>
                  </label>
                </div>
                <p className="muted small">
                  oneitem: once per line · item: each unit · items: merchandise
                  aggregate · order: merchandise and shipping.
                </p>
                {["flat", "flatgoal", "fixedprice"].includes(
                  draft.strategy,
                ) && (
                  <label>
                    {draft.strategy === "fixedprice"
                      ? "Target price ($)"
                      : "Reduction ($)"}
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      required
                      value={(draft.details.amount || 0) / 100}
                      onChange={(e) =>
                        updatePromotionDetail(
                          "amount",
                          parseDollarAmount(e.target.value),
                        )
                      }
                    />
                  </label>
                )}
                {["percent", "rainbowpercent"].includes(draft.strategy) && (
                  <label>
                    Percentage (%)
                    <input
                      type="number"
                      min="0.01"
                      max="100"
                      step="0.01"
                      required
                      value={(draft.details.basisPoints || 0) / 100}
                      onChange={(e) =>
                        updatePromotionDetail(
                          "basisPoints",
                          Math.round(Number(e.target.value) * 100),
                        )
                      }
                    />
                  </label>
                )}
                {["flatgoal", "shippinggoal"].includes(draft.strategy) && (
                  <label>
                    Merchandise must exceed ($)
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      required
                      value={(draft.details.threshold || 0) / 100}
                      onChange={(e) =>
                        updatePromotionDetail(
                          "threshold",
                          parseDollarAmount(e.target.value),
                        )
                      }
                    />
                  </label>
                )}
                {draft.strategy === "rainbowpercent" && (
                  <label>
                    Minimum category count
                    <input
                      type="number"
                      min="1"
                      required
                      value={draft.details.categories || 1}
                      onChange={(e) =>
                        updatePromotionDetail(
                          "categories",
                          Number(e.target.value),
                        )
                      }
                    />
                  </label>
                )}
                <label>
                  Exclusivity
                  <select
                    value={draft.exclusivity}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        exclusivity: e.target.value as Promotion["exclusivity"],
                      })
                    }
                  >
                    <option value="stackable">Stackable</option>
                    <option value="units">Exclusive on affected units</option>
                    <option value="order">Exclusive across order</option>
                  </select>
                </label>
                <label>
                  Target item IDs (comma separated; blank means all)
                  <input
                    value={draft.itemIds.join(", ")}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        itemIds: e.target.value
                          .split(",")
                          .map((s) => s.trim())
                          .filter(Boolean),
                      })
                    }
                  />
                </label>
                <label>
                  Target categories (comma separated; blank means all)
                  <input
                    value={draft.categories.join(", ")}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        categories: e.target.value
                          .split(",")
                          .map((s) => s.trim())
                          .filter(Boolean),
                      })
                    }
                  />
                </label>
                <label className="check-label">
                  <input
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={(e) =>
                      setDraft({ ...draft, enabled: e.target.checked })
                    }
                  />
                  Enabled
                </label>
              </fieldset>
              <button className="primary">Save promotion</button>
              <p className="muted small">ID: {draft.id}</p>
            </form>
          </div>
        ) : (
          <div className="test-layout">
            <aside className="panel">
              <div className="section-heading">
                <h2>Test sets</h2>
                <button
                  onClick={() => {
                    setSet(createEmptyTestSet());
                    setMessage("");
                    setNoCases(false);
                    setResults([]);
                  }}
                >
                  + New
                </button>
              </div>
              {sets.map((s) => (
                <button
                  className="promotion-card"
                  key={s.id}
                  onClick={() => {
                    setSet(s);
                    setMessage("");
                    setNoCases(false);
                    setResults([]);
                  }}
                >
                  <b>{s.name}</b>
                  <small>
                    {s.cases.length} cases · v{s.version}
                  </small>
                </button>
              ))}
              <h3>Import JSON</h3>
              <label>
                File
                <input
                  type="file"
                  accept=".json,application/json"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      if (file.size > 262144)
                        setError("Maximum file size is 256 KiB");
                      else void file.text().then(setJson);
                    }
                  }}
                />
              </label>
              <label>
                Paste a test set
                <textarea
                  rows={7}
                  value={json}
                  onChange={(e) => setJson(e.target.value)}
                />
              </label>
              <div className="actions">
                <button onClick={() => importTestSetJson(json, false)}>
                  Import new set
                </button>
                <button onClick={() => importTestSetJson(json, true)}>
                  Append cases
                </button>
              </div>
            </aside>
            <section className="panel">
              <label>
                Test set name
                <input
                  value={set.name}
                  onChange={(e) => setSet({ ...set, name: e.target.value })}
                />
              </label>
              <div className="actions">
                <button
                  className="primary"
                  onClick={() =>
                    void runWorkspaceTask(async () => {
                      const saved = await sendApiRequest<TestSet>(
                        `/admin/testsets/${set.id}`,
                        "PUT",
                        set,
                      );
                      setSet(saved);
                      await loadWorkspaceData();
                      setMessage("Test set saved.");
                    })
                  }
                >
                  Save set
                </button>
                <button onClick={downloadTestSet}>Export JSON</button>
                <button
                  onClick={() =>
                    void (() => {
                      if (!set.cases.length) {
                        setNoCases(true);
                        return;
                      }
                      setNoCases(false);
                      runWorkspaceTask(async () => {
                        setResults(
                          await sendApiRequest<TestResult[]>(
                            "/admin/tests/run",
                            "POST",
                            set,
                          ),
                        );
                        setMessage("Test run complete.");
                      });
                    })()
                  }
                >
                  Run tests
                </button>
                {noCases && (
                  <small role="alert" className="remove">
                    No test cases
                  </small>
                )}
              </div>
              <div className="test-cases">
                {set.cases.map((test, i) => (
                  <article className="test-case" key={i}>
                    <div>
                      <b>{test.name}</b>
                      <span className="muted small">
                        {test.mode} · expected {formatMoney(test.expectedTotal)}
                      </span>
                    </div>
                    <button
                      className="quiet"
                      onClick={() => setFixture(JSON.stringify(test, null, 2))}
                    >
                      Copy to editor
                    </button>
                    <button
                      className="quiet remove"
                      onClick={() =>
                        setSet({
                          ...set,
                          cases: set.cases.filter((_, index) => index !== i),
                        })
                      }
                    >
                      Remove
                    </button>
                  </article>
                ))}
              </div>
              <details open>
                <summary>Add a case manually</summary>
                <div className="form-grid">
                  <label>
                    Case name
                    <input
                      value={manualName}
                      onChange={(e) => setManualName(e.target.value)}
                    />
                  </label>
                  <label>
                    Quantity
                    <input
                      type="number"
                      min="1"
                      value={manualQuantity}
                      onChange={(e) =>
                        setManualQuantity(Number(e.target.value))
                      }
                    />
                  </label>
                  <label>
                    Unit price ($)
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={manualPrice}
                      onChange={(e) => setManualPrice(e.target.value)}
                    />
                  </label>
                  <label>
                    Shipping ($)
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={manualShipping}
                      onChange={(e) => setManualShipping(e.target.value)}
                    />
                  </label>
                  <label>
                    Expected total ($)
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={manualExpected}
                      onChange={(e) => setManualExpected(e.target.value)}
                    />
                  </label>
                  <label>
                    Mode
                    <select
                      value={manualMode}
                      onChange={(e) => setManualMode(e.target.value)}
                    >
                      {["manual", "suggest", "swindle"].map((m) => (
                        <option key={m}>{m}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Fixed evaluation time
                    <input
                      type="datetime-local"
                      value={manualTime}
                      onChange={(e) => setManualTime(e.target.value)}
                    />
                  </label>
                </div>
                <p className="muted small">
                  Snapshot promotions for this fixture. Manual mode also selects
                  them.
                </p>
                {promotions.map((p) => (
                  <label className="check-label" key={p.id}>
                    <input
                      type="checkbox"
                      checked={manualPromos.includes(p.id)}
                      onChange={(e) =>
                        setManualPromos(
                          e.target.checked
                            ? [...manualPromos, p.id]
                            : manualPromos.filter((id) => id !== p.id),
                        )
                      }
                    />
                    {p.name}
                  </label>
                ))}
                <button
                  onClick={() => {
                    const test = createSampleTestCase();
                    test.name = manualName;
                    test.cart.lines[0].quantity = manualQuantity;
                    test.cart.lines[0].price = parseDollarAmount(manualPrice);
                    test.cart.shipping = parseDollarAmount(manualShipping);
                    test.expectedTotal = parseDollarAmount(manualExpected);
                    test.mode = manualMode;
                    test.now = new Date(manualTime).toISOString();
                    test.promotions = promotions.filter((p) =>
                      manualPromos.includes(p.id),
                    );
                    test.cart.selections =
                      manualMode === "manual"
                        ? manualPromos.map(
                            (id) =>
                              ({
                                promotionId: id,
                                pairs: [],
                              }) as TestCase["cart"]["selections"][number],
                          )
                        : [];
                    delete (test as Partial<TestCase>).expectedCoveredUnits;
                    setSet({ ...set, cases: [...set.cases, test] });
                  }}
                >
                  Add case
                </button>
              </details>
              <details>
                <summary>
                  Advanced case editor · multiple lines and exact expectations
                </summary>
                <textarea
                  aria-label="Case JSON"
                  className="code"
                  rows={16}
                  value={fixture}
                  onChange={(e) => setFixture(e.target.value)}
                />
                <button
                  onClick={() => {
                    try {
                      const data = JSON.parse(fixture);
                      if (!data.cart || !data.name)
                        throw Error("Case requires name and cart");
                      setSet({ ...set, cases: [...set.cases, data] });
                    } catch (e) {
                      setError(String(e));
                    }
                  }}
                >
                  Append case JSON
                </button>
              </details>
              {results.length > 0 && (
                <section className="results">
                  <h2>
                    Results · {results.filter((r) => r.passed).length}/
                    {results.length} passed
                  </h2>
                  {results.map((r, i) => (
                    <article className="test-result" key={i}>
                      <b className={r.passed ? "saving" : "remove"}>
                        {r.passed ? "PASS" : "FAIL"} · {r.name}
                      </b>
                      {r.error ? (
                        <p>{r.error}</p>
                      ) : (
                        <>
                          <p>
                            Actual total: {formatMoney(r.evaluation.total)}
                            {r.optimalityProven === false
                              ? " · Optimality unproven"
                              : ""}
                          </p>
                          <Trace evaluation={r.evaluation} />
                        </>
                      )}
                    </article>
                  ))}
                </section>
              )}
            </section>
          </div>
        )}
      </fieldset>
      {busy && (
        <div className="working" role="status">
          Working…
        </div>
      )}
    </main>
  );
}
