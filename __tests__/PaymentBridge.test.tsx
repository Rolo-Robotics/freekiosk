/**
 * Covers the payment terminal bridge on the JS side:
 *   - window.FreeKiosk.payments is only reachable from an allow-listed https origin that holds
 *     the page bridge's nonce, and an empty list lets no page in
 *   - calls open one session for the page, and navigation releases it
 *   - SDK events reach only the page holding the session
 *   - the JS-side guards see a transaction in progress
 *   - remote JavaScript is refused while the bridge is enabled
 */
// jest.setup.js stubs native modules with a Proxy that hands back a fresh jest.fn() on every
// access, so a spy set on it is discarded. PaymentTerminalModule.ts also captures the module at
// import time. Hence a plain object installed *before* the modules under test are required.
export {}; // a module, not a global script: keeps these requires out of other files' scope

const { NativeModules, DeviceEventEmitter } = require('react-native');

const nativePayments = {
  getInfo: jest.fn(() => Promise.resolve(JSON.stringify({ supported: true, providers: ['stripe'] }))),
  getReadiness: jest.fn(() => Promise.resolve(JSON.stringify({ checks: [], tapToPayReady: false, bluetoothReady: true }))),
  openSession: jest.fn(() => Promise.resolve('session-1')),
  closeSession: jest.fn(() => Promise.resolve(null)),
  closeSessionsOwnedBy: jest.fn(() => Promise.resolve(null)),
  initialize: jest.fn(() => Promise.resolve(JSON.stringify({ initialized: true }))),
  discoverReaders: jest.fn(() => Promise.resolve('{}')),
  connectReader: jest.fn(() => Promise.resolve('{}')),
  disconnectReader: jest.fn(() => Promise.resolve('{}')),
  collectPayment: jest.fn(() => Promise.resolve(JSON.stringify({ id: 'pi_1', status: 'SUCCEEDED' }))),
  cancel: jest.fn(() => Promise.resolve('{}')),
  setReaderDisplay: jest.fn(() => Promise.resolve('{}')),
  clearReaderDisplay: jest.fn(() => Promise.resolve('{}')),
  installReaderUpdate: jest.fn(() => Promise.resolve('{}')),
  getStatus: jest.fn(() => Promise.resolve('{}')),
  invoke: jest.fn(() => Promise.resolve('{}')),
  provideToken: jest.fn(() => Promise.resolve(null)),
  failTokenRequest: jest.fn(() => Promise.resolve(null)),
  addListener: jest.fn(),
  removeListeners: jest.fn(),
};
NativeModules.PaymentTerminalModule = nativePayments;

// The shared mock forwards the ref to a plain View, which has no injectJavaScript. This one
// hands the component the handle the current test installed.
jest.mock('react-native-webview', () => {
  const ReactLib = require('react');
  const { View } = require('react-native');
  const WebView = ReactLib.forwardRef((props: any, ref: any) => {
    ReactLib.useImperativeHandle(ref, () => (globalThis as any).__mockWebViewHandle);
    return ReactLib.createElement(View, props);
  });
  return { WebView, default: WebView };
});

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

const React = require('react');
const ReactTestRenderer = require('react-test-renderer');
const WebViewComponent = require('../src/components/WebViewComponent').default;
const { isPaymentTransactionActive } = require('../src/utils/PaymentTerminalModule');
const { toPaymentOrigins, StorageService } = require('../src/utils/storage');
const { ApiService } = require('../src/utils/ApiService');

const PAGE = 'https://pos.example.com/checkout';
const flush = () => new Promise<void>((resolve) => setImmediate(resolve));

const mounted: any[] = [];

async function renderBridge(props: Record<string, unknown> = {}) {
  const injectJavaScript = jest.fn();
  (globalThis as any).__mockWebViewHandle = { injectJavaScript, goBack: jest.fn(), reload: jest.fn() };
  let renderer: any;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      React.createElement(WebViewComponent, {
        url: PAGE,
        autoReload: false,
        paymentsEnabled: true,
        paymentOrigins: ['https://pos.example.com'],
        ...props,
      }),
    );
  });
  mounted.push(renderer);
  const webView = () => renderer.root.findAll((node: any) => typeof node.props.onMessage === 'function')[0];
  const script = (): string => webView().props.injectedJavaScript;
  const nonce = (): string => JSON.parse(script().match(/var nonce = ("[^"]+")/)![1]);

  const post = async (op: string, data: Record<string, unknown> = {}, overrides: Record<string, unknown> = {}) => {
    const message = { type: 'FK_PAYMENT', nonce: nonce(), origin: 'https://pos.example.com', op, id: '7', data, ...overrides };
    await ReactTestRenderer.act(async () => {
      webView().props.onMessage({ nativeEvent: { data: JSON.stringify(message), url: overrides.pageUrl ?? PAGE } });
      await flush();
    });
  };

  /** What the page was told for request id 7, decoded from the injected __fkSettle call. */
  const settled = (): any => {
    const call = injectJavaScript.mock.calls.map((c) => c[0] as string).reverse().find((js) => js.includes('__fkSettle('));
    if (!call) return undefined;
    const arg = call.match(/__fkSettle\((".*")\); true;$/)![1];
    return JSON.parse(JSON.parse(arg));
  };

  return { renderer, webView, script, nonce, post, settled, injectJavaScript };
}

beforeEach(() => {
  jest.clearAllMocks();
});

// The WebView arms a 10 s loading timeout; unmounting clears it before the environment goes.
afterEach(async () => {
  await ReactTestRenderer.act(async () => {
    mounted.splice(0).forEach((r) => r.unmount());
  });
});

describe('page API injection', () => {
  test('window.FreeKiosk.payments is injected only when the bridge is enabled', async () => {
    const on = await renderBridge();
    expect(on.script()).toContain('window.FreeKiosk.payments');

    const off = await renderBridge({ paymentsEnabled: false });
    expect(off.script()).not.toContain('window.FreeKiosk.payments');
  });

  test('the injected page script is valid JavaScript', async () => {
    for (const props of [{}, { silentPrintEnabled: true }, { paymentsEnabled: false, silentPrintEnabled: true }]) {
      const bridge = await renderBridge(props);
      // Compiles without running: a syntax slip in the template would otherwise only show on a device.
      // eslint-disable-next-line no-new-func -- compiled for a syntax check, never called
      expect(() => new Function(bridge.script())).not.toThrow();
    }
  });

  test('silent print and payments share one bridge core', async () => {
    const both = await renderBridge({ silentPrintEnabled: true });
    expect(both.script()).toContain('window.FreeKiosk.silentPrinter');
    expect(both.script()).toContain('window.FreeKiosk.payments');
    expect(both.script().match(/window\.__fkSettle = /g)).toHaveLength(1);
  });
});

describe('request gating', () => {
  test('an allow-listed https page gets an answer', async () => {
    const bridge = await renderBridge();
    await bridge.post('getInfo');
    expect(nativePayments.getInfo).toHaveBeenCalled();
    expect(bridge.settled()).toMatchObject({ id: '7', ok: true, value: { supported: true } });
  });

  test('an origin not on the list is refused', async () => {
    const bridge = await renderBridge();
    await bridge.post('getInfo', {}, { origin: 'https://evil.example.com', pageUrl: 'https://evil.example.com/' });
    expect(nativePayments.getInfo).not.toHaveBeenCalled();
    expect(bridge.settled()).toMatchObject({ ok: false, code: 'ORIGIN_NOT_ALLOWED' });
  });

  test('an empty list lets no page in', async () => {
    const bridge = await renderBridge({ paymentOrigins: [] });
    await bridge.post('getInfo');
    expect(nativePayments.getInfo).not.toHaveBeenCalled();
    expect(bridge.settled()).toMatchObject({ ok: false, code: 'ORIGIN_NOT_ALLOWED' });
  });

  test('an http origin is refused even when listed', async () => {
    const bridge = await renderBridge({
      url: 'http://pos.example.com/',
      paymentOrigins: ['http://pos.example.com'],
    });
    await bridge.post('getInfo', {}, { origin: 'http://pos.example.com', pageUrl: 'http://pos.example.com/' });
    expect(nativePayments.getInfo).not.toHaveBeenCalled();
    expect(bridge.settled()).toMatchObject({ ok: false, code: 'ORIGIN_NOT_ALLOWED' });
  });

  test('a message without the bridge nonce (an iframe) is dropped without an answer', async () => {
    const bridge = await renderBridge();
    await bridge.post('getInfo', {}, { nonce: 'guessed' });
    expect(nativePayments.getInfo).not.toHaveBeenCalled();
    expect(bridge.settled()).toBeUndefined();
  });

  test('an unknown operation is refused', async () => {
    const bridge = await renderBridge();
    await bridge.post('refundEverything');
    expect(bridge.settled()).toMatchObject({ ok: false, code: 'UNKNOWN_OP' });
  });
});

describe('sessions and events', () => {
  test('a payment call opens the page session and passes the request through as JSON', async () => {
    const bridge = await renderBridge();
    await bridge.post('collectPayment', { opId: 'op1', request: { clientSecret: 'pi_1_secret' } });
    expect(nativePayments.openSession).toHaveBeenCalledWith('webview');
    expect(nativePayments.collectPayment).toHaveBeenCalledWith('session-1', 'op1', JSON.stringify({ clientSecret: 'pi_1_secret' }));
    expect(bridge.settled()).toMatchObject({ ok: true, value: { id: 'pi_1', status: 'SUCCEEDED' } });
  });

  test('concurrent first calls share one session', async () => {
    const bridge = await renderBridge();
    await ReactTestRenderer.act(async () => {
      const post = (op: string, id: string) =>
        bridge.webView().props.onMessage({
          nativeEvent: {
            data: JSON.stringify({ type: 'FK_PAYMENT', nonce: bridge.nonce(), origin: 'https://pos.example.com', op, id, data: {} }),
            url: PAGE,
          },
        });
      post('getStatus', '1');
      post('disconnectReader', '2');
      await flush();
    });
    expect(nativePayments.openSession).toHaveBeenCalledTimes(1);
    expect(nativePayments.getStatus).toHaveBeenCalledWith('session-1');
    expect(nativePayments.disconnectReader).toHaveBeenCalledWith('session-1');
  });

  test('events for the page session are pushed to the page, others are not', async () => {
    const bridge = await renderBridge();
    await bridge.post('initialize', { provider: 'stripe', config: {} });
    bridge.injectJavaScript.mockClear();

    await ReactTestRenderer.act(async () => {
      DeviceEventEmitter.emit('paymentTerminalEvent', JSON.stringify({ sessionId: 'other', type: 'readerMessage', payload: {} }));
      DeviceEventEmitter.emit('paymentTerminalEvent', JSON.stringify({ sessionId: 'session-1', type: 'readerMessage', payload: { kind: 'input' } }));
    });

    const pushed = bridge.injectJavaScript.mock.calls.map((c) => c[0] as string).filter((js) => js.includes('__fkPaymentEvent('));
    expect(pushed).toHaveLength(1);
    expect(pushed[0]).toContain('readerMessage');
  });

  test('navigating releases the session so the next page starts clean', async () => {
    const bridge = await renderBridge();
    await bridge.post('initialize', { provider: 'stripe' });
    await ReactTestRenderer.act(async () => {
      bridge.webView().props.onLoadStart({ nativeEvent: { url: 'https://pos.example.com/next' } });
    });
    expect(nativePayments.closeSessionsOwnedBy).toHaveBeenCalledWith('webview', 'navigation');
  });
});

describe('guards', () => {
  test('transactionActive events drive isPaymentTransactionActive', () => {
    DeviceEventEmitter.emit('paymentTerminalEvent', JSON.stringify({ sessionId: 's', type: 'transactionActive', payload: { active: true } }));
    expect(isPaymentTransactionActive()).toBe(true);
    DeviceEventEmitter.emit('paymentTerminalEvent', JSON.stringify({ sessionId: 's', type: 'transactionActive', payload: { active: false } }));
    expect(isPaymentTransactionActive()).toBe(false);
  });

  test('remote JavaScript is refused while the payment bridge is enabled', async () => {
    const onExecuteJs = jest.fn();
    (ApiService as any).callbacks = { onExecuteJs };

    await StorageService.savePaymentsEnabled(true);
    const refused = await ApiService.executeAction('executeJs', { code: 'window.x = 1' });
    expect(refused.ok).toBe(false);
    expect(onExecuteJs).not.toHaveBeenCalled();

    await StorageService.savePaymentsEnabled(false);
    const allowed = await ApiService.executeAction('executeJs', { code: 'window.x = 1' });
    expect(allowed.ok).toBe(true);
    expect(onExecuteJs).toHaveBeenCalledWith('window.x = 1');
  });
});

describe('toPaymentOrigins', () => {
  test('keeps https origins only and fails closed', () => {
    // eslint-disable-next-line no-script-url -- the input being rejected
    expect(toPaymentOrigins(['https://a.example', 'http://b.example', 'javascript:alert(1)', 42])).toEqual(['https://a.example']);
    expect(toPaymentOrigins(null)).toEqual([]);
    expect(toPaymentOrigins('https://a.example')).toEqual([]);
  });
});
