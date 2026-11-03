---
layout: default
title: Become a Candela alpha tester
description: Help test Candela, TechEMPOWER's free Android app that reads books, articles and documents aloud. Five minutes to join, thirty seconds to try.
---

# Become a Candela alpha tester

Candela is a free Android app from TechEMPOWER, a nonprofit that closes the digital divide for low-income individuals and families. It reads text aloud like an audiobook. Pick a free book from Project Gutenberg, a public-domain recording from LibriVox, a Wikipedia article, or a PDF on your phone, and Candela reads it to you in a natural voice that runs on the phone itself: no cloud, no API keys, nothing to pay. You don't need to sign in to start listening; sync and the other sign-ins are optional. You need Android 8.0 or newer, and an internet connection to browse and to download a voice the first time. After that, the chapters and voices stay on your phone.

**Status** (Play Console, read 2026-11-03): closed testing track **Alpha**, release 279 (v1.16.0). The testers list holds 15 people and **4 have opted in**. Goal: **12 opted in**, then 14 days in a row.

**How to join:**

1. **Email us first** (see [Sign up](#sign-up)). Google only lets accounts on our testers list install the alpha, so we add your Google account email to the list.
2. **Opt in** on the web, signed in with that same Google account: [https://play.google.com/apps/testing/org.techempower.candela](https://play.google.com/apps/testing/org.techempower.candela)
3. **Install** from Google Play on your phone (this link only opens once you've opted in and are signed in with that account): [https://play.google.com/store/apps/details?id=org.techempower.candela](https://play.google.com/store/apps/details?id=org.techempower.candela)

## Try it in 30 seconds

1. **Install** Candela from Google Play after you opt in (steps above).
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

Google Play requires a **closed test with at least 12 testers opted in for 14 days in a row** before an app from a personal developer account can go to production.

**It applies to Candela.** The account that publishes `org.techempower.candela` is a **personal** account: the Console home reads "TechEMPOWER.org · Personal account". The Apply for production checklist shows:

- "Publish a closed testing release" — done.
- "At least 12 testers opted-in" — **4 currently opted in.**
- "Run the closed test with at least 12 testers for at least 14 days" — not started; the 14 days count once 12 have opted in.

Source: Play Console home and the Apply for production checklist, read 2026-11-03.

## Posts

Three short versions, each under 80 words, ready to post.

**Newsletter (64 words)**

> **Help us test Candela.** Candela is TechEMPOWER's free Android app that reads books, articles and PDFs aloud in a natural voice, right on your phone. No account needed to start listening. We're looking for alpha testers with Android 8.0 or newer: install, play one chapter, tell us one thing. Email hi@techempower.org with the subject "Alpha tester" and we'll add you, then opt in: https://play.google.com/apps/testing/org.techempower.candela

**Discord (65 words)**

> 🕯️ **Alpha testers wanted for Candela!** Our free Android app reads Project Gutenberg books, LibriVox, Wikipedia and your own PDFs aloud in a natural voice on your phone. Got Android 8.0+? Email hi@techempower.org (subject "Alpha tester") so we can add you, then opt in at https://play.google.com/apps/testing/org.techempower.candela, play a chapter, try a different voice, and post one thing that worked or broke here. Thank you! 💛

**Facebook (60 words)**

> Do you like listening more than reading? Help us test Candela, TechEMPOWER's free Android app that reads books, articles and documents aloud in a natural voice, with nothing to pay and no account needed to start. We need alpha testers with Android 8.0 or newer. Email hi@techempower.org with the subject "Alpha tester" and we'll add you, then opt in: https://play.google.com/apps/testing/org.techempower.candela

## Tracker

This page is public, so the tracker carries **no personal data**:

- A row is a tester **ID** (`T-001`, `T-002`, …). It holds a first name only if the tester asked to be listed.
- Emails live only in the Play Console testers list and the vault item **candela alpha testers**.
- `scripts/testers-count.sh` prints how many rows there are and how many have opted in, for the daily pass.

<!-- testers:start -->
| Date joined | Tester | Opted in | Device |
|---|---|---|---|
<!-- testers:end -->
