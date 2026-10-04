# Push to Candela (desktop → phone)

Send a URL or some text from your desktop, a homelab box or a script to
Candela on your phone. It shows up in your Library, ready to listen to
offline. This is the desktop counterpart of Share → Candela on the phone.
Issue [#1469](https://github.com/techempower-org/candela/issues/1469).

## Quick start

You need Candela signed in to sync (Settings → Account → Sync) on the phone,
and Python 3.8+ on the desktop. The script uses only the standard library.

```bash
# once: sign in with the SAME email the phone syncs with
tools/candela-push login you@example.com      # emails a code; paste it

# push things
tools/candela-push https://example.com/some-article
tools/candela-push --title "Standup notes" --text "Remember to…"
tools/candela-push --file ~/notes/chapter-3.txt
xclip -o | tools/candela-push -                # stdin as text

# look at / manage the inbox
tools/candela-push list
tools/candela-push remove 1a2b3c4d            # id prefix from `list`
tools/candela-push clear
tools/candela-push logout
```

Symlink it onto your `PATH` if you use it a lot:
`ln -s "$PWD/tools/candela-push" ~/.local/bin/candela-push`.

The item lands in the Library **the next time Candela comes to the
foreground** (at most once a minute) or on the next cold start. No push
notification: Candela has no server of its own to send one.

### The app id

The script needs the InstantDB **app id** the Candela build was made with.
The id is not a secret (every APK carries it), but this repo does not commit
it. The script looks for it in this order:

1. `--app-id <id>` on `login`
2. `CANDELA_APP_ID` (or `INSTANTDB_APP_ID`) in the environment
3. the saved config from a previous `login`
4. `INSTANTDB_APP_ID` in this checkout's `local.properties`

Credentials (your user id and an InstantDB refresh token) are saved to
`~/.config/candela/push.json` with mode `600`. Override the path with
`CANDELA_PUSH_CONFIG`. `logout` revokes the token on the server and deletes
the file.

## What gets imported, and how

| Push | On the phone |
|---|---|
| a single `http(s)` URL | routed like Share → Candela / Magic-add (`FictionRepository.addByUrl`): a Royal Road / AO3 / GitHub / … link becomes that source's fiction; anything else is read by the Readability source. Then it is added to the Library. If several backends claim the URL, the top-ranked one wins, since nobody is at the phone to answer the chooser. |
| text (`--text`, `--file`, stdin, or several words) | saved as a local text document in the on-device document store (the one OCR scans use), split into parts of about 15,000 characters at paragraph breaks, and added to the Library. Title: `--title`, else the first line. |

Limits (enforced on both ends): URL ≤ 4,096 characters, text ≤ 200,000
characters, title ≤ 300. Longer text is refused rather than cut short;
push a URL instead.

A URL that fails for a transient reason (offline, rate-limited, 5xx,
Cloudflare) is retried on later polls, up to 5 times. A URL no source can
handle, or a 404, is dropped.

## Design

**Transport: the existing InstantDB sync channel.** No new server and no new
credentials. The desktop signs in to the same InstantDB app with the same
magic-code flow the phone uses, and writes through the same admin HTTP API
(`/admin/query`, `/admin/transact`) with `as-token` impersonation. See
[sync.md](sync.md).

**One inbox row per user, no schema change.** The inbox is one more blob
domain:

```
entity: blobs
id:     java.util.UUID.nameUUIDFromBytes("inbox:<instant-user-id>")   (UUID v3, no namespace)
attrs:  { payload: "<JSON string>", updatedAt: <epoch ms> }
```

The `blobs` entity and its `payload` / `updatedAt` / `userId` attributes are
the same ones the phone's settings, pronunciation, bookmarks and secrets
domains use (`core-sync/instant.attrs.json`), so the pusher needs no schema
change of its own. Every write carries the `userId` owner stamp, which the
app's permissions (`core-sync/instant.perms.json`) require. Python's `uuid.uuid3`
prepends a namespace and gives a different id, so the script carries its
own port of the Java function. Both test suites pin the same test vector.

**Payload (v1):**

```json
{
  "v": 1,
  "items": [
    { "id": "<uuid4>", "kind": "url",  "url": "https://…", "createdAt": 1727400000000, "from": "katana" },
    { "id": "<uuid4>", "kind": "text", "text": "…", "title": "…", "createdAt": 1727400001000 }
  ],
  "updatedAt": 1727400001000
}
```

Unknown fields are ignored on both ends. An item with an unknown `kind` is
left alone by the phone and **not** marked as seen, so a future app version
can still import it.

**Ownership: pushers write, the phone only reads.** A pusher does a
read-modify-write (append, prune, write), then reads back to check its item
survived and retries if a concurrent pusher overwrote it. The phone never
writes the row. It keeps a per-device "seen" set in SharedPreferences
(`sync.inbox.seen.v1`). If the phone deleted items it had consumed, it would
race a desktop append and could lose the new item. With a read-only phone
that race cannot happen.

**Staying small.** Every push drops items older than 30 days, then drops the
oldest items until the row is under about 1 MB. The phone intersects its
seen set with the ids still on the row, so its local state is bounded by the
row too.

**New phone.** On a device's first inbox pull, items older than 24 hours
are marked seen without being imported, on the assumption that the old
phone already took them. Newer items are imported.

**Sign-out.** Signing out of sync on the phone deletes the inbox row along
with every other sync row (#1139). The desktop's own token stays valid until
you run `candela-push logout`.

### Code map

| Piece | Where |
|---|---|
| Wire types, limits, pure selection/fold logic | `core-sync/…/sync/domain/Inbox.kt` (`InboxLogic`) |
| Syncer (pull → sink, seen-set, purge) | `core-sync/…/sync/domain/InboxSyncer.kt` |
| Pull trigger | `SyncCoordinator.requestPull("inbox")` from `MainActivity.onStart` (throttled to one per minute), plus the cold-start `initialize()` pull |
| Library import | `app/…/data/InboxSinkImpl.kt` |
| Desktop CLI | `tools/candela-push` |
| Tests | `core-sync/src/test/…/InboxSyncerTest.kt`, `app/src/test/…/InboxSinkImplTest.kt`, `tools/test_candela_push.py` (`python3 -m unittest tools/test_candela_push.py`) |

### Writing your own pusher

Anything that can make two HTTPS POSTs can push: an Outline webhook, a
realmwatch action, a browser bookmarklet behind a small relay. Follow the
same four steps as `tools/candela-push`:

1. Get a refresh token with `POST /runtime/auth/send_magic_code` and
   `POST /runtime/auth/verify_magic_code` (`{"app-id", "email", "code"}` →
   `user.id`, `user.refresh_token`).
2. Read the row with `POST /admin/query?app_id=…`, using headers `app-id`
   and `as-token: <refresh token>`.
3. Append your item, prune, and write the row with
   `POST /admin/transact?app_id=…`, using the step
   `["update","blobs",<row id>,{"payload":…,"updatedAt":…,"userId":<your user id>}]`. The `userId` owner stamp is required: the app's permissions only allow a row whose `userId` matches the signed-in user.
4. Optionally read the row back to confirm your item survived.

## Privacy

Pushed URLs and text are stored in your InstantDB sync record, next to your
library and bookmarks, until they are 30 days old, until you run
`candela-push clear`, or until you sign out of sync on the phone. See
[privacy.md](privacy.md).

## Known limitations

- Delivery waits for Candela to come to the foreground. Background polling
  (WorkManager) and real-time delivery (the InstantDB WebSocket) are v2
  work.
- The phone does not tell the desktop that an item was imported. `list`
  shows everything still on the row, imported or not.
- Two desktops pushing in the same instant can still clash. The read-back
  check turns a lost write into a retry, and after three failed tries the
  script reports an error.
