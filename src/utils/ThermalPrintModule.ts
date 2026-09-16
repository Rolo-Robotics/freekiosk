import { NativeModules } from 'react-native';

/** Print width in dots at the usual 203dpi: 58mm paper prints 48mm wide, 80mm prints 72mm. */
export const THERMAL_WIDTH_58MM = 384;
export const THERMAL_WIDTH_80MM = 576;

export type ThermalPrinterState =
  | 'ready'
  | 'no_printer'
  | 'no_permission'
  | 'paper_out'
  | 'error';

/** OK/OUT come from the printer; 'unknown' means it does not report paper, which is common. */
export type ThermalPaperState = 'ok' | 'out' | 'unknown';

export interface ThermalPrinterInfo {
  transport: string;
  name: string;
  manufacturer: string | null;
  model: string | null;
  /** IEEE 1284 CMD:, e.g. "ESC/POS". Null when the printer does not answer. */
  commandSet: string | null;
  /** "0416:5011" — the pair needed to add a printer to the USB device filter. */
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
  /** Shows the system USB dialog. The grant it returns lasts only until the printer is unplugged. */
  requestPermission(): Promise<boolean>;
  printImage(base64: string, options: ThermalPrintOptions | null): Promise<boolean>;
  printTestPage(options: ThermalPrintOptions | null): Promise<boolean>;
}

const ThermalPrintModule: ThermalPrintModuleType = NativeModules.ThermalPrintModule;

export default ThermalPrintModule;
