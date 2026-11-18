# Prompt for building the RESQGO presentation

Copy everything below the line into another AI tool. Attach the 11 screenshots from
`docs/screenshots/` in the same message.

---

You are helping me build a PowerPoint presentation for my final-year engineering project. I present
it tomorrow to a panel of evaluators. Please build the whole deck.

## Rules for the writing

- **Simple English only.** Short sentences. A reader who is tired at 4pm should understand every
  line the first time.
- No buzzwords: do not write "leverage", "seamless", "revolutionary", "cutting-edge", "empower",
  "disrupt", "game-changing".
- Every slide: a clear title, at most 5 bullet points, at most 12 words per bullet.
- Never invent a number. Use only the numbers I give you below. If you think a slide needs a figure
  I have not given, write `[verify this]` next to it instead of guessing.
- Write speaker notes under every slide: 3 to 4 sentences of what I should say out loud, in the
  same simple English.

## Design

- 16:9 widescreen.
- Colours: deep charcoal `#1A1A1A` for text, warm cream `#FAF5ED` for backgrounds, burnt orange
  `#A0522D` for headings and accents, strong red `#C62828` for anything about crashes or SOS.
  These are the real colours of the app.
- One clear sans-serif font throughout. Titles around 32pt, bullets around 20pt. Nothing under 16pt.
- Plenty of white space. Phone screenshots on the right or in a row, text on the left.
- Keep phone screenshots at their real tall shape. Do not stretch them.
- No clip art, no stock photos of motorbikes, no emoji.

## The screenshots I am attaching

Use each one where I say. They are real screens from the working app.

| File | What it shows |
| --- | --- |
| `monitoring.png` | Live sensor readings while a ride is being watched |
| `impact.png` | The moment of the hit: a red spike crosses the trigger line |
| `alarm.png` | Full red screen, "Are you OK?", counting down |
| `sos-sent.png` | SOS sent: SMS delivered, call placed |
| `call.png` | The phone actually dialling the emergency contact |
| `home-demo.png` | Home screen, demo mode |
| `home-nav.png` | Home screen, navigation mode |
| `search.png` | Searching for a destination |
| `route.png` | Distance and time for a two-wheeler |
| `setup-contacts.png` | Adding emergency contacts and choosing the timer |
| `setup-modes.png` | Settings for demo mode and pilot data |

---

# THE CONTENT

Build these slides in this order.

### Slide 1 — Title
RESQGO
Ride safe. Help faster.
Automatic crash detection and emergency SOS for two-wheeler riders
Ashutosh Mishra · Final-year project
github.com/ashutoshm0/RESQGO-v2

### Slide 2 — The problem
- In India, road crashes killed about 1,68,000 people in 2022 (Ministry of Road Transport and
  Highways). Two-wheeler riders are the largest group. `[verify this figure before presenting]`
- Delivery riders work 8 to 12 hours a day, often alone.
- After a bad crash, a rider may be unconscious. Nobody can call for help.
- The first hour decides whether someone lives. Every minute of delay matters.

### Slide 3 — Why existing options do not fit
Make this a simple table.

| Option | Why it does not help a gig rider |
| --- | --- |
| Apple Crash Detection | Needs an expensive iPhone, tuned for cars |
| Google Pixel safety | Needs a Pixel phone, tuned for cars |
| Smartwatches | Cost extra, most riders do not wear one |
| Manual SOS buttons in apps | Useless if the rider is unconscious |

One line at the bottom: **Nothing cheap, automatic and built for two-wheelers.**

### Slide 4 — What RESQGO is
One sentence, large on the slide:

**An Android app that notices a crash and sends help, even when the rider cannot.**

Then three short points:
- Runs on cheap Android phones riders already own
- Works without internet
- No monthly cost to run

### Slide 5 — How it decides a crash happened
This is the most important slide. Explain the three conditions as three boxes with arrows between
them:

1. **A hard hit** — more than 4 g of force
2. **While moving** — the rider was above 15 km/h in the last 5 seconds
3. **Then stillness** — the phone barely moves for 10 seconds after

Bottom line: **All three must happen together.**

Then explain why, in simple words:
- A pothole gives a hit, but the rider keeps moving. No alarm.
- A dropped phone gives a hit, but there was no speed. No alarm.
- A real crash gives all three.

Speaker note: this is the idea that makes the app usable. A system that cries wolf gets switched off.

### Slide 6 — What the rider sees
Put `monitoring.png`, `impact.png`, `alarm.png`, `sos-sent.png` in a row of four, left to right,
with a short caption under each:
1. Watching the ride
2. The hit is detected
3. "Are you OK?" — the rider has 20 seconds
4. No answer, so the SOS goes out

Speaker note: mention that the alarm appears over whatever app is open, and plays at alarm volume
even if the phone is on silent.

### Slide 7 — What actually gets sent
Use `call.png` on the right. On the left, list what happens when nobody answers:
- An SMS to every emergency contact, with a Google Maps link to the exact spot
- A phone call to the first contact
- The message tells them to call 112 or ambulance 108
- Location sent again every 2 minutes until the rider says they are safe
- One tap sends "I'm safe" if it was a false alarm

Add the real message text in a box:

> RESQGO ALERT: Ashutosh may have crashed at 9:42 PM.
> Location: https://maps.google.com/?q=28.59310,77.21970
> Battery 64%.
> If you can't reach Ashutosh, call 112 (ambulance 108).

### Slide 8 — A real problem we found and fixed
Title it: **Testing found a bug that would have cost lives**

- On a phone with one SIM, the SOS worked.
- On a phone with two SIMs, it silently failed. The app waited at a SIM chooser nobody could tap.
- Fix: the app now picks the SIM itself. If one SIM fails, it tries the other.
- Every send shows its result on screen, so nothing fails quietly.

Speaker note: say this is why real-device testing matters, and that silent failure is the worst
possible behaviour for a safety app.

### Slide 9 — Why it works without internet
Make this a simple comparison:

| The usual way | RESQGO |
| --- | --- |
| Cloud service sends the SMS | The rider's own SIM sends it |
| Needs mobile data | Needs only phone signal |
| Costs money per message | Free |
| Needs a server | No server at all |

Bottom line: **Crash detection runs on the phone. The SMS and call go through the SIM. Nothing in
the middle can break.**

### Slide 10 — Navigation
Put `home-nav.png`, `search.png`, `route.png` in a row.
- Search any place by name
- See the distance and time for a two-wheeler, not a car
- Turn-by-turn opens in Google Maps, which riders already know
- Safety monitoring keeps running behind it

Speaker note: explain we deliberately did not rebuild Google Maps. Riders already trust it, and
rebuilding it would cost money per request for no benefit.

### Slide 11 — How it is built
A simple diagram, top to bottom:

```
Phone sensors (accelerometer, GPS)
        ↓
Background service  →  crash rules
        ↓
Alarm + countdown screen
        ↓
SOS: SMS and call through the SIM
```

Beside it, the tools used:
- Kotlin and Jetpack Compose
- Android foreground service, so it keeps running in other apps
- Google Places and Routes for navigation
- Python for training the crash model
- No Firebase, no Twilio, no paid service

### Slide 12 — Settings the rider controls
Put `setup-contacts.png` and `setup-modes.png` side by side.
- Emergency contacts, and who gets the call first
- How long to wait before the SOS: 20, 30, 45 or 60 seconds
- Never longer than 60 seconds, because help should not wait
- A "Send test SOS" button, so a rider can check it works on their own phone

### Slide 13 — Testing done so far
- 20 automated tests, covering the crash rules, the SMS path and the model
- Tested on real phones: Samsung Galaxy M11 (Android 12) and Galaxy A01 (Android 16)
- Also tested on Android 15
- Checked that a resting phone, a dropped phone and a pothole do **not** raise an alarm
- Full chain run end to end: shake, alarm, countdown, SMS delivered, call placed

### Slide 14 — Where the project stands
Be honest here. Evaluators respect this more than big claims.

**Done**
- Crash detection and the SOS chain work and are tested
- Runs in the background while other apps are open
- Tools built to collect real riding data

**Not done yet**
- No real-road pilot, so we cannot yet state false alarms per hour
- Battery cost over a full shift is not measured
- The machine learning model is trained on simulated data and only observes; it never blocks an alarm

One line at the bottom: **This is not a finished safety product yet, and we do not claim it is.**

### Slide 15 — What comes next
A simple timeline, four steps:
1. **Pilot** — 5 to 10 riders, 2 weeks, real roads, app collecting data
2. **Measure** — false alarms per 100 hours, battery per hour, detection rate
3. **Train** — build the crash model on that real data to cut false alarms
4. **Partner** — talk to delivery fleets, bike rentals and insurers, who have a reason to pay

### Slide 16 — Thank you
RESQGO — Ride safe. Help faster.
Code and demo video: github.com/ashutoshm0/RESQGO-v2
Ashutosh Mishra

---

## Also build these backup slides at the end

Mark them "Backup — only if asked". Evaluators often ask these.

**B1 — Why does WhatsApp not send automatically?**
WhatsApp does not allow any app to send messages on a user's behalf. So RESQGO opens the chat with
the message already typed, and the rider taps send. For an unconscious rider, the SMS and the call
are what matter, and those are fully automatic.

**B2 — What stops false alarms?**
The three conditions on slide 5. Plus the rider gets 20 seconds to tap "I'm OK", and can send an
"I'm safe" message even after the alert goes out.

**B3 — What if the phone has no gyroscope?**
Many cheap phones do not have one. RESQGO only needs the accelerometer, which every phone has. On
phones without a gyroscope the app shows tilt instead, worked out from the accelerometer.

**B4 — What about the rider's privacy?**
Location is only sent during an emergency, and only to contacts the rider chose. Pilot data uses a
code like R01 instead of a name, and carries no location. Riders sign a consent form and can have
their data deleted.

**B5 — Does this work on any Android phone?**
Android 8.0 and above. Tested on Android 12, 15 and 16. The phone must have an accelerometer and a
SIM that can send SMS.

---

## Last instructions

- Build all 16 main slides plus the 5 backup slides.
- Put the speaker notes in the notes area of each slide, not on the slide itself.
- Give me the finished file as a .pptx I can download.
- At the end, write me a short list of anything you marked `[verify this]` so I can check those
  numbers before I present.
