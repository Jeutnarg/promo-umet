import { useEffect, useState } from "react";
import { client, formatMoney } from "./api";
import type { PromotionDashboard as DashboardData } from "./types";

export function PromotionDashboard() {
  const [dashboard, setDashboard] = useState<DashboardData>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function refreshDashboard() {
    setBusy(true);
    setError("");
    try {
      setDashboard(await client.getPromotionDashboard());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    void refreshDashboard();
  }, []);

  return (
    <section
      className="promotion-dashboard"
      aria-label="Promotion usage dashboard"
    >
      <div className="section-heading">
        <div>
          <h2>Promotion usage &amp; cost</h2>
          <p className="muted">
            All-time totals from recorded purchases. Cart previews and
            administrator tests do not count.
          </p>
        </div>
        <button disabled={busy} onClick={() => void refreshDashboard()}>
          Refresh dashboard
        </button>
      </div>
      {error && (
        <div role="alert" className="banner error">
          {error}
        </div>
      )}
      {busy && <p role="status">Loading purchase statistics…</p>}
      {dashboard && (
        <>
          <div className="dashboard-metrics">
            <article className="panel">
              <span className="eyebrow">RECORDED PURCHASES</span>
              <strong data-testid="purchase-count">
                {dashboard.purchaseCount.toLocaleString()}
              </strong>
            </article>
            <article className="panel">
              <span className="eyebrow">TOTAL PROMOTION COST</span>
              <strong data-testid="promotion-cost">
                {formatMoney(dashboard.totalPromotionCost)}
              </strong>
            </article>
            <article className="panel">
              <span className="eyebrow">TOTAL ORDER VALUE</span>
              <strong>{formatMoney(dashboard.totalOrderValue)}</strong>
            </article>
          </div>
          <div className="panel dashboard-table-wrap">
            <table className="dashboard-table">
              <caption>
                Promotion costs in USD, including shipping discounts. Usage
                counts purchases where each offer applied; historical versions
                are combined.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Promotion</th>
                  <th scope="col">Purchases</th>
                  <th scope="col">Units discounted</th>
                  <th scope="col">Discount cost</th>
                  <th scope="col">Shipping cost</th>
                  <th scope="col">Last used</th>
                </tr>
              </thead>
              <tbody>
                {dashboard.promotions.map((usage) => (
                  <tr key={usage.promotionId}>
                    <th scope="row">{usage.name}</th>
                    <td>{usage.purchaseCount.toLocaleString()}</td>
                    <td>{usage.discountedUnits.toLocaleString()}</td>
                    <td>{formatMoney(usage.cost)}</td>
                    <td>{formatMoney(usage.shippingCost)}</td>
                    <td>
                      {usage.lastUsedAt
                        ? new Date(usage.lastUsedAt).toLocaleString()
                        : "---"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {dashboard.promotions.length === 0 && (
              <p className="empty">No promotions have been created yet.</p>
            )}
          </div>
          {dashboard.purchaseCount === 0 && (
            <p className="muted">
              No purchases recorded yet. Completed purchases will appear here
              automatically.
            </p>
          )}
          <p className="muted small">
            Updated {new Date(dashboard.generatedAt).toLocaleString()}. These
            are simulated orders; no payment is collected.
          </p>
        </>
      )}
    </section>
  );
}
