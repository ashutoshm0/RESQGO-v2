/**
 * RESQGO pilot data receiver. Runs in YOUR Google account and saves what riders' phones send:
 *   Drive folder "RESQGO pilot data/<rider>/"  every CSV file
 *   Google Sheet "RESQGO pilot"                tab Rides (one row per ride), tab Clips (one row per sensor clip)
 *
 * Setup, once (about 10 minutes):
 *  1. Open https://script.google.com and click New project. Name it "RESQGO pilot".
 *  2. Delete the sample code, paste this whole file, and change KEY below to your own long random word.
 *  3. Click Deploy > New deployment > Select type: Web app.
 *       Execute as: Me.  Who has access: Anyone.  Click Deploy and allow the permissions it asks for.
 *  4. Copy the Web app URL (it ends in /exec).
 *  5. In the RESQGO project's local.properties add two lines, then rebuild the app:
 *       RESQGO_UPLOAD_URL=<the /exec URL>
 *       RESQGO_UPLOAD_KEY=<the same KEY as below>
 *  6. Open the URL in a browser: it should say "RESQGO pilot receiver is running".
 *
 * Anyone with the URL could send files, so the key rejects everything without it.
 * After editing this file, use Deploy > Manage deployments > Edit > New version, or the URL keeps the old code.
 */
const KEY = 'CHANGE-ME-to-a-long-random-word';
const ROOT = 'RESQGO pilot data';

function doGet() {
  return ContentService.createTextOutput('RESQGO pilot receiver is running');
}

function doPost(e) {
  let d;
  try {
    d = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply({ ok: false, error: 'not JSON' });
  }
  if (d.key !== KEY) return reply({ ok: false, error: 'wrong key' });

  const rider = String(d.rider || 'unknown').replace(/[^A-Za-z0-9-]/g, '').slice(0, 20) || 'unknown';
  const name = String(d.name || '').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 120);
  if (!name.endsWith('.csv')) return reply({ ok: false, error: 'bad name' });

  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const folder = subfolder(rootFolder(), rider);
    // The phone retries if it misses our answer, so the same file can arrive twice.
    if (folder.getFilesByName(name).hasNext()) return reply({ ok: true, duplicate: true });

    const file = folder.createFile(name, String(d.content || ''), MimeType.CSV);
    const book = spreadsheet();
    if (name.startsWith('ride-')) {
      appendCsv(book, 'Rides', String(d.content || ''));
    } else {
      // <rider>-<yyyyMMdd-HHmmss-SSS>-<label>.csv, e.g. R01-20261017-183529-123-rider_ok.csv
      const m = name.match(/-\d{8}-\d{6}-\d{3}-(.+)\.csv$/);
      const label = m ? m[1] : '';
      append(book, 'Clips', ['received', 'rider', 'file', 'label', 'link'], [new Date(), rider, name, label, file.getUrl()]);
    }
    return reply({ ok: true });
  } finally {
    lock.releaseLock();
  }
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function rootFolder() {
  const it = DriveApp.getFoldersByName(ROOT);
  return it.hasNext() ? it.next() : DriveApp.createFolder(ROOT);
}

function subfolder(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function spreadsheet() {
  const props = PropertiesService.getScriptProperties();
  const id = props.getProperty('sheet');
  if (id) return SpreadsheetApp.openById(id);
  const book = SpreadsheetApp.create('RESQGO pilot');
  DriveApp.getFileById(book.getId()).moveTo(rootFolder());
  props.setProperty('sheet', book.getId());
  return book;
}

/** Ride files are a header line plus one data row. */
function appendCsv(book, tab, csv) {
  const rows = Utilities.parseCsv(csv).filter(r => r.length > 1);
  if (rows.length < 2) return;
  append(book, tab, ['received'].concat(rows[0]), [new Date()].concat(rows[1]));
}

function append(book, tab, header, row) {
  const sheet = book.getSheetByName(tab) || book.insertSheet(tab);
  if (sheet.getLastRow() === 0) sheet.appendRow(header);
  sheet.appendRow(row);
}
