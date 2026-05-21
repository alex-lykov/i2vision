/**
 * i2-Vision Universal Bridge
 * 
 * Export all bridge components for easy import.
 */

// Protocol types
export * from './Protocol';

// Transport layer
export * from './Transport';
export * from './StdioTransport';
export * from './HttpTransport';

// UI Configuration
export * from './I2VisionUiConfig';

// Bridge implementations
export * from './UniversalAgentBridge';
export * from './VsCodeAgentBridge';
