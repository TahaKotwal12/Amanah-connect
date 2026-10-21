// Complaints, support threads, announcements and notifications.
import type {
  AnnouncementAudience,
  AnnouncementKind,
  AnnouncementStatus,
  ComplaintStatus,
  ComplaintVisibility,
  IsoInstant,
  Priority,
  SupportSide,
  ThreadStatus,
  UploadInstructions,
  Uuid,
} from './common'

// ---- complaints -------------------------------------------------------------------------------

export interface CreateComplaintRequest {
  memberId?: Uuid
  subject: string
  description: string
  priority?: Priority
  category?: string
  assignedTo?: Uuid
}
export interface UpdateComplaintRequest {
  subject?: string
  description?: string
  priority?: Priority
  category?: string
  memberId?: Uuid
  assignedTo?: Uuid
  unassign?: boolean
}
export interface ChangeComplaintStatusRequest {
  status: ComplaintStatus
  note?: string
  notifyMember?: boolean
}
export interface AddCommentRequest {
  body: string
  visibility: ComplaintVisibility
  notifyMember?: boolean
}
export interface ComplaintView {
  id: Uuid
  subject: string
  description: string
  status: ComplaintStatus
  priority: Priority
  category: string | null
  memberId: Uuid | null
  memberNo: string | null
  memberName: string | null
  assignedTo: Uuid | null
  assignedToName: string | null
  createdAt: IsoInstant
  updatedAt: IsoInstant
  resolvedAt: IsoInstant | null
  closedAt: IsoInstant | null
  ageDays: number
  slaTargetDays: number
  slaBreached: boolean
  commentCount: number
  emailOutcome: string | null
}
/** An admin a complaint can be assigned to. */
export interface AssigneeView {
  id: Uuid
  fullName: string
  email: string
}
export interface CommentView {
  id: Uuid
  authorId: Uuid
  authorName: string
  body: string
  visibility: ComplaintVisibility
  createdAt: IsoInstant
  emailOutcome: string | null
}
export interface ComplaintCounts {
  total: number
  open: number
  inProgress: number
  resolved: number
  closed: number
  active: number
  slaBreached: number
  urgentActive: number
  unassignedActive: number
  assignedToMe: number
  byCategory: Record<string, number>
  byPriority: Record<string, number>
}

// ---- support threads --------------------------------------------------------------------------

export interface CreateThreadRequest {
  subject: string
  priority?: Priority
  body: string
  attachmentKey?: string
  attachmentName?: string
}
export interface PostMessageRequest {
  body: string
  attachmentKey?: string
  attachmentName?: string
}
export interface UpdateThreadRequest {
  status?: ThreadStatus
  priority?: Priority
  assignedTo?: Uuid
  unassign?: boolean
}
export interface MarkReadRequest {
  upToSeq?: number
}
export interface SupportUploadView extends UploadInstructions {
  attachmentKey: string
}
export interface AttachmentView {
  name: string
  contentType: string
  size: number
  downloadUrl: string
}
export interface ThreadView {
  id: Uuid
  communityId: Uuid
  communityName: string
  subject: string
  status: ThreadStatus
  priority: Priority
  assignedTo: Uuid | null
  assignedToName: string | null
  createdBy: Uuid
  createdByName: string
  createdAt: IsoInstant
  lastMessageAt: IsoInstant
  closedAt: IsoInstant | null
  messageCount: number
  unreadCount: number
  lastMessagePreview: string | null
}
export interface MessageView {
  id: Uuid
  seq: number
  side: SupportSide
  senderId: Uuid
  senderName: string
  body: string
  attachment: AttachmentView | null
  createdAt: IsoInstant
  readAt: IsoInstant | null
}
export interface ThreadState {
  status: ThreadStatus
  priority: Priority
  assignedTo: Uuid | null
  messageCount: number
  lastMessageAt: IsoInstant
}
export interface MessagePage {
  items: MessageView[]
  cursor: number
  hasMore: boolean
  thread: ThreadState
}
export interface ThreadSummary {
  id: Uuid
  messageSeq: number
  status: ThreadStatus
  unreadCount: number
  lastMessageAt: IsoInstant
}
/** Cheap poll target (every 10 s, paused while the tab is hidden). */
export interface SupportSummary {
  unreadMessages: number
  unreadThreads: number
  activeThreads: number
  threads: ThreadSummary[]
}
export interface PlatformSupportCounts {
  total: number
  open: number
  waiting: number
  resolved: number
  closed: number
  activeUnassigned: number
  urgentActive: number
  unreadThreads: number
}

// ---- community announcements ------------------------------------------------------------------

export interface CreateAnnouncementRequest {
  title: string
  body: string
  audience?: AnnouncementAudience
  group?: string
  memberIds?: Uuid[]
  sendEmail?: boolean
  scheduledAt?: IsoInstant
}
export interface UpdateAnnouncementRequest extends Partial<CreateAnnouncementRequest> {
  unschedule?: boolean
}
export interface ScheduleRequest {
  scheduledAt: IsoInstant
}
export interface DeliveryView {
  recipientsTotal: number
  emailsQueued: number
  skippedNoEmail: number
  skippedNoConsent: number
  skippedQuota: number
}
export interface AnnouncementView {
  id: Uuid
  title: string
  bodyHtml: string
  bodyText: string
  audience: AnnouncementAudience
  group: string | null
  memberIds: Uuid[]
  sendEmail: boolean
  status: AnnouncementStatus
  scheduledAt: IsoInstant | null
  sentAt: IsoInstant | null
  delivery: DeliveryView | null
  createdBy: Uuid
  createdAt: IsoInstant
  updatedAt: IsoInstant
}
export interface AnnouncementPreviewView {
  title: string
  bodyHtml: string
  bodyText: string
  audience: AnnouncementAudience
  sendEmail: boolean
  audienceSize: number
  eligibleForEmail: number
  noEmail: number
  noConsent: number
  quotaRemaining: number | null
  wouldSkipForQuota: number
}
export interface TestSendView {
  sentTo: string
  queued: boolean
}

// ---- platform announcements and notifications -------------------------------------------------

export interface CreatePlatformAnnouncementRequest {
  title: string
  body: string
  kind?: AnnouncementKind
  communityIds?: Uuid[]
  sendEmail?: boolean
  banner?: boolean
  expiresAt?: IsoInstant
  scheduledAt?: IsoInstant
}
export interface UpdatePlatformAnnouncementRequest extends Partial<CreatePlatformAnnouncementRequest> {
  allCommunities?: boolean
  clearExpiry?: boolean
  unschedule?: boolean
}
export interface PlatformAnnouncementView {
  id: Uuid
  title: string
  bodyHtml: string
  bodyText: string
  kind: AnnouncementKind
  /** Null means every active community. */
  communityIds: Uuid[] | null
  sendEmail: boolean
  banner: boolean
  expiresAt: IsoInstant | null
  status: AnnouncementStatus
  scheduledAt: IsoInstant | null
  sentAt: IsoInstant | null
  delivery: DeliveryView | null
  readCount: number
  createdBy: Uuid
  createdAt: IsoInstant
  updatedAt: IsoInstant
}
export interface PlatformPreviewView {
  title: string
  bodyHtml: string
  bodyText: string
  kind: AnnouncementKind
  communities: number
  adminRecipients: number
  sendEmail: boolean
  banner: boolean
}
export interface NotificationView {
  id: Uuid
  title: string
  bodyHtml: string
  bodyText: string
  kind: AnnouncementKind
  banner: boolean
  sentAt: IsoInstant
  expiresAt: IsoInstant | null
  read: boolean
}
export interface NotificationSummary {
  unread: number
  banners: NotificationView[]
}
