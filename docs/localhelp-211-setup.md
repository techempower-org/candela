# Local Help (211) — setup and data access

`source-localhelp` (#1465) is a browsable, read-aloud directory of local social
services (food, housing, health care, rent help, crisis lines …). Each service
is narrated as a one-chapter "book": what it is, who runs it, the address,
phone numbers, hours, who qualifies, cost, and how to get help. Every
narration ends with "call or text 2-1-1".

It reads the **211 National Data Platform (NDP)** Search API run by United Way
Worldwide (`https://api.211.org/search/v1`, docs at <https://apiportal.211.org>).

## Why 211 NDP, not findhelp.org

| Option | Access | Verdict |
|---|---|---|
| **findhelp.org** (formerly Aunt Bertha) | Partner APIs only, under a sales contract (payers, health systems, large nonprofits). No self-serve key. | Not usable without a partnership. |
| **211 National Data Platform** | Self-serve developer account at apiportal.211.org → free **Trial** key. Production access via the NDP *API Request Form*, with permission from each 211 whose data is used. | **Used.** The only US-wide 211 directory with a documented public API. |
| Individual 211 / Open Referral HSDS feeds (211 LA, iCarol-hosted 211s …) | Per-centre agreements; no key-free US feed found. | Possible later, one region at a time. |

## Turning it on (development)

1. Create a developer account at <https://apiportal.211.org> and subscribe to
   the **Trial** product to get a subscription key.
2. Add it to the gitignored `local.properties`:

   ```properties
   TWO11_API_KEY=<your key>
   ```

   CI reads the same value from the `TWO11_API_KEY` Actions secret.
3. Build, then switch on **Local Help (211)** in Settings → Plugins. It is off by
   default, and a build without a key just shows "The 211 local-help directory
   isn't set up in this build. You can still call or text 2-1-1." It sends no
   requests.

The Trial product is limited to **10 calls a minute, 1,000 a day, 10 results
per search, first page only**. Its terms allow development and testing only.

## What's needed before it ships to users

The 211 NDP terms say: *"Any use of this data for production, operational,
commercial, or other external purposes requires the prior permission of each
participating 211 center whose data is being accessed or used."* So a public
release needs:

1. **A production API product from United Way Worldwide.** Submit the NDP
   *API Request Form* (via register.211.org), describing Candela as a free,
   nonprofit (TechEMPOWER) accessibility app that reads 211 listings aloud.
2. **Data-owner permission.** Either national permission brokered by UWW, or
   permission from specific 211s for a pilot region (for example 211 Connecting
   Point / Nevada County).
3. **A quota that fits.** The key is compiled into the APK, so every install
   shares one key's quota. The 1,000/day Trial limit covers testing only. If
   UWW needs per-user attribution or key secrecy, add a small proxy (the key
   stays on the server and the app calls the proxy). `LocalHelpApi.baseUrl` is
   the only thing that would change.

## How it behaves

- **Popular / New**: food help near the device. With no location given, the
  API geocodes the caller's IP address, so "near me" needs no location
  permission.
- **Genres**: 13 everyday need categories (Food, Housing & shelter, Rent &
  utility help, Health care, Mental health …).
- **Filters**: ZIP code or city, category, distance (5/10/25/50 miles), sort
  by best match or nearest first.
- **Detail**: `ServiceAtLocation/v2`. The app reads the fields it can find
  and skips the rest, because 211 records vary by contributing centre. If the
  detail call fails, it falls back to what the search result already had.
