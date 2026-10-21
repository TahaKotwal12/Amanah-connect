import { Link, type LinkProps } from 'react-router'
import { Button } from './ui/button'

interface LinkButtonProps extends LinkProps {
  /** `outline` is the secondary look. */
  variant?: 'primary' | 'outline'
}

/** A router link styled as a design-system Button. */
export function LinkButton({ variant = 'primary', ...props }: LinkButtonProps) {
  return (
    <Button asChild variant={variant === 'outline' ? 'secondary' : 'primary'}>
      <Link {...props} />
    </Button>
  )
}
