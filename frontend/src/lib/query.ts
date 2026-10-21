/**
 * Query string for the list endpoints. Spring wants `sort=name,desc&sort=id,asc` (the key
 * repeated), where axios would write `sort[]=...`, so the string is built here. Empty values are
 * left out.
 */
export function toQuery(params: object): URLSearchParams {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params) as [string, unknown][]) {
    const values = Array.isArray(value) ? (value as unknown[]) : [value]
    for (const item of values) {
      if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') {
        if (item !== '') query.append(key, String(item))
      }
    }
  }
  return query
}
