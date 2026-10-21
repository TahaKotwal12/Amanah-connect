export const authKeys = {
  all: ['auth'] as const,
  me: () => [...authKeys.all, 'me'] as const,
  twoFactorSetup: () => [...authKeys.all, '2fa-setup'] as const,
}
