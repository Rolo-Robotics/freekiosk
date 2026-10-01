/**
 * Payment terminal bridge: shared types.
 *
 * The native side (android/.../payment/) hosts one payment SDK. The kiosk page drives it
 * through window.FreeKiosk.payments, which WebViewComponent routes to PaymentTerminalModule.
 * See docs/payments.md.
 */

/** Reader connection modes. Which ones a provider supports is in its capabilities. */
export type PaymentReaderMode = 'tapToPay' | 'bluetooth' | 'usb' | 'internet';

export interface PaymentBridgeInfo {
  /** False when this APK was built without -Ppayments. */
  supported: boolean;
  providers: string[];
  activeProvider: string | null;
  sessionOpen: boolean;
  transactionActive: boolean;
}

export interface PaymentReadinessCheck {
  id: string;
  ok: boolean;
  /** Which reader modes the check matters for. */
  appliesTo: 'all' | 'tapToPay' | 'bluetooth';
  detail: string;
}

export interface PaymentReadiness {
  checks: PaymentReadinessCheck[];
  tapToPayReady: boolean;
  bluetoothReady: boolean;
}

/** Event types a provider can push to the session owner. Mirrors PaymentEvents.kt. */
export type PaymentEventType =
  | 'readersDiscovered'
  | 'connectionStatus'
  | 'paymentStatus'
  | 'readerMessage'
  | 'readerUpdate'
  | 'readerDisconnected'
  | 'readerReconnect'
  | 'battery'
  | 'tokenRequest'
  | 'transactionActive'
  | 'sessionClosed';

export interface PaymentTerminalEvent {
  sessionId: string;
  type: PaymentEventType | string;
  payload: Record<string, unknown>;
}

/** Operations the page may request through FK_PAYMENT messages. */
export const PAYMENT_PAGE_OPS = [
  'getInfo',
  'getReadiness',
  'initialize',
  'discoverReaders',
  'connectReader',
  'disconnectReader',
  'collectPayment',
  'cancel',
  'setReaderDisplay',
  'clearReaderDisplay',
  'installReaderUpdate',
  'getStatus',
  'invoke',
  'provideToken',
  'failTokenRequest',
  'close',
] as const;

export type PaymentPageOp = (typeof PAYMENT_PAGE_OPS)[number];
