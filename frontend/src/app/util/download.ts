/**
 * Triggers a browser download of `blob` under `filename` via a temporary object URL. Shared by the pages that
 * download a server-built file (the users page's QR ZIP/PDF, the activity page's CSV), so the object-URL
 * lifecycle lives in one place.
 *
 * @param blob the file contents to download
 * @param filename the name to save the file under
 */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  // revoke on a later tick so it does not race the click-driven download (revoking synchronously can
  // invalidate the URL before the browser has started fetching the blob)
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
