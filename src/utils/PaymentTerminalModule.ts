import { NativeModules, NativeEventEmitter } from 'react-native';
import type {
  PaymentBridgeInfo,
  PaymentReadiness,
  PaymentTerminalEvent,
} from '../types/payments';

/**
 * Bridge to PaymentTerminalModule.kt. Arguments and results cross as JSON strings so the
 * provider-specific `invoke` can carry any shape; this wrapper does the (de)serialising.
 *
 * Never log what goes through here: it carries client secrets and connection tokens.
 */
interface NativePaymentTerminalModule {
  getInfo(): Promise<string>;
  getReadiness(providerId: string | null): Promise<string>;
  openSession(owner: string): Promise<string>;
  closeSession(sessionId: string): Promise<null>;
  closeSessionsOwnedBy(owner: string, reason: string): Promise<null>;
  initialize(sessionId: string, providerId: string, configJson: string): Promise<string>;
  discoverReaders(sessionId: string, opId: string, optionsJson: string): Promise<string>;
  connectReader(sessionId: string, readerId: string, optionsJson: string): Promise<string>;
  disconnectReader(sessionId: string): Promise<string>;
  collectPayment(sessionId: string, opId: string, requestJson: string): Promise<string>;
  cancel(sessionId: string, opId: string): Promise<string>;
  setReaderDisplay(sessionId: string, cartJson: string): Promise<string>;
  clearReaderDisplay(sessionId: string): Promise<string>;
  installReaderUpdate(sessionId: string): Promise<string>;
  getStatus(sessionId: string): Promise<string>;
  invoke(sessionId: string, opId: string, method: string, argsJson: string): Promise<string>;
  provideToken(sessionId: string, requestId: string, token: string): Promise<null>;
  failTokenRequest(sessionId: string, requestId: string, message: string): Promise<null>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const EVENT = 'paymentTerminalEvent';

/** Session owner for the kiosk WebView. Must match PaymentOwners.WEBVIEW in Kotlin. */
export const WEBVIEW_OWNER = 'webview';

const native: NativePaymentTerminalModule | undefined = NativeModules.PaymentTerminalModule;

if (!native) {
  console.error('[PaymentTerminalModule] Native module not found. Did you rebuild the app?');
}

const emitter = native ? new NativeEventEmitter(NativeModules.PaymentTerminalModule) : null;

/** Mirrors PaymentTerminalManager.isTransactionActive for the JS-side guards. */
let transactionActive = false;
let transactionStartedAt = 0;
/** Same bound as TRANSACTION_MAX_LIFETIME_MS in Kotlin, so a lost "end" event cannot pin it. */
const TRANSACTION_MAX_LIFETIME_MS = 5 * 60_000;

const parse = <T,>(json: string | null | undefined): T =>
  (json ? JSON.parse(json) : {}) as T;

const unsupported = () =>
  Promise.reject(Object.assign(new Error('Payment terminal bridge not available'), { code: 'UNSUPPORTED' }));

const call = <T,>(fn: (m: NativePaymentTerminalModule) => Promise<string>): Promise<T> =>
  native ? fn(native).then((json) => parse<T>(json)) : unsupported();

const callVoid = (fn: (m: NativePaymentTerminalModule) => Promise<unknown>): Promise<void> =>
  native ? fn(native).then(() => undefined) : unsupported();

const handlers = new Set<(event: PaymentTerminalEvent) => void>();

const dispatch = (raw: string) => {
  let event: PaymentTerminalEvent;
  try {
    event = JSON.parse(raw);
  } catch {
    return;
  }
  if (event.type === 'transactionActive') {
    transactionActive = event.payload?.active === true;
    transactionStartedAt = transactionActive ? Date.now() : 0;
  } else if (event.type === 'sessionClosed') {
    transactionActive = false;
    transactionStartedAt = 0;
  }
  handlers.forEach((handler) => handler(event));
};

/** Subscribe to every payment event, for every session. Returns an unsubscribe function. */
export function onPaymentTerminalEvent(handler: (event: PaymentTerminalEvent) => void): () => void {
  handlers.add(handler);
  return () => {
    handlers.delete(handler);
  };
}

// Subscribed once for the app's lifetime: the JS-side guards need the transaction flag
// even when no page is listening.
emitter?.addListener(EVENT, dispatch);

/**
 * True while a card is being read. Page reloads, URL rotation, the screensaver and
 * self-updates wait for it to clear, because interrupting leaves the payment outcome unknown.
 */
export function isPaymentTransactionActive(): boolean {
  if (transactionActive && Date.now() - transactionStartedAt > TRANSACTION_MAX_LIFETIME_MS) {
    transactionActive = false;
  }
  return transactionActive;
}

const PaymentTerminal = {
  isAvailable: (): boolean => !!native,

  getInfo: () => call<PaymentBridgeInfo>((m) => m.getInfo()),
  getReadiness: (providerId?: string | null) =>
    call<PaymentReadiness>((m) => m.getReadiness(providerId ?? null)),

  openSession: (owner: string): Promise<string> =>
    native ? native.openSession(owner) : unsupported(),
  closeSession: (sessionId: string) => callVoid((m) => m.closeSession(sessionId)),
  closeSessionsOwnedBy: (owner: string, reason: string) =>
    callVoid((m) => m.closeSessionsOwnedBy(owner, reason)),

  initialize: (sessionId: string, providerId: string, config: object = {}) =>
    call<Record<string, unknown>>((m) => m.initialize(sessionId, providerId, JSON.stringify(config))),
  discoverReaders: (sessionId: string, opId: string, options: object = {}) =>
    call<Record<string, unknown>>((m) => m.discoverReaders(sessionId, opId, JSON.stringify(options))),
  connectReader: (sessionId: string, readerId: string, options: object = {}) =>
    call<Record<string, unknown>>((m) => m.connectReader(sessionId, readerId, JSON.stringify(options))),
  disconnectReader: (sessionId: string) =>
    call<Record<string, unknown>>((m) => m.disconnectReader(sessionId)),
  collectPayment: (sessionId: string, opId: string, request: object) =>
    call<Record<string, unknown>>((m) => m.collectPayment(sessionId, opId, JSON.stringify(request))),
  cancel: (sessionId: string, opId: string) =>
    call<Record<string, unknown>>((m) => m.cancel(sessionId, opId)),
  setReaderDisplay: (sessionId: string, cart: object) =>
    call<Record<string, unknown>>((m) => m.setReaderDisplay(sessionId, JSON.stringify(cart))),
  clearReaderDisplay: (sessionId: string) =>
    call<Record<string, unknown>>((m) => m.clearReaderDisplay(sessionId)),
  installReaderUpdate: (sessionId: string) =>
    call<Record<string, unknown>>((m) => m.installReaderUpdate(sessionId)),
  getStatus: (sessionId: string) => call<Record<string, unknown>>((m) => m.getStatus(sessionId)),
  invoke: (sessionId: string, opId: string, method: string, args: object = {}) =>
    call<Record<string, unknown>>((m) => m.invoke(sessionId, opId, method, JSON.stringify(args))),

  provideToken: (sessionId: string, requestId: string, token: string) =>
    callVoid((m) => m.provideToken(sessionId, requestId, token)),
  failTokenRequest: (sessionId: string, requestId: string, message: string) =>
    callVoid((m) => m.failTokenRequest(sessionId, requestId, message)),
};

export default PaymentTerminal;
