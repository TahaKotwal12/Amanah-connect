export const landingKeys = {
  all: ['landing'] as const,
  /** GET /public/plans */
  plans: () => [...landingKeys.all, 'plans'] as const,
}
