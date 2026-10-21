import { ApiError, toApiError } from '@/lib/apiError'
import { noticeFromError } from '@/store/planLimitStore'
import type { CommunityPlanView } from '@/types'
import {
  DEFAULT_EMAIL_INVITE,
  DEFAULT_INVITE,
  createInviteSchema,
  inviteByEmailSchema,
  toCreateInviteRequest,
  toInviteByEmailRequest,
} from './inviteSchema'
import {
  EMPTY_MEMBER,
  memberSchema,
  memberToForm,
  toCreateMemberRequest,
  toUpdateMemberRequest,
  type MemberInput,
} from './memberSchema'
import { UPGRADE_HINT_AT, usagePercent } from './planUsage'
import { writeAccessOf } from './hooks/usePlan'

describe('member form', () => {
  const valid: MemberInput = { ...EMPTY_MEMBER, fullName: 'Asha Rao' }
  const fieldsWithIssues = (value: MemberInput) =>
    memberSchema.safeParse(value).error?.issues.map((issue) => issue.path[0]) ?? []

  it('needs a name and nothing else', () => {
    expect(memberSchema.safeParse(valid).success).toBe(true)
    expect(fieldsWithIssues({ ...valid, fullName: '  ' })).toContain('fullName')
  })

  it.each([
    ['email', { email: 'nope' }],
    ['phone', { phone: 'abc' }],
    ['phone', { phone: '123' }],
    ['joinedOn', { joinedOn: '2999-01-01' }],
    ['fullName', { fullName: 'x'.repeat(151) }],
    ['group', { group: 'x'.repeat(101) }],
  ])('rejects a bad %s', (field, change) => {
    expect(fieldsWithIssues({ ...valid, ...change })).toContain(field)
  })

  it('accepts the phone formats the backend accepts', () => {
    for (const phone of ['+91 98765 43210', '(020) 2345-6789', '98765.43210']) {
      expect(memberSchema.safeParse({ ...valid, phone }).success).toBe(true)
    }
  })

  it('leaves empty boxes out of a new member', () => {
    expect(toCreateMemberRequest(memberSchema.parse(valid))).toEqual({
      fullName: 'Asha Rao',
      consentEmail: true,
    })
    expect(
      toCreateMemberRequest(
        memberSchema.parse({
          ...valid,
          email: 'a@example.test',
          group: 'Block A',
          allowDuplicateEmail: true,
        }),
      ),
    ).toEqual({
      fullName: 'Asha Rao',
      consentEmail: true,
      email: 'a@example.test',
      group: 'Block A',
      allowDuplicateEmail: true,
    })
  })

  it('sends only what changed when editing, and an emptied box as an empty string', () => {
    const original = memberToForm({
      id: 'm',
      memberNo: 'M-1',
      fullName: 'Asha Rao',
      email: 'a@example.test',
      phone: '+91 98765 43210',
      group: 'Block A',
      status: 'ACTIVE',
      statusReason: null,
      statusChangedAt: null,
      joinedOn: '2024-04-01',
      customFields: null,
      consentEmail: true,
      createdAt: '',
      updatedAt: '',
    })
    expect(toUpdateMemberRequest(memberSchema.parse(original), original)).toEqual({})
    expect(
      toUpdateMemberRequest(
        memberSchema.parse({ ...original, phone: '', group: 'Block B' }),
        original,
      ),
    ).toEqual({ phone: '', group: 'Block B' })
    expect(
      toUpdateMemberRequest(memberSchema.parse({ ...original, consentEmail: false }), original),
    ).toEqual({ consentEmail: false })
  })
})

describe('invite forms', () => {
  it('start with a fortnight and 100 uses, and send numbers', () => {
    expect(toCreateInviteRequest(createInviteSchema.parse(DEFAULT_INVITE))).toEqual({
      expiresInDays: 14,
      maxUses: 100,
    })
  })

  it.each([
    [{ expiresInDays: '0' }],
    [{ expiresInDays: '91' }],
    [{ expiresInDays: 'x' }],
    [{ maxUses: '0' }],
    [{ maxUses: '1001' }],
  ])('refuses %j', (change) => {
    expect(createInviteSchema.safeParse({ ...DEFAULT_INVITE, ...change }).success).toBe(false)
  })

  it('includes the group only when given', () => {
    expect(
      toCreateInviteRequest(
        createInviteSchema.parse({ ...DEFAULT_INVITE, defaultGroup: 'Block C' }),
      ),
    ).toMatchObject({ defaultGroup: 'Block C' })
  })

  it('needs a valid email for an emailed invitation, and drops the empty name', () => {
    expect(inviteByEmailSchema.safeParse(DEFAULT_EMAIL_INVITE).success).toBe(false)
    const parsed = inviteByEmailSchema.parse({ ...DEFAULT_EMAIL_INVITE, email: 'r@example.test' })
    expect(toInviteByEmailRequest(parsed)).toEqual({ email: 'r@example.test', expiresInDays: 14 })
  })
})

describe('plan usage', () => {
  it('is a percentage of the limit, capped at 100, and nothing when unlimited', () => {
    expect(usagePercent(43, 50)).toBe(86)
    expect(usagePercent(60, 50)).toBe(100)
    expect(usagePercent(5, null)).toBeNull()
    expect(usagePercent(5, undefined)).toBeNull()
    expect(usagePercent(5, 0)).toBeNull()
  })

  it('starts suggesting an upgrade at 90%', () => {
    expect(UPGRADE_HINT_AT).toBe(0.9)
    expect(44 / 50 >= UPGRADE_HINT_AT).toBe(false)
    expect(45 / 50 >= UPGRADE_HINT_AT).toBe(true)
  })
})

describe('write access', () => {
  const plan = (communityStatus: string) => ({ communityStatus }) as CommunityPlanView

  it('allows writing until the plan says otherwise (the server is the one that refuses)', () => {
    expect(writeAccessOf(undefined)).toEqual({ allowed: true, reason: null })
    expect(writeAccessOf(plan('ACTIVE')).allowed).toBe(true)
    expect(writeAccessOf(plan('PENDING')).allowed).toBe(true)
  })

  it('says why when suspended or archived', () => {
    expect(writeAccessOf(plan('SUSPENDED'))).toMatchObject({ allowed: false })
    expect(writeAccessOf(plan('SUSPENDED')).reason).toMatch(/suspended/)
    expect(writeAccessOf(plan('ARCHIVED')).reason).toMatch(/archived/)
  })
})

describe('plan limit notice', () => {
  it('reads the numbers the server puts on the problem', () => {
    const error = new ApiError({
      status: 402,
      code: 'PLAN_LIMIT_EXCEEDED',
      detail: 'The Starter plan allows up to 50 members.',
      extras: { limit: 'max_members', limitValue: 50, current: 50, plan: 'STARTER' },
    })
    expect(noticeFromError(error)).toEqual({
      message: 'The Starter plan allows up to 50 members.',
      limitKey: 'max_members',
      limit: 50,
      current: 50,
      plan: 'STARTER',
    })
  })

  it('copes with a problem that has none of them', () => {
    const error = new ApiError({
      status: 402,
      code: 'PLAN_LIMIT_EXCEEDED',
      detail: 'Over the limit',
    })
    expect(noticeFromError(error)).toEqual({
      message: 'Over the limit',
      limitKey: null,
      limit: null,
      current: null,
      plan: null,
    })
  })

  it('keeps the extra properties of a problem response on the error', () => {
    const error = toApiError({
      isAxiosError: true,
      response: {
        status: 402,
        data: {
          status: 402,
          code: 'PLAN_LIMIT_EXCEEDED',
          detail: 'x',
          requestId: 'r',
          limit: 'max_members',
          limitValue: 5,
        },
        headers: {},
      },
    })
    expect(error.extras).toEqual({ limit: 'max_members', limitValue: 5 })
    expect(error.code).toBe('PLAN_LIMIT_EXCEEDED')
  })
})
