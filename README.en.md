# SlipNote

A handwritten note, delivered to your friend's home screen.

Once two people are paired, you write a note and send it. Within seconds the
other person's home screen widget becomes that note. No need to open the app —
just swipe to the home screen and it's there.

## What it does

**Writing**

- Handwriting canvas with five stroke widths and six colors, with speed-based
  line weight
- Pinch to zoom for small writing
- Load a reference image and trace it
- Stickers: cut one out from your photos and send it along with the note

**Receiving**

- Shown directly on a home screen widget, updated on arrival
- Every note you receive is saved to an inbox
- Star the ones you like
- Stickers someone else put on a note can be peeled off into your own
  sticker library and reused on other notes

**Groups**

- Pair with more than one friend on a single account
- Chain mode: A passes to B, B passes to C
- Random mode: delivered to a random group member, with both sides anonymous

## Setup

You'll need a server that can run Node.js, and an Android phone.

### Server

```bash
cd server
npm install
npm start
```

Listens on `8080` by default. To change the port or data directory:

```bash
PORT=9000 DATA_DIR=/var/lib/slipnote npm start
```

For running it long-term on a server, `server/deploy/` has ready-to-use systemd
and nginx configs.

### Android

```bash
cd android
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK.

**Change one line before installing**: `DEFAULT_SERVER` in
`android/app/src/main/java/com/notedrop/demo/net/AppConfig.kt`, pointed at your
own server. Or install first and set the address from the app's connection
settings — same result.

## Tests

```bash
cd server
node test/run-local.mjs test/e2e.js test/groups.js test/auth.js test/stickers.js test/ack.mjs
```

This spins up a temporary server on its own port and data directory, so it
never touches your real data.

Covered: pairing and sending, group chain, anonymous random delivery, account
login, sticker transfer, offline delivery, duplicate delivery, and rejected
unauthorized acknowledgements.

## Layout

```
android/   Android app and home screen widget (Kotlin)
server/    Node.js server (Express + SQLite)
```

The two work independently. The server is a plain HTTP API, and the app can
point at any server address you enter.

## Design decisions

**Why not vendor push services**

Xiaomi, Huawei, OPPO and vivo each have their own push SDK. Integrating all
four is a lot of work. For personal or small-scale use, a persistent connection
is faster and simpler, so this uses WebSocket.

**Messages don't get lost**

The server doesn't mark a note as delivered on its own. It waits for the client
to confirm receipt. Anything unconfirmed stays in the queue and is re-sent on
the next connection.

The client also polls once every 60 seconds. Android often kills background
sockets silently — neither side notices — and relying on the persistent
connection alone leads to messages arriving very late.

**Cutout uses color keying, not AI**

The first attempt used MediaPipe, but it's a semantic segmentation model that
only recognizes about twenty common object classes. Given a product photo or a
screenshot, the output is essentially random.

The current approach works like Photoshop's magic wand: tap an area and the
similarly-colored region connected to it disappears. It's near-flawless on
white-background images, screenshots and handwriting, and it's undoable with an
adjustable tolerance.

**The canvas size is fixed**

The editor toolbar is a drawer that slides away. If the canvas resized with it,
opening and closing the drawer would crop what you'd already written and shift
placed stickers.

So the canvas gets its size once, at first layout, and the screen is just a
window onto it. Export is always the full canvas.

## Known limitations

- Android only. No iOS version.
- Tested mainly on Xiaomi phones; other vendors' widget behavior isn't verified.
- If the OS fully kills the app, real-time notifications stop until it's opened again.
- The server is single-process with SQLite — fine for personal use, not for scale.

## License

MIT
