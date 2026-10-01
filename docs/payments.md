# FreeKiosk Payment Terminal

**Card payments from the kiosk web app, through a payment SDK hosted by FreeKiosk**

<p>
  <a href="README.md">Docs Home</a> •
  <a href="features-and-modes.md">Features & Modes</a> •
  <a href="silent-printing.md">Silent Printing</a>
</p>


> [!WARNING]
> **Status: experimental, not yet verified on hardware.** The JavaScript side is covered by unit
> tests. The native Kotlin side, including the Stripe provider, has **not yet been compiled against
> the Stripe SDK or run on a device**. Test it in Stripe test mode with a simulated reader before
> you take real payments.


## Table of Contents

- [How It Works](#how-it-works)
- [Building](#building)
- [Setup](#setup)
- [The JavaScript API](#the-javascript-api)
- [Your Backend](#your-backend)
- [Tap to Pay vs. External Readers](#tap-to-pay-vs-external-readers)
- [What FreeKiosk Changes During a Payment](#what-freekiosk-changes-during-a-payment)
- [Security](#security)
- [Limitations](#limitations)


## How It Works

FreeKiosk hosts the payment SDK, and the kiosk web app drives it. The web app does not embed the
SDK and never holds a secret key.

```
web app (WebView) ── window.FreeKiosk.payments ──► FreeKiosk ──► Stripe Terminal SDK ──► reader
      ▲                                                  │
      └───── connection token from YOUR backend ◄────────┘  (tokenRequest event)
```

- **One provider at a time.** The first version supports **Stripe Terminal**. The bridge has a
  generic layer (discover, connect, collect, cancel) and a provider passthrough
  (`invoke(method, args)`) for anything provider-specific.
- **One client at a time.** The terminal is a single physical resource. The page holds a session,
  and that session is released when the page navigates, reloads or unmounts. Releasing it
  cancels whatever the page had running.
- **Payments are created server-side.** Your backend creates the PaymentIntent. The page passes
  only its `client_secret`, and FreeKiosk collects and confirms it. Capture and refunds stay on
  your backend.


## Building

The payment SDK is **not** in the default builds. It is proprietary and large, and it needs
Android 8.0 (API 26). Add `-Ppayments` to any build:

```bash
cd android && ./gradlew assembleRelease -Ppayments
```

- Without the flag, `src/nopayments/` compiles in a stub. `window.FreeKiosk.payments` still
  exists, but every call rejects with `UNSUPPORTED`.
- `-Ppayments` raises `minSdk` to 26 for that build only.
- It combines with `-Pcloudprovi` and `-Pplaystore`. Neither combination has been tried yet.
- Read the [Stripe Terminal Terms](https://stripe.com/legal/terminal) before distributing an APK
  that contains the SDK.


## Setup

1. Install a `-Ppayments` build.
2. Go to **Settings > General > Payment Terminal** and turn on **Enable Payment Terminal Bridge**.
3. Add the **https origin** of your payment web app, e.g. `https://pos.example.com`.
   - The list fails closed: with no origin, no page can take payments.
   - `http://` origins are ignored.
4. Check the readiness list in the same section. It shows what blocks external readers and Tap to
   Pay on this device.
5. For Bluetooth readers, the Bluetooth and location permissions have to be granted.
   - Under Device Owner, FreeKiosk grants them itself.
   - Location services must be switched on. Stripe disables payments without them.


## The JavaScript API

Wait for `freekiosk:ready`, because FreeKiosk injects the API after the page loads.

```js
window.addEventListener('freekiosk:ready', async () => {
  const pay = window.FreeKiosk.payments;

  // 1. Tokens come from YOUR backend. FreeKiosk asks for one whenever the SDK needs it.
  pay.setTokenProvider(async () => {
    const res = await fetch('/api/terminal/connection-token', { method: 'POST', credentials: 'include' });
    return (await res.json()).secret;
  });

  // 2. Events: customer prompts, connection and payment status, reader updates.
  pay.on('readerMessage', (msg) => showPrompt(msg));          // {kind: 'input'|'display', ...}
  pay.on('connectionStatus', ({ status }) => setStatus(status));

  // 3. Initialise, discover, connect.
  await pay.initialize('stripe', { locationId: 'tml_123' });
  let readers = [];
  const off = pay.on('readersDiscovered', (e) => { readers = e.readers; });
  const discovery = pay.discoverReaders({ mode: 'bluetooth', simulated: true });
  // ... once the reader you want is listed:
  await pay.connectReader(readers[0].id);
  await pay.cancel(discovery.opId).catch(() => {});   // stop discovery if it is still running
  off();

  // 4. Collect a payment your backend created.
  const { clientSecret } = await (await fetch('/api/terminal/payment-intent', { method: 'POST' })).json();
  const collecting = pay.collectPayment({ clientSecret });
  // cancelButton.onclick = () => pay.cancel(collecting.opId);
  const intent = await collecting;   // {id, status, amount, amountReceived, currency}

  // 5. Your backend captures it, and confirms the final state (see "Your Backend").
});
```

| Method | Description |
|---|---|
| `getInfo()` | `{supported, providers, activeProvider, sessionOpen, transactionActive}` |
| `getReadiness(provider?)` | Device checklist: `{checks[], tapToPayReady, bluetoothReady}` |
| `setTokenProvider(fn)` | `fn(request)` returns a connection token (string) from your backend |
| `initialize(provider, config)` | `config`: `locationId`, `verboseLogs` |
| `discoverReaders(options)` | `mode`: `tapToPay` \| `bluetooth` \| `usb` \| `internet`; `simulated`, `timeout`, `locationId`. Resolves when discovery ends. Readers arrive as `readersDiscovered` events. |
| `connectReader(readerId, options)` | `locationId`, `autoReconnect`, `merchantDisplayName` (Tap to Pay), `failIfInUse` (internet) |
| `disconnectReader()` | |
| `collectPayment({clientSecret, skipTipping?})` | Collects and confirms a server-created PaymentIntent |
| `cancel(opId)` | Cancels a discovery, a collection or an `invoke` by its `opId` |
| `setReaderDisplay(cart)` / `clearReaderDisplay()` | `cart`: `{currency, total, tax?, lineItems: [{description, quantity, amount}]}` |
| `installReaderUpdate()` | Progress arrives as `readerUpdate` events |
| `getStatus()` | Connection status, payment status, connected reader |
| `invoke(method, args)` | Stripe passthrough: `processSetupIntent {clientSecret, allowRedisplay?}`, `cancelPaymentIntent {clientSecret}`, `cancelReconnect`, `cancelReaderUpdate` |
| `on(type, cb)` | Returns an unsubscribe function. `'*'` receives every event. |
| `close()` | Releases the session |

Cancellable calls (`discoverReaders`, `collectPayment`, `invoke`) return a promise with an
`opId` property.

**Events:** `readersDiscovered`, `connectionStatus`, `paymentStatus`, `readerMessage`,
`readerUpdate`, `readerDisconnected`, `readerReconnect`, `battery`, `transactionActive`,
`sessionClosed`. `tokenRequest` is answered for you by `setTokenProvider`.

**Error codes:**
- Bridge: `ORIGIN_NOT_ALLOWED`, `UNSUPPORTED`, `BUSY`, `NO_SESSION`, `NOT_INITIALIZED`,
  `NOT_CONNECTED`, `INVALID_ARGUMENT`, `UNKNOWN_OP`, `UNKNOWN_METHOD`, `UNKNOWN_READER`,
  `TOKEN_TIMEOUT`, `TOKEN_FAILED`, `CANCELLED`.
- Stripe: its own `TerminalErrorCode` names, such as `CARD_READ_TIMED_OUT` and
  `DECLINED_BY_STRIPE_API`.


## Your Backend

- **Connection tokens.** Add an endpoint that calls `POST /v1/terminal/connection_tokens` with
  your secret key and returns the `secret`. Authenticate it. Stripe warns that anyone who can call
  it can take payments with your account. Don't cache tokens.
- **PaymentIntents.** Create them with `payment_method_types: ['card_present']` and
  `capture_method: 'manual'` or `'automatic'`. Capture on the server.
- **Reconcile on the server.** Use webhooks or read the PaymentIntent. Never treat the page's result,
  or a missing result, as final: a reboot, a crash or a lost network can end the page before the
  answer arrives.


## Tap to Pay vs. External Readers

| | External reader (Bluetooth / USB / internet) | Tap to Pay (this device's NFC) |
|---|---|---|
| Card data and PIN | Stay on the reader | Handled by the SDK on this device |
| Device requirements | Bluetooth, location | Android 13+, NFC, Google Mobile Services, hardware keystore, security patch < 12 months, unmodified OS |
| Developer options / USB debugging | Allowed | **Must be off** (switch them off after ADB provisioning) |
| Accessibility services | Allowed | **None may run**: PIN entry fails. The default (GitHub) build's accessibility service must be off; `-Pcloudprovi`/`-Pplaystore` builds don't include it |
| Screen overlays | Allowed | Must be gone during PIN entry. FreeKiosk takes its own down (see below) |
| Debug APK | Works | Simulated reader only |

We recommend **external readers** for kiosks. They avoid nearly every conflict with kiosk
features.


## What FreeKiosk Changes During a Payment

While a card is being read:

- **The watchdog** does not relaunch FreeKiosk.
- **Lock task and immersive mode** are not re-entered. The SDK's own screen comes and goes.
- **Remote screenshots** (REST, MQTT, cloud) are refused.
- **URL rotation, the URL planner, inactivity return and the screensaver** wait.
- **Cloud APK installs**, self-updates included, wait for the next poll.
- **Tap to Pay only:** FreeKiosk's overlay windows are taken down, including the 5-tap return
  button, the status bar, the dim screensaver and the blocking regions. They come back when the
  payment ends.

Every one of these guards expires after 5 minutes, so a lost "payment ended" signal cannot pin
them on.


## Security

- **Remote JavaScript is refused** (REST `/api/js`, MQTT `execute_js`, cloud `execute_js`) while
  the bridge is enabled. Injected JS could otherwise drive the terminal as if it were the page.
- **Origin allowlist.** The allowlist is checked against the page's origin and the main frame's
  URL, as for Silent Printing.
  - A per-load nonce keeps iframes out.
  - Third-party scripts on an allowed origin **do** have full access, so keep that origin clean.
- **Settings stay local.** The payment settings are kept out of backups and out of FreeKiosk Cloud
  configuration, so no remote channel can widen who may take payments.
- **No secrets in FreeKiosk.** It never stores a Stripe secret key or connection token, and it
  never logs payment payloads.
- **PCI.** Card data stays inside the SDK or the reader. The merchant who deploys FreeKiosk is
  still responsible for its payment setup's compliance.


## Limitations

- **Stripe only.** An Adyen provider (nexo Terminal API passthrough) is planned. Its SDK sits in a
  credentialed Maven repository and every version expires after six months, so it can never be
  part of a public build.
- **WebView only.** External Android apps cannot use the bridge yet.
- **Connection tokens come from the page.** A backend URL that FreeKiosk calls itself is not
  implemented.
- **Plain HTTP REST.** The REST API still answers read endpoints over plain HTTP, and its API key is
  optional. Set one.
