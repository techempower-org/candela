# TechEMPOWER screen (Discord, 211 and help tools)

**(a) What exists.** The TechEMPOWER home (`techempower/home`, "Free help — read out loud."):
- **Do I qualify?**: an offline benefits screener (`techempower/screener`).
- **Browse Resources**: guides on tech, EBT support and digital safety.
- **Learning paths** (`techempower/learn`).
- **Understand a letter**: a letter decoder (`techempower/decoder`).
- **Peer Support**: the TechEMPOWER Discord.
- **Make the call**: program phone numbers, scripts and a checklist (`techempower/calls`).
- **Call 211** (United Way local help).
- **Household profile**: form auto-fill.
- **Deadline keeper**: photograph a letter to get a reminder (`techempower/deadline`).
- **About TechEMPOWER** (`techempower/about`).

The top bar carries a **211** phone icon and a **Discord** icon on the Library, TechEMPOWER and other screens.

**(b) How a user reaches it.** On Library, tap the **TechEMPOWER** card ("Free books, guides, and help read out loud."). Or **Settings → Benefits**. The 211 and Discord icons are in the top bar.

**(c) How to drive it.**
```bash
$V nav techempower && $V text
$V tap "Do I qualify?" && $V text           # screener
$V tap "Call 211, local help" --contains    # leaves Candela for the dialer (see below)
```

**(d) What lies.**
- **The 211 and Discord icons leave the app** (checked on v1.16.0):
  - 211 fires `ACTION_DIAL` and opens `com.android.dialer`. It dials nothing on its own.
  - Discord opens a browser (`org.chromium.webview_shell` on the emulator, which has no Discord app).
  - Afterwards `health` **fails on the resumed activity**, by design. Any `nav` brings Candela back to the front first (no cold start); then re-run `health`.
- The two icons have **no `text`, only a `content-desc`** ("Open the TechEMPOWER peer-support Discord.").
- There are **two "TechEMPOWER" nodes** on Library (the card label and a screen label). An exact `tap TechEMPOWER` hits the card first, which is correct.
- **Not verified:** that the Discord invite lands in #welcome (#1820). Check that in a browser, not with adb.
