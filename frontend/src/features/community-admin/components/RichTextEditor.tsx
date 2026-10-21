import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import {
  Bold,
  Heading2,
  Italic,
  Link2,
  List,
  ListOrdered,
  Quote,
  Redo2,
  Underline as UnderlineIcon,
  Undo2,
  Unlink,
  type LucideIcon,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { Button } from '@/shared/components/ui/button'
import { Input } from '@/shared/components/ui/input'
import { cn } from '@/shared/utils/cn'

/** Only what the server keeps: it sanitises again, so anything else would be dropped anyway. */
const SAFE_LINK = /^(https?:\/\/|mailto:)/i

interface RichTextEditorProps {
  /** HTML. The editor can only produce the tags in the toolbar. */
  value: string
  onChange: (html: string) => void
  /** The id of the visible label. */
  labelledBy: string
  invalid?: boolean
  describedBy?: string | undefined
  disabled?: boolean
}

function ToolButton({
  label,
  icon: Icon,
  active,
  disabled,
  onClick,
}: {
  label: string
  icon: LucideIcon
  active?: boolean
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={active ?? false}
      disabled={disabled}
      // Keep the editor's selection: a click on a button must not move focus away first.
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      className={cn(
        'flex size-10 items-center justify-center rounded text-brand-900 hover:bg-brand-50 disabled:opacity-50',
        active && 'bg-brand-100',
      )}
    >
      <Icon aria-hidden="true" className="size-4" />
    </button>
  )
}

/**
 * A small rich text editor: bold, italic, underline, a heading, bullet and numbered lists, a quote
 * and links. Nothing else can be typed or pasted in (images, tables, colours and the rest have no
 * place in the schema), and the server sanitises the result again before it is stored or emailed.
 */
export default function RichTextEditor({
  value,
  onChange,
  labelledBy,
  invalid,
  describedBy,
  disabled,
}: RichTextEditorProps) {
  const [linkOpen, setLinkOpen] = useState(false)
  const [href, setHref] = useState('')
  const [linkError, setLinkError] = useState<string | null>(null)

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [2] },
        code: false,
        codeBlock: false,
        horizontalRule: false,
        strike: false,
        link: {
          openOnClick: false,
          autolink: false,
          isAllowedUri: (url: string) => SAFE_LINK.test(url),
          HTMLAttributes: { rel: 'nofollow noopener noreferrer', target: null },
        },
      }),
    ],
    content: value,
    editable: !disabled,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        role: 'textbox',
        'aria-multiline': 'true',
        'aria-labelledby': labelledBy,
        ...(describedBy ? { 'aria-describedby': describedBy } : {}),
        ...(invalid ? { 'aria-invalid': 'true' } : {}),
        class:
          'min-h-48 px-3 py-2 text-base focus:outline-none sm:text-sm [&_a]:text-brand-700 [&_a]:underline [&_blockquote]:border-l-4 [&_blockquote]:border-brand-200 [&_blockquote]:pl-3 [&_h2]:font-heading [&_h2]:text-lg [&_h2]:font-semibold [&_ol]:list-decimal [&_ol]:pl-6 [&_p]:my-2 [&_ul]:list-disc [&_ul]:pl-6',
      },
    },
    onUpdate: ({ editor: current }) => {
      // An empty editor is "<p></p>"; report it as empty so "required" works.
      onChange(current.isEmpty ? '' : current.getHTML())
    },
  })

  // A different announcement was opened, or the form was reset: load it into the editor.
  useEffect(() => {
    if (!editor) return
    const current = editor.isEmpty ? '' : editor.getHTML()
    if (value !== current) editor.commands.setContent(value, { emitUpdate: false })
  }, [editor, value])

  useEffect(() => {
    editor?.setEditable(!disabled)
  }, [editor, disabled])

  const state = useEditorState({
    editor,
    selector: ({ editor: current }) => ({
      bold: current?.isActive('bold') ?? false,
      italic: current?.isActive('italic') ?? false,
      underline: current?.isActive('underline') ?? false,
      heading: current?.isActive('heading', { level: 2 }) ?? false,
      bullets: current?.isActive('bulletList') ?? false,
      numbers: current?.isActive('orderedList') ?? false,
      quote: current?.isActive('blockquote') ?? false,
      link: current?.isActive('link') ?? false,
      canUndo: current?.can().undo() ?? false,
      canRedo: current?.can().redo() ?? false,
    }),
  })

  if (!editor) return <div className="min-h-48 rounded border border-muted bg-white" />

  function openLink() {
    setHref((editor?.getAttributes('link').href as string | undefined) ?? '')
    setLinkError(null)
    setLinkOpen(true)
  }

  function applyLink() {
    const url = href.trim()
    if (!SAFE_LINK.test(url)) {
      setLinkError('Start the address with https://, http:// or mailto:')
      return
    }
    editor?.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
    setLinkOpen(false)
  }

  const run = editor.chain().focus()
  return (
    <div
      className={cn(
        'rounded border bg-white',
        invalid ? 'border-danger ring-1 ring-danger' : 'border-muted',
        disabled && 'opacity-70',
      )}
    >
      <div
        role="toolbar"
        aria-label="Formatting"
        className="flex flex-wrap items-center gap-0.5 border-b border-brand-100 p-1"
      >
        <ToolButton
          label="Bold"
          icon={Bold}
          active={state?.bold ?? false}
          disabled={disabled}
          onClick={() => run.toggleBold().run()}
        />
        <ToolButton
          label="Italic"
          icon={Italic}
          active={state?.italic ?? false}
          disabled={disabled}
          onClick={() => run.toggleItalic().run()}
        />
        <ToolButton
          label="Underline"
          icon={UnderlineIcon}
          active={state?.underline ?? false}
          disabled={disabled}
          onClick={() => run.toggleUnderline().run()}
        />
        <ToolButton
          label="Heading"
          icon={Heading2}
          active={state?.heading ?? false}
          disabled={disabled}
          onClick={() => run.toggleHeading({ level: 2 }).run()}
        />
        <ToolButton
          label="Bulleted list"
          icon={List}
          active={state?.bullets ?? false}
          disabled={disabled}
          onClick={() => run.toggleBulletList().run()}
        />
        <ToolButton
          label="Numbered list"
          icon={ListOrdered}
          active={state?.numbers ?? false}
          disabled={disabled}
          onClick={() => run.toggleOrderedList().run()}
        />
        <ToolButton
          label="Quote"
          icon={Quote}
          active={state?.quote ?? false}
          disabled={disabled}
          onClick={() => run.toggleBlockquote().run()}
        />
        <ToolButton
          label="Link"
          icon={Link2}
          active={state?.link ?? false}
          disabled={disabled}
          onClick={openLink}
        />
        {state?.link ? (
          <ToolButton
            label="Remove link"
            icon={Unlink}
            disabled={disabled}
            onClick={() => run.extendMarkRange('link').unsetLink().run()}
          />
        ) : null}
        <span className="mx-1 h-6 w-px bg-brand-100" aria-hidden="true" />
        <ToolButton
          label="Undo"
          icon={Undo2}
          disabled={disabled || !(state?.canUndo ?? false)}
          onClick={() => run.undo().run()}
        />
        <ToolButton
          label="Redo"
          icon={Redo2}
          disabled={disabled || !(state?.canRedo ?? false)}
          onClick={() => run.redo().run()}
        />
      </div>
      {linkOpen ? (
        <div className="grid gap-2 border-b border-brand-100 bg-brand-50 p-2">
          <label htmlFor="editor-link" className="text-sm font-medium">
            Link address
          </label>
          <div className="flex flex-wrap gap-2">
            <Input
              id="editor-link"
              value={href}
              placeholder="https://"
              aria-invalid={linkError !== null}
              onChange={(event) => setHref(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  applyLink()
                }
              }}
              className="min-w-0 flex-1"
            />
            <Button type="button" onClick={applyLink}>
              Apply
            </Button>
            <Button type="button" variant="secondary" onClick={() => setLinkOpen(false)}>
              Cancel
            </Button>
          </div>
          {linkError ? (
            <p role="alert" className="text-sm text-danger">
              {linkError}
            </p>
          ) : null}
        </div>
      ) : null}
      <EditorContent editor={editor} />
    </div>
  )
}
