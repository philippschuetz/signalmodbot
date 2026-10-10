# signalmodbot

A Java bot for administering Signal group chats, running in a single Docker
container. It gets its Signal identity either by linking to your existing
account as a secondary device, or by registering a new account for a phone
number, using
[signal-cli](https://github.com/AsamK/signal-cli) (which itself is built on
the library you originally pointed me at,
[Turasa/libsignal-service-java](https://github.com/Turasa/libsignal-service-java)),
talking to it over its JSON-RPC daemon interface. The Java app never talks to
the Signal servers directly -- signal-cli does, and does the protocol/crypto
work; the Java app is the bot logic on top.

## What it does

- Authenticates an admin via a fixed token: DM the bot `/auth <ADMIN_TOKEN>`.
- Lets the admin define named groups for a Signal group chat (`/poll`);
  members sort themselves by reacting -- see "Workflow"
  below.
- Logs every message sent in chats the bot is a member of.
- Posts a per-group message summary back into the chat, either on a timer
  (`SUMMARY_INTERVAL_MINUTES`) or on demand (`/summary <chat>`), with message
  and whitespace-separated word counts for each group.

## About "groups"

Real Signal group *membership* changes (adding/removing people from an
actual Signal group) are implemented in modern Signal clients using GroupsV2,
which requires zero-knowledge-proof group-state crypto (`libzkgroup`).
signal-cli implements this internally, but only exposes it through its own
group-management commands (`updateGroup --add-member`, etc.), which operate
on *existing* signal-cli group chats and require the bot to already be an
admin of that group.

This bot uses that for what it *can* safely do (send to a group chat), but
"sorting people into groups" is implemented as **internal bookkeeping**:
groups are just named categories, and people choose their own by reacting to
a poll message (see below). If you specifically want the bot to
create/manage real, separate Signal group chats and move members between
them, that's a meaningfully bigger feature (built on signal-cli's
`updateGroup` commands) -- ask and I'll add it as a follow-up.

## Workflow

1. **Add the bot to the Signal group** you want to organise (normal "add member"
   in the Signal app -- for Option A of setup that's the account the bot is
   linked to).
2. **Manage it by DM.** Message the bot's account: `/auth <ADMIN_TOKEN>`, then
   `/chats` -- the bot lists its group chats with numbers.
3. **Start group selection:** `/poll 1 TeamA TeamB TeamC` (chat number from
   `/chats`, then 2-9 group names). The bot posts a message in that group with
   one numbered emoji per option. Starting a new poll resets all existing
   group assignments for that chat.
4. **Members choose themselves** by reacting with the emoji for their group.
   Reacting with a different emoji switches groups; removing the reaction
   leaves the group. One group per person per chat.
5. **Confirm the numbers by DM:** `/count 1` replies with the member count
   per group, how many have chosen, and (when Signal provides the member
   list) how many haven't chosen yet.

To give late joiners a fresh message to react to, use `/pollresend 1`. This
reposts the saved choices and keeps existing assignments; old poll reactions
are no longer used.

Other DM commands: `/groups <chat>` (who picked what, by display name),
`/members <chat>` (group members by display name), `/seen <chat>` (people the
bot has seen post, by display name),
`/summary <chat>` / `/summaryall` (per-group message summaries), `/info` (bot
software, signal-cli, Java runtime, uptime, and configuration), `/help`.
Every reply names the chat it acted on, so if you add the bot to another
group and the `/chats` numbering shifts, you'll notice.

Running `/poll` again for a chat replaces the groups on offer; people react
to the new message. Summaries attribute each logged message to the group its
sender picked; anyone who hasn't picked shows up as "Unsorted".

## Setup

### 1. Configure the environment

```sh
cp .env.example .env
# Edit .env and set ADMIN_TOKEN to a long, random secret.
```

### 2. Build the image

```sh
BOT_COMMIT="$(git rev-parse --short HEAD)" docker compose build
```

The build embeds the software version and short commit hash for `/info`.
`SIGNAL_CLI_VERSION` can also be set in `.env` to select the signal-cli version
used by the image (default: `0.14.9`).

### Publishing from GitHub Actions

The `.github/workflows/publish-image.yml` workflow builds and publishes a
date-tagged image to `ghcr.io/<owner>/<repository>` when a commit is pushed to
`main`. It uses the repository's `GITHUB_TOKEN`, embeds the short commit hash
for `/info`, adds Docker metadata labels, and generates a build provenance
attestation. Ensure the repository's Actions settings allow `GITHUB_TOKEN` to
write packages and publish attestations.

### 3. Link the bot to a Signal account (one-time)

The bot runs `signal-cli link` and appears under **Settings -> Linked Devices**
like Signal Desktop. No separate phone number is needed.

```sh
docker compose run --rm signalmodbot link
```

The QR code is printed in the terminal. On your phone, open **Signal ->
Settings -> Linked Devices -> Link New Device** and scan it. The link command
uses `DEVICE_NAME` from `.env` (default `signalmodbot`).

### 4. Run it

```sh
docker compose up -d
```

Compose bind-mounts `./data` to `/data`, so the linked Signal account, settings,
and message history survive container recreation.

### 5. Use it

Follow the **Workflow** section above: add the bot to a group in Signal, then
DM it `/auth`, `/chats`, `/poll`, `/count`.

## Environment variables

| Variable                   | Required | Default     | Meaning |
|-----------------------------|----------|-------------|---------|
| `ADMIN_TOKEN`               | yes      | -           | Shared secret the admin DMs to authenticate; set it in `.env` |
| `SUMMARY_INTERVAL_MINUTES`  | no       | `10`        | Auto-summary cadence; `0` disables the timer |
| `DEVICE_NAME`               | no       | `signalmodbot` | Name shown in the Linked Devices list when running `link` |
| `TIME_ZONE`                 | no       | `UTC`       | IANA time-zone ID used for timestamps in summaries (for example, `Europe/Berlin`) |
| `DATA_DIR`                  | fixed    | `/data`     | Container path for the persisted Signal account and bot state |

These four variables are supplied to the container by Docker Compose and can
be changed in `.env`. `ADMIN_TOKEN` and `SUMMARY_INTERVAL_MINUTES` override
their saved settings when set; otherwise, the saved values are used.
`TIME_ZONE` accepts a Java/IANA zone ID and defaults to UTC. `DATA_DIR` is fixed
to `/data` in the image.

Summary lines show message counts without listing sender names.

## A few honest caveats

- **This is a working scaffold, not a battle-tested library.** signal-cli's
  JSON-RPC field names (`sourceNumber`, `dataMessage`, `groupInfo`, etc.) can
  shift slightly between releases. If `IncomingMessageHandler` stops picking
  up messages after a signal-cli upgrade, add a `log.debug(notification)` in
  `RpcClient`'s notification handler to see the raw shape and adjust the
  field names in `IncomingMessageHandler`/`SignalApi` accordingly.
- **The reaction field names specifically (`targetSentTimestamp`, `isRemove`,
  etc.) haven't been verified against a live signal-cli install** -- of
  everything in this codebase, that's the part most likely to need a
  one-line fix once you see real reaction traffic; see the comment at the
  top of `IncomingMessageHandler`.
- **Admin auth is a single shared token, not per-user.** Anyone who learns
  the token and DMs the bot becomes an admin permanently (until `/deauth` or
  deleting `admins.json`). Treat `ADMIN_TOKEN` like a password, and treat the
  `/data` volume as sensitive -- it holds the bot's Signal identity keys.
- **`signal-cli` needs periodic updates.** Signal's servers reject clients
  older than ~3 months, so rebuild the image with a newer
  `SIGNAL_CLI_VERSION` periodically (`docker build --build-arg
  SIGNAL_CLI_VERSION=x.y.z .`). Current releases need JRE 25, which is why
  the runtime image is `eclipse-temurin:25-jre`. The Dockerfile currently
  pins signal-cli `0.14.9`.
- Only text messages are logged for summaries; attachments are ignored.
- **Phone-numberless ("Signal Login") primary accounts are untested.**
  `listAccounts()` can fall back to matching a UUID when no phone number is
  found, but that fallback is unverified against a live account.
