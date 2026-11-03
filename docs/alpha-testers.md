---
layout: default
title: Become a Candela alpha tester
description: Help test Candela, TechEMPOWER's free Android app that reads books, articles and documents aloud. Five minutes to join, thirty seconds to try.
---

# Become a Candela alpha tester

Candela is a free Android app from TechEMPOWER, a nonprofit that closes the digital divide for low-income individuals and families. It reads text aloud like an audiobook. Pick a free book from Project Gutenberg, a public-domain recording from LibriVox, a Wikipedia article, or a PDF on your phone, and Candela reads it to you in a natural voice that runs on the phone itself: no cloud, no API keys, nothing to pay. You don't need to sign in to start listening; sync and the other sign-ins are optional. You need Android 8.0 or newer, and an internet connection to browse and to download a voice the first time. After that, the chapters and voices stay on your phone.

**OPT-IN LINK: `<from Play Console, vale fills>`**

## Try it in 30 seconds

1. **Install** Candela from the opt-in link above (Google Play, alpha track).
2. **Open one free source:** Browse → Project Gutenberg (or LibriVox) → pick any book.
3. **Play a chapter.** On first launch Candela asks you to pick a voice; a Piper voice is a quick download (about 14–30 MB).
4. **Change the voice:** on the play screen, tap the voice settings icon (the soundwave with a sparkle) and pick another.
5. **Tell us one thing** that worked, broke or confused you, on the sign-up form or by email (below).

## Sign up

**Email:** write to [hi@techempower.org](mailto:hi@techempower.org?subject=Alpha%20tester) with the subject **Alpha tester**. Include the Google account email you use on your phone, your Android version and your phone model.

**Form:** coming soon (TechEMPOWER's Google Form). It asks for:

| Field | Why | Required |
|---|---|---|
| First name | So we know what to call you | No |
| Google account email | Google only lets that account install the alpha: it goes on the Play testers list | Yes |
| Android version | Candela needs Android 8.0 or newer | Yes |
| Phone model | So we can match a bug to a device | Yes |
| How you heard about Candela | So we know which posts reach people | No |
| One thing to tell us (optional) | Your first report | No |

Your email goes on the Play Console testers list and in TechEMPOWER's password vault. It is never written to this page or to the code repository.

## Google's rule for new apps

Google Play requires some developers to run a **closed test with at least 12 testers opted in for 14 days in a row** before the app can go to production. The rule applies to **personal** developer accounts created after November 2023; **organization** accounts are not subject to it. That description is Google's published policy, not something our docs record.

**Does it apply to Candela?** **Unknown.** None of our Play docs says which kind of developer account Candela is published under:

- `docs/play-store-readiness.md` doesn't mention testers or the account type.
- `docs/play-console-prerequisites.md` doesn't either. Lines 51–57 only cover the `applicationId` and creating the Console app.
- The only test plan is `docs/play-store-listing.md` line 374: "suggest 7-day internal → 14-day closed (JP + a handful of early-access users) → staged rollout starting at 5%".

**The question:** is the Play developer account that publishes `org.techempower.candela` an **Organization** account (verified with a D-U-N-S number) or a **Personal** account, and when was it created? Play Console → Settings → Developer account → Account details shows both.

- If it's a personal account created after November 2023, we need 12 testers opted in for 14 continuous days.
- If it's an organization account, the 12-for-14 gate doesn't apply, but more testers still means better coverage.

## Posts

Three short versions, each under 80 words. Swap in the opt-in link before posting.

**Newsletter (61 words)**

> **Help us test Candela.** Candela is TechEMPOWER's free Android app that reads books, articles and PDFs aloud in a natural voice, right on your phone. No account needed to start listening. We're looking for alpha testers with Android 8.0 or newer: install, play one chapter, tell us one thing. Join here: `<OPT-IN LINK>` or email hi@techempower.org with the subject "Alpha tester".

**Discord (56 words)**

> 🕯️ **Alpha testers wanted for Candela!** Our free Android app reads Project Gutenberg books, LibriVox, Wikipedia and your own PDFs aloud in a natural voice on your phone. Got Android 8.0+? Install from `<OPT-IN LINK>`, play a chapter, try a different voice, and post one thing that worked or broke in this channel. Thank you! 💛

**Facebook (56 words)**

> Do you like listening more than reading? Help us test Candela, TechEMPOWER's free Android app that reads books, articles and documents aloud in a natural voice, with nothing to pay and no account needed to start. We need alpha testers with Android 8.0 or newer. Join: `<OPT-IN LINK>`, or email hi@techempower.org with the subject "Alpha tester".

## Tracker

This page is public, so the tracker carries **no personal data**:

- A row is a tester **ID** (`T-001`, `T-002`, …). It holds a first name only if the tester asked to be listed.
- Emails live only in the Play Console testers list and the vault item **candela alpha testers**.
- `scripts/testers-count.sh` prints how many rows there are and how many have opted in, for the daily pass.

<!-- testers:start -->
| Date joined | Tester | Opted in | Device |
|---|---|---|---|
<!-- testers:end -->
