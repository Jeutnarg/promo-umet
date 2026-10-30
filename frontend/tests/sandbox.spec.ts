import { test, expect, type Page } from "@playwright/test";
import { createRandomCart } from "../src/random-cart";
test("surprise carts respect diversity and quantity bounds", () => {
  for (const count of [1, 2, 7, 8, 20, 100]) {
    const items = Array.from({ length: count }, (_, i) => ({
      version: 1,
      item: {
        id: String(i),
        name: `Item ${i}`,
        price: 100,
        category: "Sample",
      },
    }));
    for (let trial = 0; trial < 20; trial++) {
      const lines = createRandomCart(items);
      const total = lines.reduce((n, l) => n + l.quantity, 0);
      expect(lines.length).toBeGreaterThanOrEqual(Math.ceil(count / 2));
      expect(lines.length).toBeLessThanOrEqual(count);
      expect(total).toBeGreaterThanOrEqual(count >= 8 ? 2 * count : 6);
      expect(total).toBeLessThanOrEqual(count >= 8 ? 3 * count : 20);
    }
  }
});
test("customer surprise and confirmed deletion hide internal IDs", async ({
  page,
}) => {
  await createFreshCustomerCart(page);
  await page.getByRole("button", { name: "Surprise me!", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: /In your cart \(/ }),
  ).not.toHaveText("In your cart (0)");
  await page.getByText("How this price was calculated").click();
  expect(await page.locator("body").innerText()).not.toMatch(
    /[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/i,
  );
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "Delete current cart" }).click();
  await expect(
    page.getByRole("heading", { name: /In your cart \(/ }),
  ).not.toHaveText("In your cart (0)");
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "Delete current cart" }).click();
  await expect(
    page.getByRole("heading", { name: "In your cart (0)" }),
  ).toBeVisible();
  await expect(
    page.getByText("Won't your empty box be oh so pretty!"),
  ).toBeVisible();
});
test("admin descriptions expiration filters and empty test feedback", async ({
  page,
}) => {
  await signInToWorkspace(page, "admin", "admin");
  const name = `Description fixture ${Date.now()}`;
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(
    page.getByLabel("Expiration (your local time; optional)"),
  ).not.toHaveValue("");
  await page
    .getByLabel("Customer description")
    .fill("A lovely saving for your merchandise.");
  await page
    .getByLabel("Expiration (your local time; optional)")
    .fill("2020-01-01T12:00");
  await expect(page.getByLabel("Name", { exact: true })).toBeDisabled();
  await page
    .getByRole("button", { name: "Save promotion", exact: true })
    .click();
  await expect(
    page.getByRole("status").filter({ hasText: "Promotion saved" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Edit promotion (Expired)" }),
  ).toBeVisible();
  await page.getByRole("radio", { name: "Expired", exact: true }).check();
  await expect(
    page.getByRole("button", { name: new RegExp(name) }),
  ).toBeVisible();
  await page
    .getByLabel("Expiration (your local time; optional)")
    .fill("2100-01-01T12:00");
  await expect(page.getByLabel("Name", { exact: true })).toBeEnabled();
  await expect(page.getByLabel("Customer description")).toHaveValue(
    "A lovely saving for your merchandise.",
  );
  await page.getByRole("button", { name: "Test workspace" }).click();
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  await expect(page.getByText("No test cases", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /The \$54 sketch/ }).click();
  await expect(page.getByText("No test cases", { exact: true })).toHaveCount(0);
});

async function signInToWorkspace(
  page: Page,
  app = "customer",
  user = "customer",
) {
  await page.goto(`http://localhost:${app === "admin" ? 3001 : 3000}`);
  await page.getByRole("button", { name: "Sign in to your workspace" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(`${user}-password`);
  await page.locator("#kc-login").click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
}
async function createFreshCustomerCart(page: Page) {
  await signInToWorkspace(page);
  await page.getByRole("button", { name: "+ New cart", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
}
async function addCatalogItem(page: Page, name: string) {
  await page.getByRole("button", { name: `Add ${name}`, exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
}
test.beforeEach(async ({ context }) => {
  await context.route("**/*", (route) => {
    const url = new URL(route.request().url());
    if (
      ["localhost", "127.0.0.1"].includes(url.hostname) ||
      ["data:", "blob:"].includes(url.protocol)
    )
      return route.continue();
    throw new Error(`External runtime request is forbidden: ${url.href}`);
  });
});
test("sketch checkout, return, submit, clone and fresh cart", async ({
  page,
}) => {
  await createFreshCustomerCart(page);
  await addCatalogItem(page, "Everyday tote");
  await page.getByLabel("Everyday tote quantity").fill("4");
  await page.getByLabel("Everyday tote quantity").blur();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
  await addCatalogItem(page, "Studio mug");
  await page.getByLabel("Studio mug quantity").fill("2");
  await page.getByLabel("Studio mug quantity").blur();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
  await page.getByRole("checkbox", { name: /BogoBongo/ }).click();
  await expect(page.getByRole("checkbox", { name: /BogoBongo/ })).toBeChecked();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
  await page.getByRole("checkbox", { name: /MOARSAV/ }).click();
  await expect(page.getByRole("checkbox", { name: /MOARSAV/ })).toBeChecked();
  await expect(page.locator(".grand-total")).toHaveText("Total$54.00");
  await page.getByRole("button", { name: "Checkout" }).click();
  await page.getByRole("button", { name: "Let me think" }).click();
  await page.getByRole("button", { name: "Checkout" }).click();
  const purchaseResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/orders") &&
      response.request().method() === "POST" &&
      response.status() === 200,
  );
  await page.getByRole("button", { name: "Buy Buy Buy" }).click();
  const submitted = await purchaseResponse;
  const purchase = await submitted.json();
  const authorization = submitted.request().headers()["authorization"];
  expect(
    (
      await page.request.get(
        `http://localhost:8081/api/v1/carts/${purchase.cart.id}`,
        { headers: { authorization } },
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await page.request.post("http://localhost:8081/api/v1/orders", {
        headers: { authorization, "Idempotency-Key": crypto.randomUUID() },
        data: { quoteId: purchase.quoteId },
      })
    ).status(),
  ).toBe(409);
  await expect(page.getByText("SIMULATED ORDER RECORDED")).toBeVisible();
  await page.getByRole("button", { name: "Clone this cart" }).click();
  await expect(page.locator(".grand-total")).toHaveText("Total$54.00");
  await page.getByRole("button", { name: "+ New cart", exact: true }).click();
  await expect(
    page.getByText("Won't your empty box be oh so pretty!"),
  ).toBeVisible();
});
test("optimizer applies with transparent proof status", async ({ page }) => {
  await createFreshCustomerCart(page);
  await addCatalogItem(page, "Everyday tote");
  await addCatalogItem(page, "Everyday tote");
  await page.getByRole("button", { name: "Suggest best" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Optimal promotions found!" }),
  ).toBeVisible();
  await expect(
    page.locator(".banner").filter({ hasText: "Optimal promotions found!" }),
  ).toHaveText(
    "Optimal promotions found! What an artful deal! 1 units discounted.×",
  );
  await expect(page.locator(".grand-total")).toHaveText("Total$20.00");
  await page.getByRole("button", { name: "Swindle", exact: true }).click();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "Apparently optimal promotions applied!" }),
  ).toBeVisible();
  await expect(
    page.locator(".banner").filter({ hasText: "Apparently optimal" }),
  ).not.toContainText("coverage");
  await page.getByLabel("Everyday tote quantity").fill("3");
  await expect(page.locator(".banner")).toHaveCount(0);
  await page.getByLabel("Everyday tote quantity").blur();
});

test("saved carts display prices and quantities in newest-first order with hover timestamps", async ({
  page,
}) => {
  await createFreshCustomerCart(page);
  await addCatalogItem(page, "Everyday tote");
  await addCatalogItem(page, "Studio mug");
  const saved = page.getByLabel("Saved carts");
  await expect(saved.locator("option").first()).toHaveText("$35.00 · 2 items");
  await expect(saved).toHaveAttribute("title", /\d/);
  await expect(saved.locator("option").first()).toHaveAttribute("title", /\d/);
  await expect(
    page.getByText(
      '"Suggest best" finds you a great deal and tries to find the absolute best!',
    ),
  ).toBeVisible();
});

test("promotion filter radios are aligned in two columns", async ({ page }) => {
  await signInToWorkspace(page, "admin", "admin");
  const radios = page.locator(".promotion-filters input[type=radio]");
  const [first, second, third] = await Promise.all([
    radios.nth(0).boundingBox(),
    radios.nth(1).boundingBox(),
    radios.nth(2).boundingBox(),
  ]);
  expect(Math.abs(first!.y - second!.y)).toBeLessThan(2);
  expect(Math.abs(first!.x - third!.x)).toBeLessThan(2);
  expect(second!.x).toBeGreaterThan(first!.x);
});
test("administrator fixture execution, export and import", async ({ page }) => {
  await signInToWorkspace(page, "admin", "admin");
  await page.getByRole("button", { name: "Test workspace" }).click();
  await page.getByRole("button", { name: /The \$54 sketch/ }).click();
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  await expect(page.getByText("PASS · Four totes, two mugs")).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export JSON" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toContain(".json");
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream!) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString();
  await page.getByLabel("Paste a test set").fill(text);
  await page.getByRole("button", { name: "Import new set" }).click();
  await page.getByLabel("Test set name").fill(`Imported ${Date.now()}`);
  await page.getByRole("button", { name: "Save set", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Test set saved" }),
  ).toBeVisible();
});
test("customer cannot open administrator functionality", async ({ page }) => {
  await signInToWorkspace(page, "admin", "customer");
  await expect(
    page.getByRole("heading", { name: "Administrator access required" }),
  ).toBeVisible();
});

test("administrator dashboard counts purchases once and preserves actual promotion costs", async ({
  page,
  context,
}) => {
  let authorization = "";
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/"))
      authorization = request.headers()["authorization"] || authorization;
  });
  await signInToWorkspace(page, "admin", "admin");
  await expect(page.getByRole("button", { name: /BogoBongo/ })).toBeVisible();
  await page
    .getByRole("button", { name: "Promotion dashboard", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Promotion usage & cost" }),
  ).toBeVisible();
  const baseline = await (
    await context.request.get(
      "http://localhost:8081/api/v1/admin/dashboard/promotions",
      { headers: { authorization } },
    )
  ).json();
  const created = await context.request.post(
    "http://localhost:8081/api/v1/carts",
    {
      headers: { authorization },
      data: {
        id: "ignored",
        version: 0,
        lines: [
          {
            id: "dashboard-tote",
            itemId: "sample-one",
            name: "Dashboard tote",
            quantity: 2,
            price: 1000,
            category: "Accessories",
          },
        ],
        shipping: 1000,
        selections: [
          { promotionId: "bogobongo", lineId: "dashboard-tote", pairs: [] },
        ],
        completed: false,
      },
    },
  );
  expect(created.ok()).toBeTruthy();
  const view = await created.json();
  const quoted = await context.request.post(
    `http://localhost:8081/api/v1/carts/${view.cart.id}/quotes`,
    { headers: { authorization }, data: { version: view.cart.version } },
  );
  expect(quoted.ok()).toBeTruthy();
  const quote = await quoted.json();
  const key = crypto.randomUUID();
  for (let attempt = 0; attempt < 2; attempt++) {
    const purchased = await context.request.post(
      "http://localhost:8081/api/v1/orders",
      {
        headers: { authorization, "Idempotency-Key": key },
        data: { quoteId: quote.id },
      },
    );
    expect(purchased.ok()).toBeTruthy();
  }
  await page
    .getByRole("button", { name: "Refresh dashboard", exact: true })
    .click();
  await expect(page.getByTestId("purchase-count")).toHaveText(
    (baseline.purchaseCount + 1).toLocaleString(),
  );
  await expect(page.getByTestId("promotion-cost")).toHaveText(
    new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: "USD",
    }).format((baseline.totalPromotionCost + 1000) / 100),
  );
  const report = await (
    await context.request.get(
      "http://localhost:8081/api/v1/admin/dashboard/promotions",
      { headers: { authorization } },
    )
  ).json();
  const previous = baseline.promotions.find(
    (usage: { promotionId: string }) => usage.promotionId === "bogobongo",
  );
  const current = report.promotions.find(
    (usage: { promotionId: string }) => usage.promotionId === "bogobongo",
  );
  expect(current.purchaseCount).toBe(previous.purchaseCount + 1);
  expect(current.cost).toBe(previous.cost + 1000);
  await page.screenshot({
    path: "test-results/promotion-dashboard.png",
    fullPage: true,
  });
});
test("login and logout need no external network", async ({ page }) => {
  await signInToWorkspace(page);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign in to your workspace" }),
  ).toBeVisible();
});
test("unauthenticated and invalid bearer requests fail", async ({
  request,
}) => {
  expect(
    (await request.get("http://localhost:8081/api/v1/items")).status(),
  ).toBe(401);
  expect(
    (
      await request.get("http://localhost:8081/api/v1/items", {
        headers: { Authorization: "Bearer invalid" },
      })
    ).status(),
  ).toBe(401);
});
test("administrator creates and disables a promotion through the editor", async ({
  page,
}) => {
  await signInToWorkspace(page, "admin", "admin");
  const name = "Reusable editor fixture";
  await expect(page.getByRole("button", { name: /BogoBongo/ })).toBeVisible();
  const existing = page.getByRole("button", { name: new RegExp(name) });
  if (await existing.count()) await existing.first().click();
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByLabel("Reduction ($)", { exact: true }).fill("1.25");
  await page.getByLabel("Enabled", { exact: true }).check();
  await page
    .getByRole("button", { name: "Save promotion", exact: true })
    .click();
  await expect(
    page.getByRole("status").filter({ hasText: "Promotion saved" }),
  ).toBeVisible();
  await page.getByLabel("Enabled", { exact: true }).uncheck();
  await page
    .getByRole("button", { name: "Save promotion", exact: true })
    .click();
  await expect(page.locator(".promotion-card.chosen")).toContainText(
    "Disabled",
  );
});
test("expiration removes selection and shows a banner", async ({
  page,
  context,
}) => {
  let authorization = "";
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/"))
      authorization = request.headers()["authorization"] || authorization;
  });
  await signInToWorkspace(page, "admin", "admin");
  await expect(page.getByRole("button", { name: /BogoBongo/ })).toBeVisible();
  const id = "e2e-expiry";
  const allPromotions = await (
    await context.request.get("http://localhost:8081/api/v1/promotions", {
      headers: { authorization },
    })
  ).json();
  let promotion: any = {
    id,
    version:
      allPromotions.find((p: { id: string }) => p.id === id)?.version || 0,
    name: "Short-lived fixture",
    scope: "items",
    exclusivity: "stackable",
    strategy: "flat",
    details: { amount: 10 },
    itemIds: [],
    categories: [],
    enabled: true,
    schemaVersion: 1,
    expiresAt: new Date(Date.now() + 20000).toISOString(),
  };
  const response = await context.request.put(
    `http://localhost:8081/api/v1/admin/promotions/${id}`,
    { headers: { authorization }, data: promotion },
  );
  expect(response.ok()).toBeTruthy();
  promotion = await response.json();
  await page.goto("http://localhost:3000");
  await page.getByRole("button", { name: "Sign in to your workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Make your cart work harder." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "+ New cart", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Updating" }),
  ).toHaveCount(0);
  await addCatalogItem(page, "Everyday tote");
  await page.getByRole("checkbox", { name: /Short-lived fixture/ }).click();
  await expect(
    page.getByRole("checkbox", { name: /Short-lived fixture/ }),
  ).toBeChecked();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "An unavailable promotion was removed" }),
  ).toBeVisible({ timeout: 30000 });
  await expect(page.locator(".grand-total")).toHaveText("Total$20.00");
  await context.request.put(
    `http://localhost:8081/api/v1/admin/promotions/${id}`,
    { headers: { authorization }, data: { ...promotion, enabled: false } },
  );
});
test("local data survives a service restart", async ({ page }) => {
  await signInToWorkspace(page, "admin", "admin");
  await page.getByRole("button", { name: "Test workspace" }).click();
  const marker = page.getByRole("button", { name: /Persistence checkpoint/ });
  if (process.env.EXPECT_PERSISTED === "1") await expect(marker).toBeVisible();
  else if ((await marker.count()) === 0) {
    await page.getByLabel("Test set name").fill("Persistence checkpoint");
    await page.getByRole("button", { name: "Save set", exact: true }).click();
    await expect(marker).toBeVisible();
  }
});
test("mobile layout and desktop visual checkpoints", async ({ page }) => {
  await createFreshCustomerCart(page);
  await addCatalogItem(page, "Everyday tote");
  await addCatalogItem(page, "Studio mug");
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.screenshot({
    path: "test-results/customer-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await expect(page.getByRole("button", { name: "Checkout" })).toBeVisible();
  await page.screenshot({
    path: "test-results/customer-mobile.png",
    fullPage: true,
  });
});
