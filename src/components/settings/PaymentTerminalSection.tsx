/**
 * FreeKiosk v2.0 - Payment Terminal Settings
 * Enable window.FreeKiosk.payments, restrict it to https origins, and show the device's
 * readiness for Tap to Pay and external readers. See docs/payments.md.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTranslation } from 'react-i18next';

import Icon from '../Icon';
import { Colors, FontSizes, Spacing } from '../../theme';
import PaymentTerminal from '../../utils/PaymentTerminalModule';
import { toPaymentOrigins } from '../../utils/storage';
import type { PaymentBridgeInfo, PaymentReadiness } from '../../types/payments';
import SettingsButton from './SettingsButton';
import SettingsInfoBox from './SettingsInfoBox';
import SettingsSwitch from './SettingsSwitch';
import UrlListEditor from './UrlListEditor';

interface PaymentTerminalSectionProps {
  enabled: boolean;
  onEnabledChange: (value: boolean) => void;
  /** https origins allowed to use the bridge; [] = none */
  origins: string[];
  onOriginsChange: (value: string[]) => void;
}

const PaymentTerminalSection: React.FC<PaymentTerminalSectionProps> = ({
  enabled,
  onEnabledChange,
  origins,
  onOriginsChange,
}) => {
  const { t } = useTranslation();
  const [info, setInfo] = useState<PaymentBridgeInfo | null>(null);
  const [readiness, setReadiness] = useState<PaymentReadiness | null>(null);
  const [checking, setChecking] = useState(false);

  const refresh = useCallback(async () => {
    setChecking(true);
    try {
      const [nextInfo, nextReadiness] = await Promise.all([
        PaymentTerminal.getInfo(),
        PaymentTerminal.getReadiness(null),
      ]);
      setInfo(nextInfo);
      setReadiness(nextReadiness);
    } catch (error) {
      console.error('[PaymentTerminal] Readiness check failed:', error);
      setInfo(null);
      setReadiness(null);
    } finally {
      setChecking(false);
    }
  }, []);

  useEffect(() => {
    if (enabled) refresh();
  }, [enabled, refresh]);

  const summary = (ready: boolean | undefined, label: string) => (
    <View style={styles.statusRow}>
      <Icon
        name={ready ? 'check-circle' : 'alert-circle'}
        size={18}
        color={ready ? Colors.success : Colors.warning}
      />
      <Text style={styles.statusText}>{label}</Text>
    </View>
  );

  return (
    <>
      <SettingsSwitch
        label={t('components.payments.enable')}
        hint={t('components.payments.enableHint')}
        value={enabled}
        onValueChange={onEnabledChange}
      />

      {enabled && (
        <>
          {info && !info.supported && (
            <SettingsInfoBox variant="warning">
              <Text style={styles.infoText}>{t('components.payments.notInBuild')}</Text>
            </SettingsInfoBox>
          )}

          <Text style={styles.label}>{t('components.payments.origins')}</Text>
          <Text style={styles.hint}>{t('components.payments.originsHint')}</Text>
          <UrlListEditor
            urls={origins}
            onUrlsChange={(urls) => onOriginsChange(toPaymentOrigins(urls))}
            maxUrls={0}
            placeholder="https://pos.example.com"
            emptyTitle={t('components.payments.noOrigins')}
            emptyHint={t('components.payments.noOriginsHint')}
          />

          <View style={styles.card}>
            {summary(readiness?.bluetoothReady, t('components.payments.readersReady', {
              state: readiness?.bluetoothReady ? t('components.payments.ready') : t('components.payments.notReady'),
            }))}
            {summary(readiness?.tapToPayReady, t('components.payments.tapToPayReady', {
              state: readiness?.tapToPayReady ? t('components.payments.ready') : t('components.payments.notReady'),
            }))}
            {readiness?.checks
              .filter((check) => !check.ok)
              .map((check) => (
                <Text key={check.id} style={styles.detailMuted}>
                  {`• ${check.detail}`}
                </Text>
              ))}
            <View style={styles.actions}>
              <SettingsButton
                title={t('components.payments.recheck')}
                icon="refresh"
                variant="secondary"
                loading={checking}
                onPress={refresh}
              />
            </View>
          </View>

          <SettingsInfoBox variant="warning">
            <Text style={styles.infoText}>{t('components.payments.securityInfo')}</Text>
          </SettingsInfoBox>
        </>
      )}
    </>
  );
};

const styles = StyleSheet.create({
  card: {
    backgroundColor: Colors.surface,
    borderColor: Colors.border,
    borderWidth: 1,
    borderRadius: 8,
    padding: Spacing.md,
    marginBottom: Spacing.md,
    marginTop: Spacing.md,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.sm,
    marginBottom: Spacing.xs,
  },
  statusText: {
    fontSize: FontSizes.md,
    fontWeight: '600',
    color: Colors.textPrimary,
  },
  label: {
    fontSize: FontSizes.md,
    fontWeight: '600',
    color: Colors.textPrimary,
    marginTop: Spacing.sm,
  },
  hint: {
    fontSize: FontSizes.sm,
    color: Colors.textSecondary,
    marginBottom: Spacing.sm,
  },
  detailMuted: {
    marginTop: 2,
    fontSize: FontSizes.sm,
    color: Colors.textSecondary,
  },
  actions: {
    flexDirection: 'row',
    gap: Spacing.sm,
    marginTop: Spacing.md,
  },
  infoText: {
    fontSize: FontSizes.sm,
    color: Colors.textSecondary,
    lineHeight: 20,
  },
});

export default PaymentTerminalSection;
