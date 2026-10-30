import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { auth, config, signOutUser } from "./api";
import { Customer } from "./customer";
import { Admin } from "./admin";
import "@fontsource/raleway/400.css";
import "@fontsource/raleway/500.css";
import "@fontsource/raleway/600.css";
import "@fontsource/raleway/700.css";
import "./style.css";

function App() {
  const [ready, setReady] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [admin, setAdmin] = useState(false);
  useEffect(() => {
    void (async () => {
      try {
        const callback = new URLSearchParams(location.search).has("code");
        const user = callback
          ? await auth.signinRedirectCallback()
          : await auth.getUser();
        if (callback) history.replaceState({}, "", "/");
        if (user && !user.expired) {
          setName(
            user.profile.preferred_username ||
              user.profile.name ||
              "Sandbox user",
          );
          const token = JSON.parse(
            atob(
              user.access_token
                .split(".")[1]
                .replace(/-/g, "+")
                .replace(/_/g, "/"),
            ),
          );
          setAdmin(
            (
              token.realm_access?.roles ||
              token["cognito:groups"] ||
              []
            ).includes("admin"),
          );
        }
      } catch (e) {
        setError(String(e));
      }
      setReady(true);
    })();
    const expired = () => {
      setName("");
      setError("Your session expired. Sign in to resume your saved work.");
    };
    auth.events.addAccessTokenExpired(expired);
    return () => auth.events.removeAccessTokenExpired(expired);
  }, []);
  const signIn = () => auth.signinRedirect().catch((e) => setError(String(e)));
  return (
    <>
      <header className="topbar">
        <a className="brand" href="/">
          p<span>✳</span> <b>promotion studio</b>
        </a>
        <nav>
          <a
            className={config.app === "customer" ? "active" : ""}
            href={config.customerUrl}
          >
            Your cart
          </a>
          <a
            className={config.app === "admin" ? "active" : ""}
            href={config.adminUrl}
          >
            Administration
          </a>
        </nav>
        <div className="account">
          {name && (
            <>
              <span>{name}</span>
              <button
                className="quiet"
                onClick={() =>
                  void signOutUser().catch((e) => setError(String(e)))
                }
              >
                Sign out
              </button>
            </>
          )}
        </div>
      </header>
      {error && (
        <div role="alert" className="banner error">
          {error}
        </div>
      )}
      {!ready ? (
        <main>
          <p>Connecting to your local workspace…</p>
        </main>
      ) : !name ? (
        <main className="welcome">
          <span className="eyebrow">YOUR PRICE. EVERY POSSIBILITY.</span>
          <h1>
            A little strategy.
            <br />A better total.
          </h1>
          <p>
            Explore promotions, see every saving, and find the combination that
            works for your cart.
          </p>
          <button className="primary" onClick={() => void signIn()}>
            Sign in to your workspace →
          </button>
          <p className="muted">Simulated orders. Real explanations.</p>
        </main>
      ) : config.app === "admin" ? (
        admin ? (
          <Admin />
        ) : (
          <main>
            <h1>Administrator access required</h1>
            <p>Sign in with your local administrator account.</p>
          </main>
        )
      ) : (
        <Customer />
      )}
      <footer>
        <span>PROMOTION STUDIO</span>
        <span>A transparent pricing sandbox · No payment collected</span>
      </footer>
    </>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
