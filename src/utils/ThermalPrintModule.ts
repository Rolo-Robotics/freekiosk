import { NativeModules } from 'react-native';

/** Print width in dots at 203dpi: 58mm paper prints 48mm wide, 80mm prints 72mm. */
export const THERMAL_WIDTH_58MM = 384;
export const THERMAL_WIDTH_80MM = 576;

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
