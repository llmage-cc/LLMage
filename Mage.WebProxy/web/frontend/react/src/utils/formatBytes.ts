/**
 * Byte-size formatting for the replay load notice.
 *
 * The replay load blocks server-side for seconds on a multi-MB save, and this is
 * the only quantitative fact available to the client about it (the proxy reads
 * the file's length; see WebProxySession.replayFileSize). Both the lobby browser
 * and the viewer render it, so it lives here rather than twice inline.
 */

/**
 * Format a byte count for display. Returns '' for null — the proxy could not read
 * the file's size, which is NOT an error: the server may still load it fine (the
 * proxy and server do not necessarily share a filesystem).
 */
export function formatBytes(bytes: number | null): string {
  if (bytes === null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Confirmation line for a deck FILE upload (bug-hunt: uploading silently
 * replaced the seat's textarea with no feedback). The one fact the client has
 * that the textarea does not show is WHAT was read — the file's name and byte
 * size. Pure so the seat rows and the harness agree on the exact string.
 *
 * A `size` of null (FileReader gave no length; the caller could not read it)
 * omits the parenthetical rather than printing "(0 B)" for a file that clearly
 * had content.
 */
export function deckUploadNoticeMessage(fileName: string, sizeBytes: number | null): string {
  const size = formatBytes(sizeBytes)
  const name = fileName && fileName.trim() !== '' ? `"${fileName}"` : 'the selected file'
  return size ? `Loaded deck from ${name} (${size}).` : `Loaded deck from ${name}.`
}
