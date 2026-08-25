// ProviderRules.ts
// Collection of ProviderRule objects keyed by provider ID.

import { ProviderRule } from './ProviderRule';

export interface ProviderRules {
  /** Map of providerId → array of rules */
  [providerId: string]: ProviderRule[];
}
