import { NativeModules } from 'react-native';

/** Dot width of the most common receipt printers, and the only paper setting stored. */
export const DEFAULT_THERMAL_WIDTH_DOTS = 384;

const MM_PER_INCH = 25.4;

/** Converts a printable width to dots, for printers whose spec sheet is in millimetres. */
export function dotsFromMillimetres(millimetres: number, dpi: number): number {
  if (!Number.isFinite(millimetres) || !Number.isFinite(dpi) || millimetres <= 0 || dpi <= 0) {
    return 0;
  }
  return Math.round((millimetres * dpi) / MM_PER_INCH);
}

export type ThermalPrinterState =
  | 'ready'
  | 'no_printer'
  | 'no_permission'
  | 'paper_out'
  | 'error';

/** 'unknown' means the printer reports no paper sensor, which is common. */
export type ThermalPaperState = 'ok' | 'out' | 'unknown';

export interface ThermalPrinterInfo {
  transport: string;
  name: string;
  manufacturer: string | null;
  model: string | null;
  commandSet: string | null;
  hardwareId: string | null;
}

export interface ThermalPrinterStatus {
  state: ThermalPrinterState;
  paper: ThermalPaperState;
  printer: ThermalPrinterInfo | null;
}

export interface ThermalPrintOptions {
  widthDots?: number;
  feedLines?: number;
  cut?: boolean;
  threshold?: number;
}

interface ThermalPrintModuleType {
  status(): Promise<ThermalPrinterStatus>;
  /** Grants only until the printer is unplugged; see UsbPrinterAttachActivity for the durable route. */
  requestPermission(): Promise<boolean>;
  printPage(jobName: string | null, options: ThermalPrintOptions | null): Promise<boolean>;
  printImage(base64: string, options: ThermalPrintOptions | null): Promise<boolean>;
  printTestPage(options: ThermalPrintOptions | null): Promise<boolean>;
}

const ThermalPrintModule: ThermalPrintModuleType = NativeModules.ThermalPrintModule;

export default ThermalPrintModule;
