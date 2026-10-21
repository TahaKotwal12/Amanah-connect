/** The community's own name and logo at the top of a member-facing page. */
export function CommunityHeader({
  name,
  logoUrl,
}: {
  name: string
  logoUrl: string | null | undefined
}) {
  return (
    <div className="mb-6 flex items-center gap-3">
      {logoUrl ? (
        <img src={logoUrl} alt="" className="size-14 shrink-0 rounded object-contain" />
      ) : null}
      <p className="font-heading text-2xl font-semibold text-brand-900">{name}</p>
    </div>
  )
}
