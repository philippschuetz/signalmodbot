# SignalModBot

A Java bot for administering Signal group chats, running in a single Docker
container. It gets access to Signal by linking your existing
account as a secondary device, using [signal-cli](https://github.com/AsamK/signal-cli).

## What it does

- Authenticates an admin via a fixed token: DM the bot `/auth <ADMIN_TOKEN>`
- Lets the admin define named groups for a Signal group chat (`/poll`);
  members assign themselves to groups by reacting to the poll
- Logs every message sent in chats the bot is a member of
- Posts a per-group message summary back into the chat, either on a timer
  (`SUMMARY_INTERVAL_MINUTES`) or on demand (`/summary <chat>`), with message
  and word counts for each group

## Workflow

1. Add the bot to the Signal group
2. Manage it by DM Message the bot's account: `/auth <ADMIN_TOKEN>`, then
   `/chats`
3. Start group selection: `/poll <chat-number> TeamA TeamB TeamC` (chat number from
   `/chats`, then 2-9 group names). The bot then posts a message in that group with
   one numbered emoji per option. Starting a new poll resets all existing
   group assignments for that chat.
4. Members choose their group by reacting with the emoji for their group.
   Reacting with a different emoji switches groups. Removing the reaction
   leaves the group. One group per person per chat.
5. Confirm the group memberships by DM: `/count <chat-number>` replies with the member count
   per group, how many have chosen, and how many haven't chosen yet.

To give people joining after the first poll a message to react to, use `/pollresend <chat-number>`.
This reposts the poll and keeps existing assignments.

Other DM commands: `/groups <chat>` (group assignments),
`/members <chat>` (group members), `/seen <chat>` (members seen by the bot),
`/summary <chat>` / `/summaryall` (message summaries), `/info` (bot
software, signal-cli, Java runtime, uptime, and configuration), `/help`.

## Setup

### 1. Configure the environment

```sh
cp .env.example .env
```

### 2. Build the image

```sh
docker compose build \
  --build-arg BOT_COMMIT="$(git rev-parse --short HEAD)"
```

### 3. Link the bot to a Signal account (one-time)

Set `LINK_DEVICE=true` in `.env` before starting the container. Startup removes
any existing signal-cli account configuration, runs `signal-cli link`, and
prints a QR code. After linking, the bot continues starting normally.
Because `LINK_DEVICE=true` forces a link on startup, change it back to `false`
after linking and recreate the container:

```sh
docker compose up
# After linking, set LINK_DEVICE=false in .env, then:
docker compose up -d
```

### 4. Run it

```sh
docker compose up -d
```

## Environment variables

| Variable                   | Required | Default     | Meaning |
|-----------------------------|----------|-------------|---------|
| `ADMIN_TOKEN`               | yes      | -           | Secret used in DMs to authenticate; set it in `.env` |
| `SUMMARY_INTERVAL_MINUTES`  | no       | `10`        | Auto-summary cadence; `0` disables the timer |
| `DEVICE_NAME`               | no       | `signalmodbot` | Name shown in the Linked Devices list in Signal client |
| `LINK_DEVICE`               | no       | `false`     | If `true`, startup links a Signal account |
| `TIME_ZONE`                 | no       | `UTC`       | IANA time-zone ID used for timestamps in summaries (for example, `Europe/Berlin`) |
| `DATA_DIR`                  | fixed    | `/data`     | Container path for the persisted Signal account and bot state |