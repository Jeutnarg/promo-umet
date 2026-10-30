import type { Evaluation, Cart } from "./types";
import { formatMoney } from "./api";

export function Breakdown({
  evaluation,
  cart,
}: {
  evaluation: Evaluation;
  cart?: Cart;
}) {
  return (
    <div className="breakdown">
      {cart?.lines.map((line) => {
        const savings = evaluation.steps
          .flatMap((step) => step.allocations)
          .filter((a) => a.lineId === line.id)
          .reduce((sum, a) => sum + a.saving, 0);
        return (
          <div className="receipt-row" key={line.id}>
            <span>
              {line.name} <small>× {line.quantity}</small>
            </span>
            <span>
              {formatMoney(line.quantity * line.price)} − {formatMoney(savings)}{" "}
              = <b>{formatMoney(line.quantity * line.price - savings)}</b>
            </span>
          </div>
        );
      })}
      <div className="receipt-row">
        <span>Merchandise</span>
        <span>{formatMoney(evaluation.originalMerchandise)}</span>
      </div>
      {evaluation.steps.map((step) => (
        <div className="receipt-row saving" key={step.promotionId}>
          <span>{step.name}</span>
          <span>−{formatMoney(step.saving)}</span>
        </div>
      ))}
      <div className="receipt-row">
        <span>Original shipping</span>
        <span>{formatMoney(evaluation.originalShipping)}</span>
      </div>
      <div className="receipt-row grand-total">
        <span>Total</span>
        <span>{formatMoney(evaluation.total)}</span>
      </div>
      <p className="muted">
        You save {formatMoney(evaluation.savings)} · USD · No tax
      </p>
    </div>
  );
}
export function Trace({
  evaluation,
  cart,
}: {
  evaluation: Evaluation;
  cart?: Cart;
}) {
  const getUnitDisplayName = (id: string) => {
    if (id === "shipping") return "Shipping";
    const split = id.lastIndexOf(":");
    const line = cart?.lines.find((l) => l.id === id.slice(0, split));
    return `${line?.name || "Item"} #${Number(id.slice(split + 1)) + 1}`;
  };
  return (
    <details className="trace">
      <summary>How this price was calculated</summary>
      <ol>
        {evaluation.steps.map((step) => (
          <li key={step.promotionId}>
            <b>{step.name}</b>{" "}
            <span className="saving">−{formatMoney(step.saving)}</span>
            <ul>
              {step.allocations.map((a) => (
                <li key={a.unitId}>
                  {getUnitDisplayName(a.unitId)}: −{formatMoney(a.saving)}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
      {evaluation.rejected.map((r) => (
        <p className="warning" key={r.promotionId}>
          {r.reason}
        </p>
      ))}
      <p>
        {evaluation.coveredUnits.length} units discounted;{" "}
        {evaluation.uncoveredUnits.length} uncovered.
      </p>
      {evaluation.uncoveredUnits.length > 0 && (
        <p className="muted">
          Uncovered:{" "}
          {evaluation.uncoveredUnits.map(getUnitDisplayName).join(", ")}
        </p>
      )}
    </details>
  );
}
