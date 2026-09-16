/**
 * FreeKiosk v2.0 - SettingsDropdown Component
 * A select field whose options open directly beneath it
 *
 * The menu opens in the layout rather than in an overlay: an overlay has to be placed by window
 * coordinates, which shift with status bar and immersive mode differently across Android versions.
 */

import React, { useState } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, ViewStyle } from 'react-native';
import { Colors, Spacing, Typography } from '../../theme';
import Icon, { IconName } from '../Icon';

interface DropdownOption {
  value: string;
  label: string;
  /** Shown beneath the field while this option is selected. */
  description?: string;
}

interface SettingsDropdownProps {
  label: string;
  icon?: IconName;
  options: DropdownOption[];
  value: string;
  onValueChange: (value: string) => void;
  style?: ViewStyle;
}

const SettingsDropdown: React.FC<SettingsDropdownProps> = ({
  label,
  icon,
  options,
  value,
  onValueChange,
  style,
}) => {
  const [open, setOpen] = useState(false);
  const selected = options.find((option) => option.value === value);

  const choose = (next: string) => {
    setOpen(false);
    if (next !== value) onValueChange(next);
  };

  return (
    <View style={[styles.container, style]}>
      <View style={styles.labelRow}>
        {icon && <Icon name={icon} size={18} color={Colors.textSecondary} style={styles.icon} />}
        <Text style={styles.label}>{label}</Text>
      </View>

      <TouchableOpacity
        style={[styles.field, open && styles.fieldOpen]}
        onPress={() => setOpen(!open)}
        activeOpacity={0.7}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityValue={{ text: selected?.label }}
        accessibilityState={{ expanded: open }}
      >
        <Text style={[styles.fieldText, !selected && styles.fieldPlaceholder]} numberOfLines={1}>
          {selected?.label ?? 'Select…'}
        </Text>
        <Icon name={open ? 'chevron-up' : 'chevron-down'} size={22} color={Colors.textSecondary} />
      </TouchableOpacity>

      {open && (
        <View style={styles.menu}>
          {options.map((option) => {
            const isSelected = option.value === value;
            return (
              <TouchableOpacity
                key={option.value}
                style={[styles.item, isSelected && styles.itemSelected]}
                onPress={() => choose(option.value)}
                activeOpacity={0.7}
                accessibilityRole="menuitem"
                accessibilityState={{ selected: isSelected }}
              >
                <Text style={[styles.itemText, isSelected && styles.itemTextSelected]}>
                  {option.label}
                </Text>
                {isSelected && <Icon name="check" size={20} color={Colors.primary} />}
              </TouchableOpacity>
            );
          })}
        </View>
      )}

      {selected?.description && <Text style={styles.description}>{selected.description}</Text>}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginBottom: Spacing.md,
  },
  labelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: Spacing.sm,
  },
  icon: {
    marginRight: Spacing.sm,
  },
  label: {
    ...Typography.label,
  },
  field: {
    minHeight: Spacing.inputHeight,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderColor: Colors.border,
    borderRadius: Spacing.inputRadius,
    paddingHorizontal: Spacing.inputPadding,
    backgroundColor: Colors.surfaceVariant,
  },
  fieldOpen: {
    borderColor: Colors.primary,
  },
  fieldText: {
    flex: 1,
    fontSize: 16,
    color: Colors.textPrimary,
    marginRight: Spacing.sm,
  },
  fieldPlaceholder: {
    color: Colors.textHint,
  },
  menu: {
    marginTop: Spacing.xs,
    backgroundColor: Colors.surface,
    borderWidth: 1,
    borderColor: Colors.border,
    borderRadius: Spacing.inputRadius,
    paddingVertical: Spacing.xs,
    overflow: 'hidden',
  },
  item: {
    minHeight: Spacing.inputHeight,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: Spacing.inputPadding,
  },
  itemSelected: {
    backgroundColor: Colors.primaryLight,
  },
  itemText: {
    flex: 1,
    fontSize: 16,
    color: Colors.textPrimary,
    marginRight: Spacing.sm,
  },
  itemTextSelected: {
    color: Colors.primary,
    fontWeight: '600',
  },
  description: {
    ...Typography.hint,
    marginTop: Spacing.xs,
  },
});

export default SettingsDropdown;
