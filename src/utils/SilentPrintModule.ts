import { NativeModules } from 'react-native';

export const DEFAULT_THERMAL_WIDTH_DOTS = 384;

export type PrinterState =
  | 'ready'
  | 'no_printer'
  | 'no_permission'
  | 'paper_out'
  | 'error';

/** 'unknown' means the printer reports no paper sensor, which is common. */
export type PaperState = 'ok' | 'out' | 'unknown';

export interface PrinterInfo {
  transport: string;
  name: string;
  manufacturer: string | null;
  model: string | null;
  commandSet: string | null;
  hardwareId: string | null;
}

export interface PrinterStatus {
  state: PrinterState;
  paper: PaperState;
  printer: PrinterInfo | null;
}

export interface ThermalPrintOptions {
  widthDots?: number;
  feedLines?: number;
  cut?: boolean;
  threshold?: number;
}

interface SilentPrintModuleType {
  status(): Promise<PrinterStatus>;
  /** Grants only until the printer is unplugged; see UsbPrinterAttachActivity for the durable route. */
  requestPermission(): Promise<boolean>;
  printPage(jobName: string | null, options: ThermalPrintOptions | null): Promise<boolean>;
  printImage(base64: string, options: ThermalPrintOptions | null): Promise<boolean>;
  printTestPage(options: ThermalPrintOptions | null): Promise<boolean>;
}

const SilentPrintModule: SilentPrintModuleType = NativeModules.SilentPrintModule;

export default SilentPrintModule;
